package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.api.client.AddonScenes;
import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Runs a scene at a station: the approach (INTRO), the work (ACTIVE) and stepping away (OUTRO). Times are
 * seconds of a pause-aware frame clock, so motion is smooth at any frame rate. What is specific to a block
 * lives in its {@link ClientStation}.
 * <p>
 * Before it starts, {@link Reach} decides whether the scene can be played where the player is; if not, the block's
 * own window opens. On the way in the player walks first, and the camera leans in only once they have arrived; a
 * player who cannot get there (something in the way) gets the window instead. While working, a player who starts to
 * fall (the ground broken under them) is let go.
 */
public final class Scene {
    private Scene() {}

    public enum Phase { NONE, INTRO, ACTIVE, OUTRO }

    public static final double OUTRO_LENGTH = 1.05;
    /** How long the camera takes to lean in once the player has arrived. */
    public static final double LEAN_LENGTH = 1.2;
    /** The lean when coming straight back to the station just stepped away from ({@link #quick}). */
    static final double QUICK_LEAN_LENGTH = 0.4;
    /** How long this scene's lean takes. */
    private static double leanLength = LEAN_LENGTH;
    /**
     * Coming back to the same station within a few seconds of stepping away from it (Settings.quickReturn): a short way
     * in, a quick lean without the bars, so going back and forth to it is not a cutscene every time.
     */
    static boolean quick;
    /** A station used while the last one was still being stepped away from, begun once that is over. */
    @Nullable
    private static BlockPos pendingPos;
    @Nullable
    private static StationKind pendingKind;
    private static double pendingAt;
    /** The station last stepped away from, and when (the clock), for {@link #quick}. */
    @Nullable
    private static BlockPos leftPos;
    private static double leftAt = -1000;
    /** Share of the intro's progress spent walking ({@link #introProgress}); what the intro stages happens in the lean. */
    static final double WALK_SHARE = 0.3;
    /** About how far the step back on the way out goes (a little further, to be safe). */
    private static final double STEP_BACK = 0.9;
    /** How long the inventory drawer takes to slide away when stepping back. */
    public static final double UI_FADE_OUT = 0.22;

    public static Phase phase = Phase.NONE;
    @Nullable
    public static ClientStation station;
    /** Seconds spent in the current phase. */
    public static double time;
    /** How long the intro is expected to take (walk and lean), for the record. */
    public static double introLength = 1.2;
    /** Intro progress at which the lean began; 1 while the player is still walking. */
    public static double walkShare;
    private static double arrivedAt = -1;
    private static double walkEstimate;
    private static boolean crouch;
    /** Whether the step back on the way out has ground under it (not off a ledge, not into fire). */
    private static boolean stepBack;
    /** Real seconds since stepping away began: the step back is as long whatever the pace of the scene. */
    private static double outroSeconds;
    /** Getting closer to the next waypoint, or stuck: the best distance so far and when it last improved. */
    private static double bestDistance;
    private static double progressAt;
    /** Why the last station used got its own window instead of a scene (empty when the scene was played). */
    public static String refused = "";
    public static double frameDt;
    private static double clock;
    private static long lastNanos;

    private static final List<Vec3> path = new ArrayList<>();
    private static int waypoint;
    private static boolean leaveSent;
    /** The local scene is backed by an ordinary server-owned menu, with no cinematic network channel. */
    private static boolean clientOnly;
    private static int clientMenuId = -1;
    @Nullable
    private static CameraType savedCamera;
    @Nullable
    static CameraRig.Pose outroFrom;
    static double outroWeight = 1;
    private static int lastHurtTime;

    /** Set by the screen: the cursor is over the inventory drawer. */
    public static boolean overPanel;
    /** The hands stay put until the mouse actually moves. */
    private static boolean cursorEngaged;
    private static double mouseStartX, mouseStartY;

    public static double clock() {
        return clock;
    }

    public static boolean active() {
        return phase != Phase.NONE && station != null;
    }

    /**
     * A recipe viewer's page is open over the scene (opened from the field guide). Another screen replacing ours would
     * otherwise drop the scene at once; while consulting, the scene holds still beneath it (the camera stays, the
     * session and the open menu stay) and work goes on when the viewer hands the screen back.
     */
    static boolean consulting;

    /** Opens a viewer's page over the scene. */
    static void consult(Runnable open) {
        if (phase != Phase.ACTIVE) {
            return;
        }
        consulting = true;
        open.run();
        if (Minecraft.getInstance().screen instanceof SceneScreen) {
            // the viewer had nothing to show and kept our screen
            consulting = false;
        }
    }

    /** Back from the viewer: work on only while the player still stands at the same block and may work it. */
    static void consulted() {
        if (!consulting) {
            return;
        }
        consulting = false;
        if (phase == Phase.ACTIVE && !stillAt()) {
            leave();
        }
    }

    private static boolean stillAt() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || station == null || !player.isAlive()) {
            return false;
        }
        return StationKind.of(mc.level.getBlockState(station.pos)) == station.kind()
                && player.position().distanceTo(Vec3.atCenterOf(station.pos)) < 3.5;
    }

    public static boolean clientOnly() { return clientOnly && active(); }

    /** 0..1 through the intro: up to {@link #WALK_SHARE} while walking, then the lean; 1 once working. */
    public static double introProgress() {
        if (phase != Phase.INTRO) {
            return phase == Phase.NONE ? 0 : 1;
        }
        if (arrivedAt < 0) {
            return WALK_SHARE * Math.min(0.98, time / Math.max(0.2, walkEstimate));
        }
        return walkShare + (1 - walkShare) * Anim.clamp01((time - arrivedAt) / leanLength);
    }

    /** Whether the player has got to where they stand (always, once working). */
    public static boolean arrived() {
        return phase != Phase.INTRO || arrivedAt >= 0;
    }

    /** Seconds until the intro reaches progress {@code u}; 0 when it is past it or the player is still walking. */
    public static double introSecondsUntil(double u) {
        if (phase != Phase.INTRO || arrivedAt < 0) {
            return 0;
        }
        return Math.max(0, arrivedAt + (u - walkShare) / (1 - walkShare) * leanLength - time) / Settings.approachSpeed();
    }

    /** The station of the current scene if it is of the given type. */
    @Nullable
    public static <T extends ClientStation> T station(Class<T> type) {
        return active() && type.isInstance(station) ? type.cast(station) : null;
    }

    // ---- lifecycle ----------------------------------------------------------------------------------

    public static void begin(BlockPos pos, StationKind kind) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (phase == Phase.OUTRO && player != null) {
            // used again while still stepping away: it begins as soon as the step away is over
            pendingPos = pos.immutable();
            pendingKind = kind;
            pendingAt = clock;
            return;
        }
        if (phase != Phase.NONE || player == null || mc.level == null) {
            return;
        }
        if (airborne(player)) {
            // used mid-jump: the way in is planned from the ground under the feet, so it begins on landing
            pendingPos = pos.immutable();
            pendingKind = kind;
            pendingAt = clock;
            return;
        }
        BlockState currentState = mc.level.getBlockState(pos);
        var addonDefinition = kind == StationKind.ADDON ? dev.dyslexic.cinematic.api.AddonStations.forBlock(currentState) : null;
        if (!Settings.scene(kind) || OWN_WINDOW.contains(kind)
                || addonDefinition != null && OWN_ADDON_WINDOWS.contains(addonDefinition.id())) {
            // the player turned this block's scene off, or a mod gave the block a menu of its own: it keeps its usual
            // window (a campfire has none)
            if (kind != StationKind.CAMPFIRE) {
                openWindow(pos, kind);
            }
            return;
        }
        if (kind == StationKind.ADDON && !AddonScenes.available(currentState)) {
            openWindow(pos, kind);
            return;
        }
        if (kind == StationKind.JUKEBOX && !JukeboxStation.worthIt(player, pos)) {
            // an empty jukebox and no record to put in it: nothing happens, as in vanilla
            return;
        }
        Choice choice = choose(pos, kind, player);
        refused = choice.plan().why();
        if (!choice.plan().scene()) {
            // the work is out of reach from here, or there is no room for the body: the block's own window instead,
            // or with the height logic off, just a word on why
            Cinematic.LOGGER.info("[Cinematic] {} at {}: no scene ({})", kind, pos.toShortString(), choice.plan().why());
            refuse(choice.station(), choice.plan().reason(), choice.plan().blocker());
            return;
        }
        station = choice.station();
        clientOnly = false;
        transition = false;
        transitionFrom = null;
        // back at the station just stepped away from: the quick way in
        quick = Settings.quickReturn() && pos.equals(leftPos) && clock - leftAt <= Settings.quickWindow();
        leanLength = quick ? QUICK_LEAN_LENGTH : LEAN_LENGTH;
        savedCamera = mc.options.getCameraType();
        if (!savedCamera.isFirstPerson()) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        CameraRig.reset();
        Hands.begin();
        startIntro(choice.plan(), player);
        sound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.35f, 1.1f);
    }

    /** Start only after the vanilla server has opened the menu. The body stays exactly where it is. */
    public static boolean beginClientOnly(BlockPos pos, StationKind kind) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (phase != Phase.NONE || player == null || mc.level == null || mc.gameMode == null) return false;
        ClientStation candidate = create(kind, pos, mc.level.getBlockState(pos), player,
                ClientStation.freeSide(mc.level, pos, player, kind));
        Reach.Plan here = Reach.planHere(candidate, player);
        if (!here.scene()) {
            refused = here.why();
            Cinematic.LOGGER.info("[Cinematic] client-only {} at {}: vanilla window ({})", kind, pos.toShortString(), here.why());
            return false;
        }
        station = candidate;
        clientOnly = true;
        refused = "";
        clientMenuId = player.containerMenu.containerId;
        transition = false;
        transitionFrom = null;
        quick = false;
        leanLength = LEAN_LENGTH;
        savedCamera = mc.options.getCameraType();
        if (!savedCamera.isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
        CameraRig.reset();
        Hands.begin();
        startIntro(here, player);
        sound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.35f, 1.1f);
        return true;
    }

    /** A block's scene and the way into it: the nearest side, else another side, else standing beside one. */
    private record Choice(ClientStation station, Reach.Plan plan) {}

    private static Choice choose(BlockPos pos, StationKind kind, LocalPlayer player) {
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        ClientStation first = create(kind, pos, state, player, ClientStation.freeSide(level, pos, player, kind));
        Reach.Plan plan = Reach.plan(first, player);
        if (plan.scene() || kind == StationKind.CAMPFIRE) {
            return new Choice(first, plan);
        }
        // the nearest side will not do (lava before it, say): another side the block is worked from, nearest first, and
        // failing that, standing beside one of them (the refusal said is still the nearest side's)
        List<Direction> sides = ClientStation.sidesByNearness(level, pos, player, kind);
        for (boolean beside : new boolean[]{false, true}) {
            for (Direction side : sides) {
                if (!beside && side == first.sideDirection()) {
                    continue;
                }
                ClientStation other = create(kind, pos, state, player, side);
                if (beside && !other.worksAtAnAngle()) {
                    return new Choice(first, plan);
                }
                Reach.Plan otherPlan = beside ? Reach.planBeside(other, player) : Reach.plan(other, player);
                if (otherPlan.scene()) {
                    return new Choice(other, otherPlan);
                }
            }
        }
        return new Choice(first, plan);
    }

    /** The way in to {@link #station} begins: the walk, then the lean (a new scene, or moving over from another). */
    private static void startIntro(Reach.Plan plan, LocalPlayer player) {
        path.clear();
        path.addAll(plan.path());
        waypoint = 0;
        double distance = 0;
        Vec3 at = player.position();
        for (Vec3 point : path) {
            distance += Math.hypot(point.x - at.x, point.z - at.z);
            at = point;
        }
        walkEstimate = path.isEmpty() ? 0 : 0.25 + distance / 3.6;
        introLength = walkEstimate + leanLength;
        arrivedAt = path.isEmpty() ? 0 : -1;
        walkShare = path.isEmpty() ? 0 : 1;
        bestDistance = Double.MAX_VALUE;
        progressAt = 0;
        crouch = !clientOnly && station.crouchesFor(station.view().pos().y - plan.feet().y);
        phase = Phase.INTRO;
        time = 0;
        leaveSent = false;
        outroFrom = null;
        neighbor = null;
        lastHurtTime = player.hurtTime;
        if (!clientOnly) player.setSprinting(false);
        if (!clientOnly) {
            PacketDistributor.sendToServer(new StationActionPayload(station.pos, station.kind(), StationActionPayload.Action.BEGIN, 0, 0, false,
                    station.sideDirection().get2DDataValue()));
        }
    }

    // ---- moving over to a neighbouring station ---------------------------------------------------------

    /** A station next to the one worked at, under the cursor, that a right click moves over to; null when none. */
    @Nullable
    public static BlockPos neighbor;
    @Nullable
    public static StationKind neighborKind;
    /** The scene began by moving over from another station: no bars, the drawer stays, camera and hands carry on. */
    static boolean transition;
    /** Where the camera was when moving over began. */
    @Nullable
    static CameraRig.Pose transitionFrom;
    /** How near (blocks from the eyes) a neighbouring station must be to be moved over to. */
    private static final double NEIGHBOR_REACH = 4.5;

    /** While working: the station under the cursor, if the cursor is off the work and on another station's block. */
    private static void findNeighbor() {
        neighbor = null;
        neighborKind = null;
        if (clientOnly) return;
        Minecraft mc = Minecraft.getInstance();
        if (station.aim != null || overPanel || station.busy() || mc.level == null || mc.player == null) {
            return;
        }
        Vec3[] ray = Picking.cursorRay();
        if (ray == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult hit = mc.level.clip(new ClipContext(ray[0], ray[0].add(ray[1].scale(NEIGHBOR_REACH + 2)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(station.pos)
                || hit.getBlockPos().getCenter().distanceTo(eye) > NEIGHBOR_REACH) {
            return;
        }
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        StationKind kind = StationKind.of(state);
        if (kind == null || !kind.startsOnUse() || !Settings.scene(kind) || !kind.usable(mc.level, hit.getBlockPos(), state)
                || kind == StationKind.ADDON && !AddonScenes.available(state)) {
            return;
        }
        neighbor = hit.getBlockPos().immutable();
        neighborKind = kind;
    }

    /**
     * Moves over to the neighbouring station under the cursor without stepping away: the hands let go of this one and
     * the camera turns to the other, walking over if it is not within reach from here. The item in hand goes back to
     * the inventory, as when stepping away (each station keeps what lies on or in it). When the other cannot be worked
     * from anywhere near, a notice says why and the work here goes on.
     */
    public static void moveOver() {
        if (clientOnly) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (phase != Phase.ACTIVE || neighbor == null || neighborKind == null || player == null || station.busy()) {
            return;
        }
        Choice choice = choose(neighbor, neighborKind, player);
        if (!choice.plan().scene()) {
            Cinematic.LOGGER.info("[Cinematic] {} at {}: cannot move over ({})", neighborKind, neighbor.toShortString(), choice.plan().why());
            if (choice.plan().reason() != null) {
                ReachNotice.show(choice.plan().reason(), choice.plan().blocker());
            }
            return;
        }
        ClientStation old = station;
        transitionFrom = CameraRig.last();
        old.clearCursor();
        old.onEnd();
        // the server puts the item in hand back into the inventory on LEAVE; it leaves the hand at once
        player.inventoryMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);
        PacketDistributor.sendToServer(StationActionPayload.of(old.pos, old.kind(), StationActionPayload.Action.LEAVE));
        Hands.beginTransition();
        station = choice.station();
        transition = true;
        quick = false;
        leanLength = LEAN_LENGTH;
        refused = "";
        startIntro(choice.plan(), player);
        sound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.22f, 1.25f);
    }

    /** 0..1: how far the camera has let go of the old station towards the head, when moving over takes a walk. */
    static double released() {
        return transition && walkShare > 0 ? Anim.smoother(phase == Phase.INTRO && arrivedAt < 0 ? time / 0.45 : 1) : 0;
    }

    /** The scene of a block, worked from {@code side} (a furnace always from its front, a campfire from the player's side). */
    private static ClientStation create(StationKind kind, BlockPos pos, BlockState state, LocalPlayer player, Direction side) {
        return switch (kind) {
            case TABLE -> new TableStation(pos, side);
            case FURNACE, SMOKER, BLAST_FURNACE -> new FurnaceStation(pos, state.getValue(AbstractFurnaceBlock.FACING), kind);
            case ENCHANTING -> new EnchantStation(pos, side);
            case ANVIL -> new AnvilStation(pos, side);
            case GRINDSTONE -> new GrindstoneStation(pos, side, state);
            case SMITHING -> new SmithingStation(pos, side);
            case STONECUTTER -> new StonecutterStation(pos, side);
            case LOOM -> new LoomStation(pos, side);
            case CARTOGRAPHY -> new CartographyStation(pos, side);
            case BREWING -> new BrewingStation(pos, side);
            case CRAFTER -> new CrafterStation(pos, side, state);
            case CAMPFIRE -> new CampfireStation(pos, player);
            case LECTERN -> new LecternStation(pos, side);
            case BEACON -> new BeaconStation(pos, side);
            case JUKEBOX -> new JukeboxStation(pos, side);
            case CHISELED_BOOKSHELF -> new ShelfStation(pos, side);
            case ADDON -> AddonScenes.create(pos, state, player, side);
        };
    }

    /** Steps away from the station with the closing shot. */
    public static void leave() {
        if (phase != Phase.INTRO && phase != Phase.ACTIVE) {
            return;
        }
        // the quick way back counts from here
        leftPos = station.pos;
        leftAt = clock;
        neighbor = null;
        outroFrom = CameraRig.last() != null ? CameraRig.last() : station.view();
        outroWeight = CameraRig.weight();
        outroSeconds = 0;
        LocalPlayer player = Minecraft.getInstance().player;
        stepBack = !clientOnly && player != null && player.onGround()
                && Reach.walkable(player.level(), player, List.of(player.position().add(Vec3.directionFromRotation(0, player.getYRot()).scale(-STEP_BACK))));
        Hands.beginOutro();
        phase = Phase.OUTRO;
        time = 0;
        station.clearCursor();
        station.onEnd();
        sendLeave();
        sound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.3f, 0.9f);
    }

    /** The server ended the session: walk away gracefully. */
    public static void serverClosed() {
        leaveSent = true;
        leave();
    }

    /**
     * Kinds of block whose menu, on this connection, is not the one the scene plays (a mod put its own in place): they
     * keep their own window from then on, without the scene starting first.
     */
    private static final java.util.Set<StationKind> OWN_WINDOW = java.util.EnumSet.noneOf(StationKind.class);
    private static final java.util.Set<net.minecraft.resources.ResourceLocation> OWN_ADDON_WINDOWS = new java.util.HashSet<>();

    /**
     * The server answered with a menu the scene cannot stand in for: its window shows instead and the scene ends at
     * once. The server started no session for it (it checks the menu too), so nothing is sent back.
     */
    static void yieldToWindow() {
        if (phase == Phase.NONE || station == null) {
            return;
        }
        if (station.kind() == StationKind.ADDON && Minecraft.getInstance().level != null) {
            var definition = dev.dyslexic.cinematic.api.AddonStations.forBlock(
                    Minecraft.getInstance().level.getBlockState(station.pos));
            if (definition != null) OWN_ADDON_WINDOWS.add(definition.id());
        } else {
            OWN_WINDOW.add(station.kind());
        }
        Cinematic.LOGGER.info("[Cinematic] {} opened a menu of its own ({}): its window instead of the scene", station.kind(),
                Minecraft.getInstance().player == null ? "-" : Minecraft.getInstance().player.containerMenu.getClass().getName());
        leaveSent = true;
        finish(false);
    }

    /** A new connection: every block plays its scene again until it shows otherwise. */
    public static void forgetOwnWindows() {
        OWN_WINDOW.clear();
        OWN_ADDON_WINDOWS.clear();
    }

    /** Ends at once, without the closing shot. */
    public static void abort() {
        if (phase == Phase.NONE) {
            return;
        }
        sendLeave();
        finish(true);
    }

    /** The player could not get to where they stand (something in the way): the block's own window instead. */
    private static void fallBack() {
        ClientStation refusedAt = station;
        leaveSent = true;
        finish(true);
        if (refusedAt != null && Minecraft.getInstance().getConnection() != null) {
            refuse(refusedAt, Reach.Reason.BLOCKED, null);
        }
    }

    /**
     * No scene at this station: the block's own window opens, as vanilla would open it, or with the height logic off
     * (and always at a campfire, which has no window) a notice says why.
     */
    private static void refuse(ClientStation at, @Nullable Reach.Reason reason, @Nullable net.minecraft.network.chat.Component blocker) {
        boolean window = at.kind() != StationKind.CAMPFIRE;
        if (window && Settings.heightLogic()) {
            openWindow(at.pos, at.kind());
        } else if (reason != null) {
            ReachNotice.show(reason, blocker);
        }
    }

    /**
     * The block's own window instead of the scene: the hand reaches out to the block as vanilla's use swings it (the use
     * itself was taken from vanilla to start the scene, and with it the swing).
     */
    private static void openWindow(BlockPos pos, StationKind kind) {
        PacketDistributor.sendToServer(window(pos, kind));
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /** Asks for the block's own window (a chiseled bookshelf has none: the book used is taken or put in, as in vanilla). */
    private static StationActionPayload window(BlockPos pos, StationKind kind) {
        int slot = kind == StationKind.CHISELED_BOOKSHELF ? ShelfStation.slotAt(pos, Minecraft.getInstance().level.getBlockState(pos)
                .getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING), dev.dyslexic.cinematic.ClientBridge.useAt) : 0;
        return new StationActionPayload(pos, kind, StationActionPayload.Action.WINDOW, slot, 0, false, 0);
    }

    /** Forgets the scene without touching screens: another screen is replacing ours, or the world is closing. */
    public static void drop() {
        if (phase == Phase.NONE) {
            return;
        }
        sendLeave();
        finish(false);
    }

    private static void finish(boolean closeScreen) {
        Minecraft mc = Minecraft.getInstance();
        FreeLook.reset();
        transition = false;
        transitionFrom = null;
        neighbor = null;
        consulting = false;
        if (phase != Phase.OUTRO && station != null) {
            station.onEnd();
        }
        phase = Phase.NONE;
        if (closeScreen && mc.screen instanceof SceneScreen) {
            mc.setScreen(null);
        }
        if (savedCamera != null && !savedCamera.isFirstPerson()) {
            mc.options.setCameraType(savedCamera);
        }
        savedCamera = null;
        station = null;
        clientOnly = false;
        clientMenuId = -1;
        outroFrom = null;
        overPanel = false;
        path.clear();
        CameraRig.reset();
        BlockPos next = pendingPos;
        StationKind nextKind = pendingKind;
        pendingPos = null;
        pendingKind = null;
        if (next != null && nextKind != null && clock - pendingAt < 2 && mc.level != null && nextKind.matches(mc.level.getBlockState(next))) {
            begin(next, nextKind);
        }
    }

    private static void sendLeave() {
        if (clientOnly) {
            if (!leaveSent) {
                leaveSent = true;
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null && player.containerMenu != player.inventoryMenu
                        && player.containerMenu.containerId == clientMenuId) player.closeContainer();
            }
            return;
        }
        if (!leaveSent && station != null && Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(StationActionPayload.of(station.pos, station.kind(), StationActionPayload.Action.LEAVE));
        }
        leaveSent = true;
    }

    private static void enterActive() {
        Minecraft mc = Minecraft.getInstance();
        boolean carriedOn = transition;
        transition = false;
        transitionFrom = null;
        phase = Phase.ACTIVE;
        time = 0;
        Hands.enterActive();
        mc.setScreen(new SceneScreen());
        // releasing the mouse recentres the cursor; measure movement from there (moving over, the cursor never left)
        cursorEngaged = carriedOn;
        mouseStartX = mc.mouseHandler.xpos();
        mouseStartY = mc.mouseHandler.ypos();
    }

    // ---- per frame / per tick -----------------------------------------------------------------------

    /** Start of every frame: advance the clocks and the phases. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (mc.isPaused()) {
            dt = 0;
        }
        frameDt = dt;
        clock += dt;
        TableLayouts.frame(dt);
        FurnaceLayouts.frame(dt);
        SurfaceLayouts.frame(dt);
        SpatialLayouts.frame(dt);
        if (phase == Phase.NONE) {
            return;
        }
        if (station != null) {
            station.beforeFrame();
        }
        FreeLook.frame(dt);
        // the lean in once arrived and the stepping away run at the pace the player set (the walk itself does not:
        // getting stuck is told by how long it takes)
        double pace = phase == Phase.INTRO && arrivedAt >= 0 ? Settings.approachSpeed() : phase == Phase.OUTRO ? Settings.leaveSpeed() : 1;
        time += dt * pace;
        if (phase == Phase.OUTRO) {
            outroSeconds += dt;
        }
        if (phase == Phase.INTRO && arrivedAt >= 0 && time - arrivedAt >= leanLength) {
            enterActive();
        } else if (phase == Phase.OUTRO && time >= OUTRO_LENGTH) {
            finish(true);
        } else if (phase == Phase.OUTRO && time >= UI_FADE_OUT && mc.screen instanceof SceneScreen) {
            mc.setScreen(null);
        }
    }

    public static void tick() {
        if (phase == Phase.NONE) {
            beginOnLanding();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || station == null) {
            finish(false);
            return;
        }
        if (!player.isAlive()) {
            abort();
            return;
        }
        if (consulting && mc.screen == null) {
            // the viewer closed without handing our screen back: return to the work, or step away if it is gone
            consulting = false;
            if (phase == Phase.ACTIVE && stillAt()) {
                mc.setScreen(new SceneScreen());
            } else {
                leave();
            }
        }
        if (clientOnly && phase != Phase.OUTRO && (player.containerMenu == player.inventoryMenu
                || player.containerMenu.containerId != clientMenuId)) {
            drop();
            return;
        }
        if (phase != Phase.OUTRO) {
            boolean gone = !station.stillThere(mc.level) || player.distanceToSqr(station.pos.getCenter()) > 49;
            // the ground gave way, or something knocked the player off: hand control back
            boolean falling = !player.onGround() && !player.isInWater() && !player.onClimbable() && player.fallDistance > 0.7;
            if (gone || falling || player.hurtTime > lastHurtTime) {
                leave();
            }
        }
        lastHurtTime = player.hurtTime;
        player.setSprinting(false);
        if (!mc.options.getCameraType().isFirstPerson() && !FreeLook.showsBody()) {
            // the scene is filmed from the eyes; F5 would put the camera inside our own head (looking round from behind
            // is the one exception: the player's body is drawn then)
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        if (phase == Phase.ACTIVE) {
            station.tick();
        }
    }

    /** In the middle of a jump or a short drop (flying, gliding, swimming and climbing are left as they are). */
    private static boolean airborne(LocalPlayer player) {
        return !player.onGround() && !player.getAbilities().flying && !player.isFallFlying() && !player.isInWater()
                && !player.onClimbable() && !player.isPassenger();
    }

    /** A station used mid-jump begins once the feet are back on the ground (not after a fall of more than two seconds). */
    private static void beginOnLanding() {
        if (pendingPos == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || clock - pendingAt >= 2) {
            pendingPos = null;
            pendingKind = null;
            return;
        }
        if (airborne(player)) {
            return;
        }
        BlockPos next = pendingPos;
        StationKind nextKind = pendingKind;
        pendingPos = null;
        pendingKind = null;
        if (nextKind != null && nextKind.matches(mc.level.getBlockState(next))) {
            begin(next, nextKind);
        }
    }

    /** Keeps the body turned to the station so other players see the pose (end of the player's tick). */
    public static void steer(LocalPlayer player) {
        if (!active() || clientOnly) {
            return;
        }
        float yaw = station.sideDirection().getOpposite().toYRot() + (float) station.swing;
        float pitch = switch (phase) {
            case INTRO -> station.view().pitch();
            case ACTIVE -> station.bodyPitch();
            default -> 24f;
        };
        float rate = phase == Phase.INTRO ? 0.25f : 0.22f;
        float newYaw = player.getYRot() + Mth.wrapDegrees(yaw - player.getYRot()) * rate;
        float newPitch = player.getXRot() + (pitch - player.getXRot()) * rate;
        player.setYRot(newYaw);
        player.setXRot(newPitch);
        player.setYHeadRot(newYaw);
    }

    /** Walks the player up to the station on the way in (crouching where needed), and a step back on the way out. */
    public static void movementInput(Input input) {
        if (!active()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        input.up = input.down = input.left = input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        if (player == null) {
            return;
        }
        boolean walked = waypoint >= path.size();
        if (phase == Phase.INTRO && !walked) {
            Vec3 target = path.get(waypoint);
            double dx = target.x - player.getX();
            double dz = target.z - player.getZ();
            double distance = Math.hypot(dx, dz);
            boolean last = waypoint == path.size() - 1;
            if (last && distance < 0.3 && arrivedAt < 0) {
                // close enough: the camera starts leaning in while the last step settles
                arrivedAt = time;
                walkShare = WALK_SHARE * Math.min(0.98, time / Math.max(0.2, walkEstimate));
                introLength = arrivedAt + leanLength;
            }
            if (distance < bestDistance - 0.02) {
                bestDistance = distance;
                progressAt = time;
            }
            if (arrivedAt < 0 && (time - progressAt > 0.7 || time > walkEstimate * 2 + 1.5)) {
                // walked into something and not getting any closer
                refused = "walked into something on the way";
                Cinematic.LOGGER.info("[Cinematic] {} at {}: {}, the block's own window instead", station.kind(), station.pos.toShortString(), refused);
                fallBack();
                return;
            }
            if (distance < (last ? 0.05 : 0.3)) {
                waypoint++;
                bestDistance = Double.MAX_VALUE;
                progressAt = time;
            } else {
                double yaw = Math.toRadians(player.getYRot());
                double sin = Math.sin(yaw), cos = Math.cos(yaw);
                double forward = -dx * sin + dz * cos;
                double left = dx * cos + dz * sin;
                double strength = (last ? Math.min(1.0, distance * 2.4) : 1.0) / Math.max(distance, 1e-4);
                input.forwardImpulse = (float) (forward * strength);
                input.leftImpulse = (float) (left * strength);
            }
        }
        if (crouch) {
            boolean settling = phase == Phase.INTRO && arrivedAt >= 0;
            input.shiftKeyDown = settling || phase == Phase.ACTIVE || (phase == Phase.OUTRO && time < 0.15);
        }
        if (phase == Phase.OUTRO && stepBack && outroSeconds > 0.2 && outroSeconds < 0.6) {
            input.forwardImpulse = -0.45f;
        }
    }

    // ---- interaction ------------------------------------------------------------------------------

    /** Works out what the cursor points at, with this frame's matrices. */
    public static void updateCursor() {
        if (!active()) {
            return;
        }
        if (phase != Phase.ACTIVE || FreeLook.looking()) {
            // looking round, the mouse turns the camera: nothing is pointed at
            station.clearCursor();
            neighbor = null;
            return;
        }
        if (!cursorEngaged) {
            Minecraft mc = Minecraft.getInstance();
            double moved = Math.abs(mc.mouseHandler.xpos() - mouseStartX) + Math.abs(mc.mouseHandler.ypos() - mouseStartY);
            if (moved < 4) {
                station.clearCursor();
                return;
            }
            cursorEngaged = true;
        }
        station.updateCursor(overPanel);
        findNeighbor();
    }

    static void sound(SoundEvent event, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && station != null) {
            Vec3 at = station.soundPoint();
            mc.level.playLocalSound(at.x, at.y, at.z, event, SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    /** Horizontal unit vector for a camera yaw. */
    static Vec3 yawForward(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }
}
