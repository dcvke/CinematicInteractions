package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.furnace.FurnaceLayout;
import dev.dyslexic.cinematic.furnace.FurnaceZones;
import dev.dyslexic.cinematic.station.StationKind;
import dev.dyslexic.cinematic.table.TableGrid;
import dev.dyslexic.cinematic.table.TableStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Development-only scripted walkthrough, enabled with -Dcinematic.showcase=true: builds a little workshop in a
 * flat world and goes through every station with simulated cursor moves (craft, smelt, smoke, blast, enchant,
 * forge, grind, smith, cut), saving screenshots along the way and logging what ended up where. Inert in normal
 * play.
 */
public final class Showcase {
    private Showcase() {}

    public static final boolean ENABLED = Boolean.getBoolean("cinematic.showcase");
    /**
     * Which act to start from, to film one station quickly: 0 table, 3 furnace, 4 smoker, 5 blast furnace,
     * 6 enchanting, a anvil, g grindstone, s smithing table, c stonecutter, l loom, m cartography table, b brewing stand,
     * r crafter, f campfire, u soul campfire, k lectern, n beacon, h heights, o settings, t moving over between stations,
     * y laying out a recipe, v looking round from behind, j jukebox, q the quick way back, e chiseled bookshelf,
     * w what others see (another player, a stand-in, forges, grinds, cuts and reads).
     */
    private static final String FROM = System.getProperty("cinematic.showcase.from", "0");
    /** Just the act started from: the walkthrough ends where it would hand over to the next act. */
    private static final boolean ONLY = Boolean.getBoolean("cinematic.showcase.only");
    private static final boolean EDITOR_BATCH = Boolean.getBoolean("cinematic.showcase.editorBatch");
    private static final String WORLD = System.getProperty("cinematic.showcase.world", "cinematic-showcase");
    private static final BlockPos TABLE = new BlockPos(0, -60, 2);
    private static final BlockPos FURNACE = TABLE.west();
    private static final BlockPos SMOKER = new BlockPos(2, -60, 2);
    private static final BlockPos BLAST = new BlockPos(4, -60, 2);
    private static final BlockPos ENCHANT = new BlockPos(-5, -60, 8);
    // the smithy: a row along a back wall, each worked from the south
    private static final BlockPos ANVIL = new BlockPos(8, -60, 5);
    private static final BlockPos GRINDSTONE = new BlockPos(10, -60, 5);
    private static final BlockPos SMITHING = new BlockPos(12, -60, 5);
    private static final BlockPos STONECUTTER = new BlockPos(14, -60, 5);
    // the second workshop, along another wall, and a campfire out in the open
    private static final BlockPos LOOM = new BlockPos(8, -60, 11);
    private static final BlockPos CARTOGRAPHY = new BlockPos(10, -60, 11);
    private static final BlockPos BREWING = new BlockPos(12, -60, 11);
    private static final BlockPos CRAFTER = new BlockPos(14, -60, 11);
    private static final BlockPos CAMPFIRE = new BlockPos(11, -60, 17);
    private static final BlockPos SOUL_CAMPFIRE = new BlockPos(15, -60, 17);
    private static final BlockPos LECTERN = new BlockPos(6, -60, 21);
    /** On top of a full pyramid: iron, gold, emerald and diamond, nine to three wide. */
    private static final BlockPos BEACON = new BlockPos(22, -56, 22);
    /** A chiseled bookshelf in the reading corner, worked from the south, another one on top of it. */
    private static final BlockPos SHELF = new BlockPos(3, -60, 21);
    /** Where on the shelf's south face each slot's book is (x from the west, y up). */
    private static final Vec3[] SHELF_SLOTS = {new Vec3(0.19, 0.72, 1), new Vec3(0.5, 0.75, 1), new Vec3(0.81, 0.75, 1), new Vec3(0.19, 0.25, 1),
            new Vec3(0.5, 0.22, 1), new Vec3(0.81, 0.25, 1)};
    /** A jukebox out by the campfires, worked from the west. */
    private static final BlockPos JUKEBOX = new BlockPos(19, -60, 17);
    /** The height cases stand in a row along z = 22, four blocks apart, going west from here. */
    private static final int HEIGHTS_X = -10;


    private record Step(int delay, Runnable action) {}

    private static boolean requested;
    private static int t = -1;
    private static int wait;
    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static final double[] INTRO_SHOTS = {0.15, 0.35, 0.55, 0.75, 0.92};
    private static final double[] OUTRO_SHOTS = {0.1, 0.25, 0.45, 0.65, 0.85};
    private static int introShot, outroShot;
    /** Frames of a jukebox move, by its progress. */
    private static final double[] MOVE_SHOTS = {0.15, 0.35, 0.55, 0.75, 0.95};
    private static int moveShot;
    /** Screenshot name prefix for the scene being filmed (the act, see {@link #FROM}). */
    private static String act = "0";
    private static boolean waitingForActive;
    private static int waitingTicks;
    private static boolean done;

    public static void onScreen(Screen screen) {
        if (!ENABLED || requested || !(screen instanceof TitleScreen)) {
            return;
        }
        requested = true;
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.execute(() -> {
            try {
                if (mc.getLevelSource().levelExists(WORLD)) {
                    mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.gui.setScreen(new TitleScreen()));
                    return;
                }
            } catch (Exception e) {
                Cinematic.LOGGER.warn("[Showcase] could not reopen world", e);
            }
            //? if >=26.1 {
            LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
            //?} else if <26.1 {
            /*LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS),
            *///?}
                    WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261010L, false, false),
                    registry -> registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                            .createWorldDimensions(), screen);
        });
    }

    public static void tick() {
        if (!ENABLED || done) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // A clean Fabric development directory first opens the accessibility wizard.
        if (!requested && mc.gui.screen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
            mc.options.onboardAccessibility = false;
            mc.options.save();
            mc.gui.setScreen(new TitleScreen());
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            return;
        }
        if (mc.gui.screen() != null && t < 0) {
            return;
        }
        t++;
        if (t == 1) {
            setup();
        } else if (t == 70) {
            shot("00_before");
        } else if (t == 75) {
            switch (FROM) {
                case "3" -> nextAct("3", new Vec3(-3.2, -60, 4.6), 200f, FURNACE, new Vec3(0.5, 0.6, 1.0), Direction.SOUTH);
                case "4" -> nextAct("4", new Vec3(2.5, -60, 5.4), 180f, SMOKER, new Vec3(0.5, 0.6, 1.0), Direction.SOUTH);
                case "5" -> nextAct("5", new Vec3(4.5, -60, 5.4), 180f, BLAST, new Vec3(0.5, 0.6, 1.0), Direction.SOUTH);
                case "i" -> {
                    step(2, () -> server(p -> p.level().setBlockAndUpdate(CRAFTER, Blocks.CRAFTER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ORIENTATION, net.minecraft.core.FrontAndTop.UP_SOUTH))));
                    nextAct("i", new Vec3(14.5, -60, 14.4), 180f, CRAFTER, new Vec3(0.5, 0.5, 1), Direction.SOUTH);
                }
                case "6" -> nextAct("6", new Vec3(-4.5, -60, 11.6), 180f, ENCHANT, new Vec3(0.5, 0.75, 0.6), Direction.UP);
                case "a" -> smithyAct("a", ANVIL);
                case "g" -> smithyAct("g", GRINDSTONE);
                case "s" -> smithyAct("s", SMITHING);
                case "c" -> smithyAct("c", STONECUTTER);
                case "l" -> {
                    restock();
                    smithyAct("l", LOOM);
                }
                case "m" -> {
                    restock();
                    smithyAct("m", CARTOGRAPHY);
                }
                case "b" -> {
                    restock();
                    smithyAct("b", BREWING);
                }
                case "r" -> {
                    restock();
                    smithyAct("r", CRAFTER);
                }
                case "f" -> {
                    restock();
                    campfireAct();
                }
                case "u" -> {
                    restock();
                    soulCampfireAct();
                }
                case "k" -> {
                    restock();
                    lecternAct();
                }
                case "n" -> {
                    restock();
                    beaconAct();
                }
                case "h" -> heightAct();
                case "o" -> optionsAct();
                case "t" -> transitionAct();
                case "y" -> layoutAct();
                case "v" -> lookAct();
                case "j" -> jukeboxAct();
                case "q" -> quickAct();
                case "e" -> {
                    restock();
                    shelfAct();
                }
                case "w" -> watchAct();
                case "ip" -> nextAct("ip", new Vec3(0.7, -60, 5.6), 172f, TABLE, new Vec3(0.5, 1, 0.8), Direction.UP);
                case "d" -> nextAct("d", new Vec3(0.7, -60, 5.6), 172f, TABLE, new Vec3(0.5, 1, 0.8), Direction.UP);
                case "p" -> nextAct("p", new Vec3(-3.2, -60, 4.6), 200f, FURNACE, new Vec3(0.5, 0.6, 1), Direction.SOUTH);
                case "z" -> { developerOptionsScript(); step(3, () -> finish(Minecraft.getInstance())); }
                default -> use(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP);
            }
        }
        if (Scene.phase == Scene.Phase.INTRO && introShot < INTRO_SHOTS.length && Scene.introProgress() >= INTRO_SHOTS[introShot]) {
            shot(String.format("%s1_intro_%02d", act, Math.round(INTRO_SHOTS[introShot++] * 100)));
        }
        if (Scene.phase == Scene.Phase.OUTRO && outroShot < OUTRO_SHOTS.length && Scene.time / Scene.OUTRO_LENGTH >= OUTRO_SHOTS[outroShot]) {
            shot(String.format("%s8_outro_%02d", act, Math.round(OUTRO_SHOTS[outroShot++] * 100)));
        }
        if (Scene.station instanceof JukeboxStation jukebox && Scene.phase == Scene.Phase.ACTIVE) {
            String[] move = jukebox.moveName().split(" ");
            double progress = Double.parseDouble(move[1].replace(',', '.'));
            if (!move[0].equals("none") && moveShot < MOVE_SHOTS.length && progress >= MOVE_SHOTS[moveShot]) {
                shot(String.format("%s5_%s_%02d", act, move[0], Math.round(MOVE_SHOTS[moveShot++] * 100)));
            }
        }
        if (waitingForActive && Scene.phase == Scene.Phase.NONE && ++waitingTicks > 300) {
            // the scene never started: say so and go on, so one broken act does not stall the rest
            check("scene of act " + act + " started", false, "refused=" + Scene.refused + " screen=" + Minecraft.getInstance().gui.screen());
            closeWindow();
            waitingTicks = 0;
            waitingForActive = false;
            // its script runs anyway (its steps fail one by one and say so) and leads on to the next act
            runScript();
        }
        if (!waitingForActive) {
            waitingTicks = 0;
        }
        if (waitingForActive && Scene.phase == Scene.Phase.ACTIVE && Scene.time > 0.9) {
            waitingForActive = false;
            runScript();
        }
        if (wait > 0) {
            wait--;
        } else if (!STEPS.isEmpty()) {
            Step step = STEPS.poll();
            try {
                step.action().run();
            } catch (RuntimeException e) {
                check("step in act " + act + " ran", false, e.toString());
                Cinematic.LOGGER.warn("[Showcase] a step failed", e);
            }
            wait = STEPS.isEmpty() ? 0 : STEPS.peek().delay();
        }
        if (t > 40000) {
            REPORT.add("timed out");
            finish(mc);
        }
    }

    /** The script of the act whose scene has just begun. */
    private static void runScript() {
        switch (act) {
            case "0" -> tableScript();
            case "d" -> {
                tableGridEditorScript();
                step(30, () -> finish(Minecraft.getInstance()));
            }
            case "p" -> {
                StationKind kind = Scene.station.kind();
                furnaceEditorScript();
                step(3, Scene::leave);
                step(45, () -> {});
                if (kind == StationKind.FURNACE) nextAct("p", new Vec3(2.5, -60, 5.4), 180f, SMOKER, new Vec3(0.5, 0.6, 1), Direction.SOUTH);
                else if (kind == StationKind.SMOKER) nextAct("p", new Vec3(4.5, -60, 5.4), 180f, BLAST, new Vec3(0.5, 0.6, 1), Direction.SOUTH);
                else { developerOptionsScript(); step(3, () -> finish(Minecraft.getInstance())); }
            }
            case "3" -> furnaceScript();
            case "4" -> smokerScript();
            case "5" -> blastScript();
            case "6" -> enchantScript();
            case "ip" -> inventoryEffectsScript();
            case "a" -> anvilScript();
            case "g" -> grindstoneScript();
            case "s" -> smithingScript();
            case "c" -> stonecutterScript();
            case "l" -> loomScript();
            case "m" -> cartographyScript();
            case "b" -> brewingScript();
            case "r", "i" -> crafterScript();
            case "u" -> soulCampfireScript();
            case "k" -> lecternScript();
            case "e" -> shelfScript();
            case "n" -> beaconScript();
            default -> campfireScript();
        }
    }

    private static void setup() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        //? if >=26.2 {
        if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        //?} else if <26.2 {
        /*if (mc.options.hideGui) mc.options.hideGui = false;
        *///?}
        for (String c : new String[]{"gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false",
                "weather clear", "time set 4000", "gamerule show_advancement_messages false"}) {
            cmd(c);
        }
        server(p -> {
            ServerLevel level = p.level();
            for (int x = -4; x <= 6; x++) {
                for (int y = -60; y <= -57; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, 0), y == -57 ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                            : Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
            level.setBlockAndUpdate(FURNACE, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(TABLE.east(), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(SMOKER, Blocks.SMOKER.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(BLAST, Blocks.BLAST_FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(SMOKER.east(), Blocks.SPRUCE_PLANKS.defaultBlockState());
            level.setBlockAndUpdate(FURNACE.west(), Blocks.SPRUCE_LOG.defaultBlockState());
            level.setBlockAndUpdate(FURNACE.west().above(), Blocks.LANTERN.defaultBlockState());
            // a reading corner: the enchanting table with a horseshoe of bookshelves, open to the south
            level.setBlockAndUpdate(ENCHANT, Blocks.ENCHANTING_TABLE.defaultBlockState());
            for (int y = 0; y <= 1; y++) {
                for (int dx = -2; dx <= 2; dx++) {
                    level.setBlockAndUpdate(ENCHANT.offset(dx, y, -2), Blocks.BOOKSHELF.defaultBlockState());
                }
                for (int dz = -1; dz <= 0; dz++) {
                    level.setBlockAndUpdate(ENCHANT.offset(-2, y, dz), Blocks.BOOKSHELF.defaultBlockState());
                    level.setBlockAndUpdate(ENCHANT.offset(2, y, dz), Blocks.BOOKSHELF.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(ENCHANT.offset(-2, 2, -2), Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
            level.setBlockAndUpdate(ENCHANT.offset(2, 2, -2), Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
            // the smithy
            for (int x = 7; x <= 15; x++) {
                for (int y = -60; y <= -58; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, 3), y == -58 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(ANVIL, Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(GRINDSTONE, Blocks.GRINDSTONE.defaultBlockState().setValue(GrindstoneBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(SMITHING, Blocks.SMITHING_TABLE.defaultBlockState());
            level.setBlockAndUpdate(STONECUTTER, Blocks.STONECUTTER.defaultBlockState().setValue(StonecutterBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(new BlockPos(9, -60, 4), Blocks.BLAST_FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(new BlockPos(11, -57, 3), Blocks.LANTERN.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(13, -57, 3), Blocks.LANTERN.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(15, -60, 4), Blocks.STONE.defaultBlockState());
            // the second workshop and the campfire
            for (int x = 7; x <= 15; x++) {
                for (int y = -60; y <= -58; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, 10), y == -58 ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(LOOM, Blocks.LOOM.defaultBlockState().setValue(net.minecraft.world.level.block.LoomBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(CARTOGRAPHY, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
            level.setBlockAndUpdate(BREWING, Blocks.BREWING_STAND.defaultBlockState());
            level.setBlockAndUpdate(CRAFTER, Blocks.CRAFTER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ORIENTATION, net.minecraft.core.FrontAndTop.SOUTH_UP));
            level.setBlockAndUpdate(new BlockPos(11, -57, 10), Blocks.LANTERN.defaultBlockState());
            level.setBlockAndUpdate(CAMPFIRE, Blocks.CAMPFIRE.defaultBlockState());
            level.setBlockAndUpdate(SOUL_CAMPFIRE, Blocks.SOUL_CAMPFIRE.defaultBlockState());
            // a reading corner: a lectern with a book, a shelf and a lantern
            level.setBlockAndUpdate(LECTERN, Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, Direction.SOUTH));
            net.minecraft.world.level.block.LecternBlock.tryPlaceBook(null, level, LECTERN, level.getBlockState(LECTERN), storyBook());
            level.setBlockAndUpdate(LECTERN.west(), Blocks.BOOKSHELF.defaultBlockState());
            level.setBlockAndUpdate(LECTERN.west().above(), Blocks.LANTERN.defaultBlockState());
            level.setBlockAndUpdate(LECTERN.east(), Blocks.BOOKSHELF.defaultBlockState());
            // the beacon on its pyramid, open to the sky
            net.minecraft.world.level.block.Block[] layers = {Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.DIAMOND_BLOCK};
            for (int layer = 0; layer < 4; layer++) {
                int r = 4 - layer;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        level.setBlockAndUpdate(BEACON.offset(dx, -4 + layer, dz), layers[layer].defaultBlockState());
                    }
                }
            }
            level.setBlockAndUpdate(BEACON, Blocks.BEACON.defaultBlockState());
            level.setBlockAndUpdate(JUKEBOX, Blocks.JUKEBOX.defaultBlockState());
            buildHeights(level);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 24));
            p.getInventory().setItem(1, new ItemStack(Items.STICK, 12));
            p.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 40));
            p.getInventory().setItem(3, new ItemStack(Items.REDSTONE, 9));
            p.getInventory().setItem(4, new ItemStack(Items.IRON_INGOT, 5));
            p.getInventory().setItem(5, new ItemStack(Items.COAL, 8));
            p.getInventory().setItem(6, new ItemStack(Items.RAW_IRON, 6));
            p.getInventory().setItem(9, new ItemStack(Items.COAL, 16));
            p.getInventory().setItem(10, new ItemStack(Items.TORCH, 16));
            p.getInventory().setItem(11, new ItemStack(Items.BEEF, 4));
            p.getInventory().setItem(12, new ItemStack(Items.APPLE, 3));
            p.getInventory().setItem(13, new ItemStack(Items.DIAMOND_SWORD));
            p.getInventory().setItem(14, new ItemStack(Items.LAPIS_LAZULI, 12));
            p.getInventory().setItem(15, new ItemStack(Items.RAW_IRON, 4));
            p.getInventory().setItem(16, new ItemStack(Items.COAL, 16));
            ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
            pickaxe.setDamageValue(140);
            p.getInventory().setItem(17, pickaxe);
            var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            p.getInventory().setItem(19, net.minecraft.world.item.enchantment.EnchantmentHelper.createBook(new EnchantmentInstance(enchantments.getOrThrow(Enchantments.EFFICIENCY), 3)));
            ItemStack golden = new ItemStack(Items.GOLDEN_SWORD);
            golden.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3);
            golden.enchant(enchantments.getOrThrow(Enchantments.LOOTING), 2);
            p.getInventory().setItem(20, golden);
            // the chiseled bookshelves: books of every kind, with gaps
            net.minecraft.world.level.block.state.BlockState shelfState = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.SOUTH);
            level.setBlockAndUpdate(SHELF, shelfState);
            level.setBlockAndUpdate(SHELF.above(), shelfState);
            if (level.getBlockEntity(SHELF) instanceof net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity shelf) {
                shelf.setItem(0, net.minecraft.world.item.enchantment.EnchantmentHelper.createBook(new EnchantmentInstance(enchantments.getOrThrow(Enchantments.SHARPNESS), 4)));
                shelf.setItem(1, notesBook());
                shelf.setItem(3, storyBook());
                shelf.setItem(4, new ItemStack(Items.BOOK));
                shelf.setItem(5, net.minecraft.world.item.enchantment.EnchantmentHelper.createBook(new EnchantmentInstance(enchantments.getOrThrow(Enchantments.MENDING), 1)));
            }
            if (level.getBlockEntity(SHELF.above()) instanceof net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity shelf) {
                shelf.setItem(0, new ItemStack(Items.BOOK));
                shelf.setItem(2, new ItemStack(Items.BOOK));
                shelf.setItem(3, new ItemStack(Items.WRITABLE_BOOK));
                shelf.setItem(4, new ItemStack(Items.BOOK));
            }
            p.getInventory().setItem(21, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            p.getInventory().setItem(22, new ItemStack(Items.DIAMOND_CHESTPLATE));
            p.getInventory().setItem(23, new ItemStack(Items.NETHERITE_INGOT));
            p.getInventory().setItem(24, new ItemStack(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE));
            p.getInventory().setItem(25, new ItemStack(Items.AMETHYST_SHARD, 3));
            p.getInventory().setItem(26, new ItemStack(Items.STONE, 24));
            p.getInventory().setItem(27, new ItemStack(Items.COBBLED_DEEPSLATE, 16));
            p.getInventory().setSelectedSlot(0);
            p.giveExperienceLevels(30);
            p.teleportTo(level, 0.7, -60, 5.6, java.util.Set.of(), 172f, 18f, true);
            p.setItemInHand(InteractionHand.MAIN_HAND, p.getInventory().getItem(0));
        });
    }

    private static void use(BlockPos pos, Vec3 local, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = new BlockHitResult(Vec3.atLowerCornerOf(pos).add(local), face, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        REPORT.add("used " + pos.toShortString() + ", phase=" + Scene.phase + " intro=" + String.format("%.2f", Scene.introLength)
                + " station=" + (Scene.station == null ? "-" : Scene.station.kind()));
        introShot = 0;
        outroShot = 0;
        waitingForActive = true;
    }

    /** Moves on to the next station: walk to a spot, then use it. */
    /** Running just one act ({@link #ONLY}): the walkthrough ends where it would go on to another. */
    private static boolean stopHere(String code) {
        if (ONLY && !code.equals(FROM)) {
            step(2, () -> finish(Minecraft.getInstance()));
            return true;
        }
        return false;
    }

    private static void nextAct(String next, Vec3 from, float yaw, BlockPos station, Vec3 local, Direction face) {
        if (ONLY && !next.equals(FROM)) {
            step(2, () -> finish(Minecraft.getInstance()));
            return;
        }
        step(2, () -> {
            act = next;
            server(p -> p.teleportTo(p.level(), from.x, from.y, from.z, java.util.Set.of(), yaw, 20f, true));
        });
        step(30, () -> shot(next + "0_before"));
        step(4, () -> use(station, local, face));
    }

    // ---- the crafting table -------------------------------------------------------------------------

    /** Real mouse clicks through SceneScreen, compared with the optional mods' own live renderer state. */
    private static void inventoryEffectsScript() {
        if (Scene.station instanceof TableStation) {
            float[] angle = new float[1];
            int[] scale = new int[1];
            step(3, InventoryEffectsProbe::defaults);
            step(3, () -> { drawerPoint(3); screenClick(0); });
            step(12, () -> { pointAside(); });
            step(30, () -> {
                check("inventory effects carried stack", Hands.carried().is(Items.REDSTONE) && InventoryEffectsProbe.cursor() == Items.REDSTONE,
                        "actual=" + Hands.carried() + " native=" + InventoryEffectsProbe.cursor());
                check("inventory effects native cursor particles", InventoryEffectsProbe.particles() > 0, "count=" + InventoryEffectsProbe.particles());
                check("inventory effects native physics", !InventoryEffectsProbe.hasInteractions() || InventoryEffectsProbe.physicsItem() == Items.REDSTONE,
                        "installed=" + InventoryEffectsProbe.hasInteractions() + " native=" + InventoryEffectsProbe.physicsItem());
                angle[0] = InventoryEffectsProbe.angle();
                shot("ip1_cursor");
                pointGui(40, 120);
            });
            step(4, () -> pointGui(Minecraft.getInstance().gui.screen().width - 40, 120));
            step(4, () -> {
                check("inventory effects physics follows motion", !InventoryEffectsProbe.hasInteractions() || Float.isFinite(InventoryEffectsProbe.angle())
                        && Math.abs(InventoryEffectsProbe.angle() - angle[0]) > 0.1, "installed=" + InventoryEffectsProbe.hasInteractions() + " angle=" + InventoryEffectsProbe.angle());
                point(1, 1);
                InventoryEffectsProbe.clear();
            });
            step(25, () -> {
                check("inventory effects world hand hides GUI copy", Hands.itemInHand() && InventoryEffectsProbe.cursor() == Items.AIR,
                        "inHand=" + Hands.itemInHand() + " cursor=" + InventoryEffectsProbe.cursor());
                double[] at = Picking.toGui(Hands.heldPoint());
                check("inventory effects follow world hand", at != null && InventoryEffectsProbe.near(at[0], at[1]), "particles=" + InventoryEffectsProbe.particles());
                shot("ip2_hand");
                InventoryEffectsProbe.actionOnly();
                clickCell(1, 1, 1);
            });
            step(7, () -> {
                double[] at = Picking.toGui(table().cellPoint(4));
                check("inventory effects placement uses projected cell", InventoryEffectsProbe.particles() > 0 && at != null && InventoryEffectsProbe.near(at[0], at[1]),
                        "particles=" + InventoryEffectsProbe.particles());
                server(p -> check("inventory effects real table placement", TableStore.get(p.level()).get(TABLE) != null
                        && TableStore.get(p.level()).get(TABLE).items.get(4).is(Items.REDSTONE), "server table"));
                shot("ip3_placed");
                drawerPoint(3); screenClick(0);
            });
            step(8, () -> { InventoryEffectsProbe.clear(); point(1, 1); });
            step(5, () -> clickCell(1, 1, 0));
            step(7, () -> {
                check("inventory effects taking from world cell", Hands.carried().is(Items.REDSTONE) && Hands.carried().getCount() == 1 && InventoryEffectsProbe.particles() > 0,
                        "carried=" + Hands.carried() + " particles=" + InventoryEffectsProbe.particles());
                drawerPoint(3); screenClick(0);
            });
            step(10, InventoryEffectsProbe::disable);
            step(2, () -> { drawerPoint(3); screenClick(0); pointAside(); });
            step(18, () -> {
                check("inventory effects respect particle toggles", InventoryEffectsProbe.particles() == 0 && Hands.carried().is(Items.REDSTONE), "no particles, normal transfer");
                scale[0] = Minecraft.getInstance().options.guiScale().get();
                Minecraft.getInstance().options.guiScale().set(3); Minecraft.getInstance().resizeGui();
                InventoryEffectsProbe.defaults();
                pointGui(40, 100);
            });
            step(14, () -> {
                check("inventory effects GUI scale coordinates", Math.abs(InventoryEffectsProbe.x() - 40) < 1 && Math.abs(InventoryEffectsProbe.y() - 100) < 1,
                        "native=" + InventoryEffectsProbe.x() + "," + InventoryEffectsProbe.y());
                shot("ip4_scale3");
                Minecraft.getInstance().options.guiScale().set(scale[0]); Minecraft.getInstance().resizeGui();
            });
            step(10, () -> { Settings.DEVELOPER_MODE.set(true); Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G)); });
            step(15, () -> {
                check("inventory effects editor hides carried effects", table().gridEditor.visible() && InventoryEffectsProbe.cursor() == Items.AIR
                        && InventoryEffectsProbe.particles() == 0, "editor remains usable");
                shot("ip5_editor");
                Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE));
                Settings.DEVELOPER_MODE.set(false);
            });
            step(8, () -> Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_LALT)));
            step(16, () -> {
                check("inventory effects free look hides carried effects", FreeLook.looking() && InventoryEffectsProbe.cursor() == Items.AIR
                        && InventoryEffectsProbe.particles() == 0, "no GUI trail over third-person view");
                Minecraft.getInstance().gui.screen().keyReleased(testKey(InputConstants.KEY_LALT));
            });
            step(16, Showcase::pointAside);
            step(8, () -> { drawerPoint(3); screenClick(0); Scene.leave(); });
            step(45, () -> check("inventory effects scene exits", Scene.phase == Scene.Phase.NONE, "phase=" + Scene.phase));
            nextAct("ip", new Vec3(-3.2, -60, 4.6), 200f, FURNACE, new Vec3(0.5, 0.6, 1), Direction.SOUTH);
        } else if (Scene.station instanceof FurnaceStation) {
            step(3, InventoryEffectsProbe::actionOnly);
            step(3, () -> { drawerPoint(5); screenClick(0); });
            step(10, () -> { InventoryEffectsProbe.clear(); pointZone(FURNACE, FurnaceZones.FUEL); });
            step(5, () -> screenClick(1));
            step(7, () -> {
                check("inventory effects furnace server transfer", ClientFurnaces.item(FURNACE, 1).is(Items.COAL), "fuel=" + ClientFurnaces.item(FURNACE, 1));
                check("inventory effects furnace action particles", InventoryEffectsProbe.particles() > 0, "particles=" + InventoryEffectsProbe.particles());
                shot("ip6_furnace"); drawerPoint(5); screenClick(0);
            });
            step(8, Scene::leave);
            step(45, () -> {});
            nextAct("ip", new Vec3(8.5, -60, 8.4), 180f, ANVIL, new Vec3(0.5, 1, 0.7), Direction.UP);
        } else {
            int[] ambientParticles = {0};
            step(3, () -> server(p -> p.getInventory().setItem(3, p.getInventory().getItem(17).copy())));
            step(10, InventoryEffectsProbe::actionOnly);
            step(3, () -> { drawerPoint(3); screenClick(0); });
            step(10, () -> { InventoryEffectsProbe.clear(); pointAt(((AnvilStation) Scene.station).layoutPoint(0)); });
            step(5, () -> screenClick(0));
            step(7, () -> {
                check("inventory effects vanilla menu transfer", ((AnvilStation) Scene.station).slot(0).is(Items.IRON_PICKAXE), "real anvil menu input");
                check("inventory effects vanilla menu particles", InventoryEffectsProbe.particles() > 0, "particles=" + InventoryEffectsProbe.particles());
                shot("ip7_anvil");
                InventoryEffectsProbe.allSlots(); pointAside();
                server(p -> p.getInventory().setItem(8, new ItemStack(Items.TORCH, 16)));
            });
            for (int i = 0; i < 12; i++) step(8, () -> ambientParticles[0] = Math.max(ambientParticles[0], InventoryEffectsProbe.particles()));
            step(1, () -> {
                check("inventory effects stationary slot particles", ambientParticles[0] > 0, "peak=" + ambientParticles[0]);
                shot("ip8_slots"); InventoryEffectsProbe.defaults();
                server(p -> p.getInventory().setItem(4, p.getInventory().getItem(19).copy()));
            });
            step(8, () -> { drawerPoint(4); screenClick(0); });
            step(8, () -> pointAt(((AnvilStation) Scene.station).layoutPoint(1)));
            step(5, () -> screenClick(0));
            step(7, () -> {
                check("inventory effects real forge recipe", !((AnvilStation) Scene.station).slot(2).isEmpty(), "enchantment output available");
                pointAt(((AnvilStation) Scene.station).restPoint(0));
            });
            step(5, () -> { InventoryEffectsProbe.actionOnly(); screenClick(0); });
            step(4, () -> check("inventory effects special move hides GUI copy", ((AnvilStation) Scene.station).moving()
                    && InventoryEffectsProbe.cursor() == Items.AIR, "hammer animation owns the carried item"));
            step(13, () -> {
                check("inventory effects animation beat particles", InventoryEffectsProbe.particles() > 0
                        && ((AnvilStation) Scene.station).moving(), "particles=" + InventoryEffectsProbe.particles());
                shot("ip9_forge_beat");
            });
            step(27, Showcase::pointAside);
            step(8, () -> {
                check("inventory effects restore cursor after forging", Hands.carried().is(Items.IRON_PICKAXE)
                        && Hands.carried().isEnchanted() && InventoryEffectsProbe.cursor() == Items.IRON_PICKAXE,
                        "real output=" + Hands.carried() + " enchanted=" + Hands.carried().isEnchanted()
                                + " inHand=" + Hands.itemInHand() + " cursor=" + InventoryEffectsProbe.cursor());
                shot("ipa_forged"); Scene.leave();
            });
            step(50, () -> finish(Minecraft.getInstance()));
        }
    }

    private static void drawerPoint(int inventorySlot) {
        Minecraft mc = Minecraft.getInstance();
        double scale = net.minecraft.util.Mth.clamp(mc.gui.screen().height * 0.068 / 18, 0.6, 1.25);
        pointGui(mc.gui.screen().width / 2.0 + (inventorySlot - 4) * 18 * scale, 6 + 14 * scale);
    }

    /** The reference packet keeps vanilla's own crafting recipes, transmute ones included, and no mod codecs. */
    private static void recipeSyncChecks() {
        var level = Minecraft.getInstance().level;
        var synced = ClientRecipes.crafting();
        boolean vanillaOnly = !synced.isEmpty() && synced.stream().allMatch(h -> {
            var key = net.minecraft.core.registries.BuiltInRegistries.RECIPE_SERIALIZER.getKey(h.value().getSerializer());
            return key != null && "minecraft".equals(key.getNamespace());
        });
        long transmute = synced.stream().filter(h -> h.value() instanceof net.minecraft.world.item.crafting.TransmuteRecipe).count();
        check("reference recipes use vanilla serializers only", vanillaOnly && transmute > 0,
                "recipes=" + synced.size() + " transmute=" + transmute);
        List<ItemStack> grid = new ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
        grid.set(4, new ItemStack(Items.SHULKER_BOX));
        //? if >=26.2 {
        grid.set(5, new ItemStack(Items.DYE.red()));
        //?} else if <26.2 {
        /*grid.set(5, new ItemStack(Items.RED_DYE));
        *///?}
        ItemStack dyed = dev.dyslexic.cinematic.table.TableCrafting.match(level, grid, net.minecraft.core.Direction.NORTH)
                .map(dev.dyslexic.cinematic.table.TableCrafting.Match::result).orElse(ItemStack.EMPTY);
        //? if >=26.2 {
        check("table previews a vanilla transmute recipe", dyed.is(Items.DYED_SHULKER_BOX.red()), "result=" + dyed);
        //?} else if <26.2 {
        /*check("table previews a vanilla transmute recipe", dyed.is(Items.RED_SHULKER_BOX), "result=" + dyed);
        *///?}
        grid = new ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
        for (int cell : new int[] {0, 1, 3, 4}) grid.set(cell, new ItemStack(Items.OAK_PLANKS));
        ItemStack bench = dev.dyslexic.cinematic.table.TableCrafting.match(level, grid, net.minecraft.core.Direction.NORTH)
                .map(dev.dyslexic.cinematic.table.TableCrafting.Match::result).orElse(ItemStack.EMPTY);
        check("table previews a shaped recipe", bench.is(Items.CRAFTING_TABLE), "result=" + bench);
        // A singleplayer connection passes packets as objects, so encode the reference batch here as a
        // dedicated server would; the unfiltered list shows whether a loaded mod would have dropped the player.
        server(p -> {
            var sent = dev.dyslexic.cinematic.net.RecipeSyncPayload.reference(p);
            String result = encodeRecipes(p, sent);
            check("reference packet encodes and decodes", result.equals("ok " + sent.size()), result);
            var all = p.level().getServer().getRecipeManager().getRecipes().stream()
                    .filter(h -> h.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe).toList();
            Cinematic.LOGGER.info("[Showcase] unfiltered crafting recipes ({}): {}", all.size(), encodeRecipes(p, all));
        });
    }

    private static String encodeRecipes(ServerPlayer p, List<net.minecraft.world.item.crafting.RecipeHolder<?>> recipes) {
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), p.level().registryAccess());
        try {
            dev.dyslexic.cinematic.net.RecipeSyncPayload.CODEC.encode(buf, new dev.dyslexic.cinematic.net.RecipeSyncPayload(false, recipes));
            return "ok " + dev.dyslexic.cinematic.net.RecipeSyncPayload.CODEC.decode(buf).recipes().size();
        } catch (RuntimeException e) {
            return "failed: " + e;
        } finally {
            buf.release();
        }
    }

    private static void tableScript() {
        shot("05_active");
        step(2, Showcase::recipeSyncChecks);
        tableGridAlignmentScript();
        tableGridEditorScript();
        tableRecipeBookScript();
        tableVisibilityScript();
        tableGlintVisibilityScript();
        tableSpecialVisibilityScript();
        step(4, () -> inventoryClick(36, 0));                // planks in hand
        step(3, () -> point(0, 0));
        step(12, () -> shot("06_carry"));
        step(2, () -> clickCell(0, 0, 1));
        step(6, () -> point(0, 1));
        step(8, () -> clickCell(0, 1, 1));
        step(6, () -> point(0, 2));
        step(8, () -> clickCell(0, 2, 1));
        step(6, () -> inventoryClick(36, 0));                // rest of the planks back
        step(4, () -> inventoryClick(37, 0));                // sticks
        step(3, () -> point(1, 1));
        step(8, () -> clickCell(1, 1, 1));
        step(6, () -> point(2, 1));
        step(8, () -> clickCell(2, 1, 1));
        step(6, () -> inventoryClick(37, 0));
        step(4, Showcase::pointAside);
        step(10, () -> shot("07_recipe"));
        step(2, () -> pointAt(table().resultPosition()));
        step(8, () -> shot("08_result_hover"));
        step(2, Showcase::clickResult);
        step(3, () -> shot("09_crafting_a"));
        step(3, () -> shot("09_crafting_b"));
        step(14, () -> shot("10_crafted"));
        step(2, () -> inventoryClick(44, 0));                // pickaxe into the last hotbar slot
        step(4, () -> inventoryClick(36, 0));                // planks again: three sticks' worth
        step(3, () -> point(0, 1));
        step(6, () -> clickCell(0, 1, 1));
        step(3, () -> clickCell(0, 1, 1));
        step(3, () -> clickCell(0, 1, 1));
        step(4, () -> point(1, 1));
        step(6, () -> clickCell(1, 1, 1));
        step(3, () -> clickCell(1, 1, 1));
        step(3, () -> clickCell(1, 1, 1));
        step(4, () -> inventoryClick(36, 0));
        step(4, Showcase::pointAside);
        step(8, () -> shot("10b_sticks"));
        step(2, () -> table().craft(true));                  // shift-click: craft all into the inventory
        step(14, () -> report("after craft-all"));
        step(4, () -> inventoryClick(38, 0));                // cobblestone
        step(3, () -> point(1, 1));
        step(8, () -> clickCell(1, 1, 0));
        step(6, () -> inventoryClick(40, 0));                // iron
        step(3, () -> point(0, 2));
        step(8, () -> clickCell(0, 2, 1));
        step(5, () -> point(2, 2));
        step(8, () -> clickCell(2, 2, 1));
        step(5, () -> inventoryClick(40, 0));
        step(4, () -> inventoryClick(39, 0));                // redstone, reached with the left hand
        step(3, () -> point(1, 0));
        step(12, () -> shot("11_left_hand"));
        step(2, () -> clickCell(1, 0, 0));
        step(6, Showcase::pointAside);
        step(8, () -> shot("12_items"));
        step(4, Showcase::hoverDrawer);
        step(10, () -> shot("13_drawer"));
        step(2, Showcase::pointAside);
        step(6, () -> report("table before leaving"));
        step(2, Scene::leave);
        step(50, () -> shot("09_after"));
        nextAct("3", new Vec3(-3.2, -60, 4.6), 200f, FURNACE, new Vec3(0.5, 0.6, 1.0), Direction.SOUTH);
    }

    /** Texture-derived targets must stay independent of TableGrid, or a misplaced grid would test itself. */
    private static void tableGridEditorScript() {
        TableLayout[] original = TableLayouts.profiles();
        int active = TableLayouts.selected();
        int[] previousGuiScale = new int[1];
        boolean originalDev = Settings.developerMode();
        step(2, () -> {
            Settings.DEVELOPER_MODE.set(false);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            double[] at = table().gridEditor.togglePoint();
            check("table editor hidden with developer mode off", !table().gridEditor.editing()
                    && !table().gridEditor.over(at[0], at[1]), "G and icon unavailable");
            Settings.DEVELOPER_MODE.set(true);
        });
        step(2, () -> {
            TableLayout turned = new TableLayout(1, -1, 90, 1, 1, 1, 1);
            Vec3 first = turned.cell(TABLE, 0);
            check("editor quarter-turn geometry", Math.abs(first.x - (TABLE.getX() + 0.375)) < 1e-9
                    && Math.abs(first.z - (TABLE.getZ() + 0.625)) < 1e-9
                    && Math.abs(first.y - (TABLE.getY() + 1.0625)) < 1e-9, "independent 90-degree reference=" + first);
            TableLayout bounded = new TableLayout(100, -100, 45, 1.2, 1.2, Double.NaN, Double.POSITIVE_INFINITY);
            boolean inside = true;
            for (double x : new double[]{3.5 / 16, 12.5 / 16}) for (double z : new double[]{3.5 / 16, 12.5 / 16}) {
                Vec3 p = bounded.point(BlockPos.ZERO, x, z);
                inside &= p.x >= 0.009 && p.x <= 0.991 && p.z >= 0.009 && p.z <= 0.991;
            }
            check("editor clamps malformed layouts", inside && bounded.height() == 0 && bounded.itemScale() == 1,
                    "rotated corners stay on the block");
        });
        step(2, () -> inventoryClick(36, 0));
        step(2, () -> {
            double[] at = table().gridEditor.togglePoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(20, () -> {
            check("editor opens without replacing scene", table().gridEditor.editing() && Minecraft.getInstance().gui.screen() instanceof SceneScreen
                    && !table().showsInventory(), "same scene, inventory hidden");
            shot("0d0_editor_open");
        });
        editorSliderStep(2, 24);
        editorSliderStep(3, 90);
        editorSliderStep(4, 80);
        editorSliderStep(5, 0.5);
        editorSliderStep(6, 75);
        step(16, () -> {
            check("editor live preview", Math.abs(TableLayouts.current().rotation() - 24) < 0.1
                    && Math.abs(TableLayouts.current().height() - 0.5) < 0.01, "layout=" + TableLayouts.current());
            shot("0d1_editor_rotated");
            pointAt(table().cellPoint(4));
            screenClick(1);
        });
        step(3, () -> {
            check("editor consumes item clicks", Hands.carried().getCount() == 24 && ClientTables.get(TABLE) == null,
                    "carried=" + Hands.carried());
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE));
        });
        step(25, () -> {
            check("editor cancel restores profile", !table().gridEditor.editing() && TableLayouts.current().equals(original[active])
                    && Scene.phase == Scene.Phase.ACTIVE, "scene remains active");
            inventoryClick(36, 0);
        });
        step(3, () -> Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G)));
        step(20, () -> {
            previousGuiScale[0] = Minecraft.getInstance().options.guiScale().get();
            Minecraft.getInstance().options.guiScale().set(3);
            Minecraft.getInstance().resizeGui();
        });
        step(8, () -> {
            shot("0d5_editor_gui_scale_3");
            Minecraft.getInstance().options.guiScale().set(previousGuiScale[0]);
            Minecraft.getInstance().resizeGui();
        });
        step(8, () -> {
            editorControl(80, 249);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_W));
            check("editor movement keys do not leave", Scene.phase == Scene.Phase.ACTIVE && table().gridEditor.editing(), "still editing");
        });
        editorSliderStep(2, 30);
        editorSliderStep(3, 85);
        editorSliderStep(4, 90);
        editorSliderStep(6, 80);
        step(15, () -> editorControl(145, 38));
        step(12, () -> {
            check("editor profile switching", Math.abs(TableLayouts.current().rotation() - original[(active + 1) % 3].rotation()) < 0.1,
                    "other profile restored");
            editorControl(18, 38);
        });
        step(20, () -> {
            check("editor profile draft retained", Math.abs(TableLayouts.current().rotation() - 30) < 0.1, "draft survives switching");
            Vec3 hit = table().cellPoint(4);
            pointAt(hit);
            screenClick(0);
            pointAt(hit.add(0.04, 0, -0.025));
            double[] mouse = Picking.mouseGui();
            Minecraft.getInstance().gui.screen().mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 4, -2);
            Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        });
        step(20, () -> {
            check("editor drag translates grid", TableLayouts.current().x() > 0.4 && TableLayouts.current().z() < -0.2,
                    "layout=" + TableLayouts.current());
            shot("0d2_editor_dragged");
            editorControl(42, 273);
        });
        step(25, () -> {
            check("editor saves profile", !table().gridEditor.editing() && java.nio.file.Files.exists(TableLayouts.path())
                    && TableLayouts.profiles()[active].rotation() == 30, "saved=" + TableLayouts.path());
            try {
                check("editor persists values", java.nio.file.Files.readString(TableLayouts.path()).contains("30.0"), "JSON contains rotation");
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        step(3, () -> Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G)));
        step(18, () -> editorControl(80, 249));
        step(20, () -> {
            check("editor reset preview", TableLayouts.current().equals(TableLayout.VANILLA), "vanilla preview");
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE));
        });
        step(25, () -> check("editor reset can be cancelled", Math.abs(TableLayouts.current().rotation() - 30) < 0.1,
                "saved layout retained"));
        for (int i = 0; i < 9; i++) {
            final int cell = i;
            step(2, () -> pointAt(table().cellPoint(cell)));
            step(4, () -> check("editor transformed picking " + cell, table().hoverCell == cell, "hover=" + table().hoverCell));
        }
        step(3, () -> {
            double[] at = table().recipeBook.togglePoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(18, () -> recipeSearch("wooden_pickaxe"));
        step(5, () -> pointRecipe("wooden_pickaxe"));
        step(3, () -> screenClick(0));
        step(15, () -> {
            shot("0d3_editor_recipe_ghosts");
            double[] at = table().recipeBook.layPoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(60, () -> {
            check("editor recipe layout reaches adjusted cells", !table().result.isEmpty() && table().result.is(Items.WOODEN_PICKAXE)
                    && table().recipeBook.guide.missingCount == 0, "result=" + table().result);
            shot("0d4_editor_laid_out");
        });
        for (int[] rc : new int[][]{{0,0},{0,1},{0,2},{1,1},{2,1}}) {
            step(2, () -> pointAt(table().cellPoint(TableGrid.worldIndex(table().sideDirection(), rc[0], rc[1]))));
            step(4, () -> screenClick(0));
            step(4, () -> inventoryClick(rc[0] == 0 ? 36 : 37, 0));
        }
        step(3, () -> recipeControl(113, 194));
        step(3, () -> recipeControl(113, 12));
        step(20, () -> {
            check("editor walkthrough restores ingredients", ClientTables.get(TABLE) == null
                    || ClientTables.get(TABLE).list().stream().allMatch(ItemStack::isEmpty), "table emptied");
            check("editor restore original profiles", TableLayouts.save(original, active), "original profiles restored");
        });
        step(25, () -> { Settings.DEVELOPER_MODE.set(originalDev); pointAside(); });
    }

    private static void editorSliderStep(int field, double value) {
        step(3, () -> {
            double[] at = table().gridEditor.sliderPoint(field, value);
            pointGui(at[0], at[1]);
            screenClick(0);
            Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        });
    }

    private static void editorControl(double x, double y) {
        double[] at = table().gridEditor.controlPoint(x, y);
        pointGui(at[0], at[1]);
        screenClick(0);
        Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
    }

    private static void tableGridAlignmentScript() {
        step(2, () -> {
            boolean aligned = true;
            for (int i = 0; i < 9; i++) {
                double x = (5 + 3 * (i % 3)) / 16.0;
                double z = (5 + 3 * (i / 3)) / 16.0;
                aligned &= Math.abs(TableGrid.cellX(i) - x) < 1e-9 && Math.abs(TableGrid.cellZ(i) - z) < 1e-9;
            }
            check("table cells centered on texture", aligned && TableGrid.cellX(4) == 0.5 && TableGrid.cellZ(4) == 0.5,
                    "texture centres 5, 8, 11 on both axes");
            double low = 3.5 / 16.0, high = 12.5 / 16.0, epsilon = 0.0001;
            boolean bounds = TableGrid.cellAt(low - epsilon, 0.5) == -1 && TableGrid.cellAt(high, 0.5) == -1
                    && TableGrid.cellAt(0.5, low - epsilon) == -1 && TableGrid.cellAt(0.5, high) == -1
                    && TableGrid.cellAt(low + epsilon, low + epsilon) == 0
                    && TableGrid.cellAt(high - epsilon, high - epsilon) == 8;
            for (int line = 1; line <= 2; line++) {
                double boundary = (3.5 + 3 * line) / 16.0;
                bounds &= TableGrid.cellAt(boundary - epsilon, 0.5) == 3 + line - 1
                        && TableGrid.cellAt(boundary + epsilon, 0.5) == 3 + line;
            }
            check("table picking follows painted boundaries", bounds, "line centres delimit adjacent cells");
        });
        for (int i = 0; i < 9; i++) {
            final int cell = i;
            step(2, () -> pointAt(new Vec3(TABLE.getX() + (5 + 3 * (cell % 3)) / 16.0, TABLE.getY() + 1.0,
                    TABLE.getZ() + (5 + 3 * (cell / 3)) / 16.0)));
            step(5, () -> {
                check("table texture cell reachable " + cell, table().hoverCell == cell, "hover=" + table().hoverCell);
                shot("0q" + cell + "_aligned_cell");
            });
        }
        step(2, Showcase::pointAside);
    }

    /** Exercise the real reference controls and prove that the diagram never edits server inventories. */
    private static void tableRecipeBookScript() {
        step(4, () -> {
            var units = List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS));
            int[] assignment = TableRecipeGuide.match(List.of(Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS),
                    Ingredient.of(Items.OAK_PLANKS)), units);
            check("recipe tag matching", assignment[0] == 1 && assignment[1] == 0, java.util.Arrays.toString(assignment));
            int[] repeated = TableRecipeGuide.match(List.of(Ingredient.of(Items.DIAMOND), Ingredient.of(Items.DIAMOND)),
                    List.of(new ItemStack(Items.DIAMOND)));
            check("recipe duplicate quantities", java.util.Arrays.stream(repeated).filter(i -> i >= 0).count() == 1,
                    java.util.Arrays.toString(repeated));
            var book = table().recipeBook;
            double[] at = book.togglePoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(16, Showcase::pointAside);
        step(4, () -> shot("0r1_recipe_reference"));
        step(2, () -> recipeSearch("diamond_pickaxe"));
        step(4, () -> pointRecipe("diamond_pickaxe"));
        step(3, () -> screenClick(0));
        step(12, () -> {
            var guide = table().recipeBook.guide;
            check("recipe missing diamonds", guide.selected != null && guide.missingCount == 3 && !guide.selected.craftable,
                    "missing=" + guide.missingCount);
            check("recipe search movement keys", Scene.phase == Scene.Phase.ACTIVE, "phase=" + Scene.phase);
            ClientTables.Entry contents = ClientTables.get(TABLE);
            check("recipe guide is local", (contents == null || contents.list().stream().allMatch(ItemStack::isEmpty))
                    && Hands.carried().isEmpty(), "no real items placed");
            server(p -> {
                TableStore.Table stored = TableStore.get(p.level()).get(TABLE);
                check("recipe guide server unchanged", (stored == null || stored.items.stream().allMatch(ItemStack::isEmpty))
                        && p.getInventory().getItem(0).getCount() == 24 && p.getInventory().getItem(1).getCount() == 12,
                        "planks=" + p.getInventory().getItem(0).getCount() + " sticks=" + p.getInventory().getItem(1).getCount());
            });
            pointAside();
        });
        step(4, () -> shot("0r2_missing_diamonds"));
        step(2, () -> recipeControl(12, 52)); // only craftable recipes
        step(4, () -> check("recipe availability filter", table().recipeBook.recipePoint(Identifier.withDefaultNamespace("diamond_pickaxe")) == null,
                "diamond pickaxe hidden"));
        step(2, () -> recipeSearch("wooden_pickaxe"));
        step(4, () -> pointRecipe("wooden_pickaxe"));
        step(3, () -> screenClick(0));
        step(10, () -> {
            var guide = table().recipeBook.guide;
            check("recipe available planks and sticks", guide.selected != null && guide.selected.craftable && guide.missingCount == 0,
                    "missing=" + guide.missingCount);
            pointAside();
        });
        step(4, () -> shot("0r3_available_guide"));
        step(2, () -> inventoryClick(36, 0));
        step(3, () -> recipeControl(64, 12)); // clicking reference with a carried stack must not place it
        step(3, () -> check("recipe panel consumes carried click", Hands.carried().getCount() == 24 && ClientTables.get(TABLE) == null,
                "carried=" + describe(Hands.carried())));
        step(2, () -> point(0, 0));
        step(8, () -> clickCell(0, 0, 1));
        step(8, () -> {
            check("recipe placed ingredient", table().recipeBook.guide.placed[0], "first plank accepted");
            shot("0r4_partial_recipe");
        });
        step(2, () -> inventoryClick(36, 0));
        step(2, () -> inventoryClick(38, 0));
        step(2, () -> point(0, 1));
        step(8, () -> clickCell(0, 1, 1));
        step(8, () -> {
            check("recipe wrong ingredient", table().recipeBook.guide.wrong(1), "cobblestone instead of planks");
            shot("0r5_wrong_ingredient");
        });
        step(2, () -> inventoryClick(38, 0));
        step(2, () -> clickCell(0, 1, 0));
        step(6, () -> inventoryClick(38, 0));
        step(2, () -> point(0, 0));
        step(6, () -> clickCell(0, 0, 0));
        step(6, () -> inventoryClick(36, 0));
        step(6, () -> {
            var contents = ClientTables.get(TABLE);
            check("recipe walkthrough restores inventory", (contents == null || contents.list().stream().allMatch(ItemStack::isEmpty))
                    && Hands.carried().isEmpty() && Minecraft.getInstance().player.getInventory().getItem(0).getCount() == 24,
                    "planks=" + Minecraft.getInstance().player.getInventory().getItem(0).getCount());
        });
        step(2, () -> recipeControl(113, 12)); // fold away, keep the diagram
        step(15, () -> {
            check("recipe folded guide retained", table().recipeBook.guide.selected != null, "selection retained");
            pointAside();
            shot("0r6_folded_reference");
            var at = table().recipeBook.togglePoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(15, () -> recipeControl(113, 194));
        step(3, () -> check("recipe clear guide", table().recipeBook.guide.selected == null, "cleared"));
        tableRecipeLayoutScript();
        step(2, () -> recipeControl(113, 12));
        step(15, Showcase::pointAside);
    }

    private static void tableRecipeLayoutScript() {
        step(2, () -> server(p -> {
            p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG));
            p.containerMenu.broadcastChanges();
        }));
        step(8, () -> recipeSearch("minecraft:oak_planks"));
        step(3, () -> pointRecipe("oak_planks"));
        step(3, () -> screenClick(0));
        step(4, () -> inventoryClick(44, 0));
        step(3, () -> point(2, 2));
        step(6, () -> clickCell(2, 2, 0));
        step(8, () -> {
            var guide = table().recipeBook.guide;
            //? if >=26.2 {
            var contents = ClientTables.get(table().pos);
            //?}
            check("recipe shapeless follows placement", guide.placed[8] && TableRecipeGuide.empty(guide.grid[0]) && guide.missingCount == 0,
                    //? if >=26.2 {
                    "placed8=" + guide.placed[8] + " grid0Empty=" + TableRecipeGuide.empty(guide.grid[0])
                            + " missing=" + guide.missingCount + " cell8=" + (contents == null ? "no contents" : contents.items[TableGrid.worldIndex(table().side, 2, 2)])
                            + " carried=" + Minecraft.getInstance().player.containerMenu.getCarried());
                    //?} else if <26.2 {
                    /*"log at bottom right");
                    *///?}
            shot("0r7_shapeless_recipe");
        });
        step(2, () -> clickCell(2, 2, 0));
        step(6, () -> inventoryClick(44, 0));
        step(4, () -> server(p -> {
            p.getInventory().setItem(8, ItemStack.EMPTY);
            p.containerMenu.broadcastChanges();
        }));
        step(6, () -> recipeSearch("minecraft:stick"));
        step(3, () -> pointRecipe("stick"));
        step(3, () -> screenClick(0));
        step(6, () -> {
            var guide = table().recipeBook.guide;
            check("recipe narrow pattern centered", !TableRecipeGuide.empty(guide.grid[1]) && !TableRecipeGuide.empty(guide.grid[4]) && TableRecipeGuide.empty(guide.grid[0]),
                    "one-column pattern");
        });
        step(2, () -> inventoryClick(36, 0));
        step(3, () -> point(1, 0));
        step(6, () -> clickCell(1, 0, 1));
        step(8, () -> {
            var guide = table().recipeBook.guide;
            check("recipe shaped follows offset", guide.placed[3] && (!TableRecipeGuide.empty(guide.grid[0]) || !TableRecipeGuide.empty(guide.grid[6])) && TableRecipeGuide.empty(guide.grid[1]),
                    "pattern moved to left column");
            shot("0r8_offset_recipe");
        });
        step(2, () -> inventoryClick(36, 0));
        step(2, () -> clickCell(1, 0, 0));
        step(6, () -> inventoryClick(36, 0));
        step(3, () -> recipeSearch("minecraft:wooden_axe"));
        step(3, () -> pointRecipe("wooden_axe"));
        step(3, () -> screenClick(0));
        step(3, () -> inventoryClick(37, 0));
        step(3, () -> point(2, 0));
        step(6, () -> clickCell(2, 0, 1));
        step(8, () -> {
            var guide = table().recipeBook.guide;
            check("recipe shaped follows mirror", guide.placed[6] && TableRecipeGuide.empty(guide.grid[7]) && guide.grid[4].test(new ItemStack(Items.OAK_PLANKS)),
                    "mirrored axe pattern");
            shot("0r9_mirrored_recipe");
        });
        step(2, () -> inventoryClick(37, 0));
        step(2, () -> clickCell(2, 0, 0));
        step(6, () -> inventoryClick(37, 0));
        step(4, () -> {
            BlockPos testPos = TABLE.offset(100, 0, 0);
            var entry = table().recipeBook.guide.entries.stream().filter(e -> e.holder.id().identifier().equals(Identifier.withDefaultNamespace("wooden_pickaxe"))).findFirst().orElseThrow();
            Direction[] sides = {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST};
            int[] firstCells = {0, 8, 6, 2};
            try {
                for (int i = 0; i < sides.length; i++) {
                    var contents = new ClientTables.Entry();
                    contents.items[firstCells[i]] = new ItemStack(Items.OAK_PLANKS);
                    ClientTables.TABLES.put(testPos, contents);
                    var guide = new TableRecipeGuide(new TableStation(testPos, sides[i]));
                    guide.select(entry);
                    check("recipe orientation " + sides[i], guide.placed[0], "world cell=" + firstCells[i]);
                }
            } finally {
                ClientTables.TABLES.remove(testPos);
            }
        });
        step(2, () -> recipeControl(113, 194));
        step(6, () -> {
            var player = Minecraft.getInstance().player;
            var contents = ClientTables.get(TABLE);
            check("recipe layout checks restore inventory", (contents == null || contents.list().stream().allMatch(ItemStack::isEmpty))
                            && Hands.carried().isEmpty() && player.getInventory().getItem(0).getCount() == 24
                            && player.getInventory().getItem(1).getCount() == 12 && player.getInventory().getItem(8).isEmpty(),
                    "planks=" + player.getInventory().getItem(0).getCount() + " sticks=" + player.getInventory().getItem(1).getCount());
        });
    }

    private static void recipeControl(int x, int y) {
        var at = table().recipeBook.controlPoint(x, y);
        pointGui(at[0], at[1]);
        screenClick(0);
    }

    private static void recipeSearch(String query) {
        recipeControl(30, 34);
        var screen = Minecraft.getInstance().gui.screen();
        screen.keyPressed(testKey(InputConstants.KEY_END));
        for (int i = 0; i < 96; i++) screen.keyPressed(testKey(InputConstants.KEY_BACKSPACE));
        for (char c : query.toCharArray()) {
            if (c >= 'a' && c <= 'z') screen.keyPressed(testKey(InputConstants.KEY_A + c - 'a'));
            //? if >=26.1 {
            screen.charTyped(new net.minecraft.client.input.CharacterEvent(c));
            //?} else if <26.1 {
            /*screen.charTyped(new net.minecraft.client.input.CharacterEvent(c, 0));
            *///?}
        }
        screen.keyPressed(testKey(InputConstants.KEY_ESCAPE)); // first Esc releases search focus
    }

    private static void pointRecipe(String id) {
        var at = table().recipeBook.recipePoint(Identifier.withDefaultNamespace(id));
        check("recipe found " + id, at != null, "reference lookup");
        if (at != null) pointGui(at[0], at[1]);
    }

    /** The centre cube hides the far cell; a cube beside it must remain solid. */
    private static void tableVisibilityScript() {
        step(4, () -> inventoryClick(38, 0));
        step(3, () -> point(1, 1));
        step(6, () -> clickCell(1, 1, 1));
        step(3, () -> point(1, 0));
        step(6, () -> clickCell(1, 0, 1));
        step(6, () -> inventoryClick(38, 0));
        step(2, Showcase::pointAside);
        step(8, () -> {
            shot("05a_occlusion_solid");
            checkTableOpacity("table cubes at rest", false);
        });
        step(2, () -> inventoryClick(40, 0));
        step(3, () -> point(0, 1));
        step(1, () -> shot("05b_occlusion_fading"));
        step(6, () -> {
            shot("05c_occlusion_clear");
            checkTableOpacity("table far cell revealed", true);
        });
        step(2, () -> clickCell(0, 1, 1));
        step(8, () -> {
            Direction side = table().sideDirection();
            int centre = TableGrid.worldIndex(side, 1, 1);
            int far = TableGrid.worldIndex(side, 0, 1);
            int beside = TableGrid.worldIndex(side, 1, 0);
            server(p -> {
                TableStore.Table stored = TableStore.get(p.level()).get(TABLE);
                boolean correct = stored != null && stored.items.get(centre).is(Items.COBBLESTONE)
                        && stored.items.get(centre).getCount() == 1 && stored.items.get(beside).is(Items.COBBLESTONE)
                        && stored.items.get(beside).getCount() == 1 && stored.items.get(far).is(Items.IRON_INGOT)
                        && stored.items.get(far).getCount() == 1;
                check("placement behind the table cube", correct, "centre=" + centre + " far=" + far + " beside=" + beside
                        + " stored=" + (stored == null ? "-" : stored.items));
            });
            shot("05d_occlusion_placed");
        });
        step(2, () -> inventoryClick(40, 0));
        step(2, Showcase::pointAside);
        step(8, () -> {
            shot("05e_occlusion_recovered");
            checkTableOpacity("table cubes recover opacity", false);
        });
        // Restore the original inventory and empty grid before the crafting walkthrough.
        step(2, () -> point(0, 1));
        step(6, () -> clickCell(0, 1, 0));
        step(6, () -> inventoryClick(40, 0));
        step(2, () -> point(1, 1));
        step(6, () -> clickCell(1, 1, 0));
        step(6, () -> inventoryClick(38, 0));
        step(2, () -> point(1, 0));
        step(6, () -> clickCell(1, 0, 0));
        step(6, () -> inventoryClick(38, 0));
        step(2, Showcase::pointAside);
    }

    private static void checkTableOpacity(String name, boolean occluded) {
        TableStation station = table();
        if (station == null) {
            check(name, false, "no table scene");
            return;
        }
        Direction side = station.sideDirection();
        int centre = TableGrid.worldIndex(side, 1, 1);
        int beside = TableGrid.worldIndex(side, 1, 0);
        double centreOpacity = station.itemOpacity(centre);
        double besideOpacity = station.itemOpacity(beside);
        int expectedHover = occluded ? TableGrid.worldIndex(side, 0, 1) : -1;
        boolean correct = station.hoverCell == expectedHover && besideOpacity >= 0.98
                && (occluded ? centreOpacity < 0.6 : centreOpacity >= 0.98);
        check(name, correct, String.format("centre=%.3f beside=%.3f hover=%d expected=%d", centreOpacity, besideOpacity,
                station.hoverCell, expectedHover));
    }

    /** Glint requests two render buffers together, so exercise it while the cube is translucent. */
    private static void tableGlintVisibilityScript() {
        step(4, () -> server(p -> {
            p.getInventory().getItem(2).set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            p.containerMenu.broadcastChanges();
        }));
        step(8, () -> inventoryClick(38, 0));
        step(3, () -> point(1, 1));
        step(6, () -> clickCell(1, 1, 1));
        step(6, () -> inventoryClick(38, 0));
        step(2, Showcase::pointAside);
        step(8, () -> shot("05f_glint_solid"));
        step(2, () -> point(0, 1));
        step(8, () -> {
            shot("05g_glint_faded");
            checkTableOpacity("glint cube reveals the far cell", true);
            int centre = TableGrid.worldIndex(table().sideDirection(), 1, 1);
            ClientTables.Entry entry = ClientTables.get(TABLE);
            check("faded cube keeps its glint", entry != null && entry.items[centre].hasFoil(),
                    "stack=" + (entry == null ? "-" : entry.items[centre]));
        });
        step(2, Showcase::pointAside);
        step(8, () -> {
            shot("05h_glint_recovered");
            checkTableOpacity("glint cube recovers opacity", false);
        });
        step(2, () -> point(1, 1));
        step(6, () -> clickCell(1, 1, 0));
        step(6, () -> inventoryClick(38, 0));
        step(6, () -> server(p -> {
            ItemStack cobblestone = p.getInventory().getItem(2);
            cobblestone.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
            p.containerMenu.broadcastChanges();
            TableStore.Table stored = TableStore.get(p.level()).get(TABLE);
            boolean empty = stored == null || stored.items.stream().allMatch(ItemStack::isEmpty);
            check("visibility checks restore ingredients", empty && cobblestone.is(Items.COBBLESTONE)
                            && cobblestone.getCount() == 40 && p.getInventory().getItem(4).is(Items.IRON_INGOT)
                            && p.getInventory().getItem(4).getCount() == 5 && p.containerMenu.getCarried().isEmpty(),
                    "tableEmpty=" + empty + " cobblestone=" + cobblestone + " iron=" + p.getInventory().getItem(4)
                            + " carried=" + p.containerMenu.getCarried());
        }));
        step(8, Showcase::pointAside);
    }

    /** Special block models must pass through the same alpha consumer as ordinary item quads. */
    private static void tableSpecialVisibilityScript() {
        step(4, () -> server(p -> {
            p.getInventory().setItem(2, new ItemStack(Items.CHEST));
            p.containerMenu.broadcastChanges();
        }));
        step(8, () -> inventoryClick(38, 0));
        step(3, () -> point(1, 1));
        step(6, () -> clickCell(1, 1, 1));
        step(2, Showcase::pointAside);
        step(8, () -> shot("05i_special_model_solid"));
        step(2, () -> point(0, 1));
        step(8, () -> {
            int center = TableGrid.worldIndex(table().sideDirection(), 1, 1);
            check("special model reveals the far table cell", table().itemOpacity(center) < 0.6, "chest alpha follows occlusion");
            shot("05j_special_model_faded");
        });
        step(2, () -> point(1, 1));
        step(6, () -> clickCell(1, 1, 0));
        step(6, () -> inventoryClick(38, 0));
        step(4, () -> server(p -> {
            check("special model returns its real item", p.getInventory().getItem(2).is(Items.CHEST), "chest returned from the logical cell");
            p.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 40));
            p.containerMenu.broadcastChanges();
        }));
        step(8, Showcase::pointAside);
    }

    private static TableStation table() {
        return Scene.station(TableStation.class);
    }

    private static Vec3 cellPoint(int row, int col) {
        int index = TableGrid.worldIndex(Scene.station.sideDirection(), row, col);
        return new Vec3(TABLE.getX() + TableGrid.cellX(index), TABLE.getY() + 1.0, TABLE.getZ() + TableGrid.cellZ(index));
    }

    private static void point(int row, int col) {
        pointAt(cellPoint(row, col));
    }

    private static void clickCell(int row, int col, int button) {
        int expected = TableGrid.worldIndex(Scene.station.sideDirection(), row, col);
        TableStation station = table();
        if (station == null || station.hoverCell != expected) {
            REPORT.add("cursor missed cell r" + row + "c" + col + ": hover=" + (station == null ? "-" : station.hoverCell) + " expected=" + expected);
            Cinematic.LOGGER.info("[Showcase] cursor diagnostic mouse={} target={} panel={} book={} hit={}",
                    java.util.Arrays.toString(Picking.mouseGui()), java.util.Arrays.toString(Picking.toGui(cellPoint(row, col))),
                    Scene.overPanel, station != null && station.recipeBook.over(Picking.mouseGui()[0], Picking.mouseGui()[1]),
                    Picking.cursorOnPlane(TABLE.getY() + 1.0));
        }
        screenClick(button);
        //? if >=26.2 {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) {
            double[] at = Picking.mouseGui();
            mc.gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1],
                    new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(button), 0)));
        }
        //?} else if <26.2 {
        /*Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(
                Picking.mouseGui()[0], Picking.mouseGui()[1],
                new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(button), 0)));
        *///?}
    }

    private static void clickResult() {
        TableStation station = table();
        if (station == null || !station.hoverResult) {
            REPORT.add("cursor missed the result");
        }
        screenClick(0);
    }

    // ---- the furnaces -------------------------------------------------------------------------------

    private static FurnaceStation furnace() { return Scene.station(FurnaceStation.class); }

    private static void furnaceEditorScript() {
        FurnaceStation station = furnace();
        StationKind kind = station.kind();
        FurnaceAppearance[] original = FurnaceLayouts.profiles(kind);
        int selected = FurnaceLayouts.selected(kind);
        boolean originalDev = Settings.developerMode();
        String prefix = "p" + kind.name().toLowerCase(java.util.Locale.ROOT);
        int[] originalGui = new int[1];
        StationKind[] otherKinds = java.util.Arrays.stream(new StationKind[]{StationKind.FURNACE, StationKind.SMOKER, StationKind.BLAST_FURNACE})
                .filter(k -> k != kind).toArray(StationKind[]::new);
        FurnaceAppearance[][] otherProfiles = {FurnaceLayouts.profiles(otherKinds[0]), FurnaceLayouts.profiles(otherKinds[1])};
        step(2, () -> {
            Settings.DEVELOPER_MODE.set(false);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            double[] at = station.gridEditor.togglePoint();
            check(prefix + " developer mode off", !station.gridEditor.editing() && !station.gridEditor.over(at[0], at[1]), "no G or icon");
            FurnaceAppearance.Region shifted = FurnaceAppearance.Region.VANILLA.with(0, 1).with(1, 1);
            FurnaceAppearance independent = FurnaceAppearance.VANILLA.with(0, shifted);
            double[] base = station.layout.center(0);
            Vec3 expected = FurnaceZones.point(station.pos, station.sideDirection(), station.layout.zone(0).face(), base[0] + 1.0 / 16, base[1] + 1.0 / 16, 0);
            check(prefix + " independent pixel geometry", independent.point(station.layout, station.pos, station.sideDirection(), 0, base[0], base[1], 0, false).distanceTo(expected) < 1e-9,
                    "one texture pixel on the original face");
            FurnaceAppearance.Region malformed = new FurnaceAppearance.Region(Double.NaN, 999, 999, -999, Double.POSITIVE_INFINITY, 999, -999, 999, -999, 999);
            check(prefix + " malformed values bounded", malformed.u() == 0 && malformed.v() == 6 && malformed.itemScale() == 1.5 && malformed.out() == 0, "finite clamped values");
            Settings.DEVELOPER_MODE.set(true);
        });
        // Pick a real coal stack, then make sure editor clicks cannot insert it.
        step(3, () -> inventoryClick(16, 0));
        step(3, () -> {
            double[] at = station.gridEditor.togglePoint(); pointGui(at[0], at[1]); screenClick(0);
        });
        step(20, () -> {
            check(prefix + " opens in scene", station.gridEditor.editing() && !station.showsInventory(), "inventory hidden");
            shot(prefix + "_open");
        });
        furnaceEditorSlider(0, 0.5);
        furnaceEditorSlider(1, 0.5);
        furnaceEditorSlider(2, 85);
        furnaceEditorSlider(3, 90);
        furnaceEditorSlider(4, 0.5);
        furnaceEditorSlider(5, 80);
        step(18, () -> {
            check(prefix + " preview", Math.abs(station.geometry().input().u() - 0.5) < 0.01
                    && Math.abs(station.geometry().input().itemScale() - 0.8) < 0.01, "live geometry");
            pointAt(station.geometry().center(station.layout, station.pos, station.sideDirection(), 0));
            screenClick(1);
        });
        step(3, () -> {
            check(prefix + " editor consumes carried clicks", Hands.carried().is(Items.COAL) && Hands.carried().getCount() == 16
                    && ClientFurnaces.item(station.pos, 0).isEmpty() && ClientFurnaces.item(station.pos, 1).isEmpty(), "real stack untouched");
            furnaceEditorControl(80, 90);
        });
        furnaceEditorSlider(0, 120);
        furnaceEditorSlider(1, 110);
        furnaceEditorSlider(2, 0.25);
        furnaceEditorSlider(3, -0.25);
        step(18, () -> {
            check(prefix + " advanced independent", Math.abs(station.geometry().input().itemU() - 0.25) < 0.01
                    && Math.abs(station.geometry().input().clickWidth() - 1.2) < 0.01, "item and target offsets");
            shot(prefix + "_advanced");
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE));
        });
        step(25, () -> {
            check(prefix + " cancel", station.geometry().equals(original[selected]) && Scene.phase == Scene.Phase.ACTIVE, "saved profile restored");
            inventoryClick(16, 0);
        });
        step(3, () -> Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G)));
        step(20, () -> furnaceEditorControl(80, 298));
        furnaceEditorSlider(0, 0.5);
        furnaceEditorSlider(4, 0.5);
        step(18, () -> {
            Vec3 start = station.geometry().center(station.layout, station.pos, station.sideDirection(), 0);
            pointAt(start); screenClick(0);
            pointAt(start.add(station.right().scale(0.025)));
            double[] at = Picking.mouseGui();
            Minecraft.getInstance().gui.screen().mouseDragged(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 3, 0);
            Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        });
        step(20, () -> {
            check(prefix + " drag", station.geometry().input().u() > 0.7, "zone follows face drag");
            furnaceEditorControl(160, 38);
        });
        step(20, () -> {
            check(prefix + " profile switching", station.geometry().equals(original[(selected + 1) % 3]), "independent profile");
            furnaceEditorControl(18, 38);
        });
        step(20, () -> {
            check(prefix + " draft retained", station.geometry().input().u() > 0.7, "draft survived switching");
            furnaceEditorControl(160, 65);
        });
        furnaceEditorSlider(0, -0.25);
        furnaceEditorSlider(1, 0.25);
        furnaceEditorSlider(2, 90);
        furnaceEditorSlider(4, 0.25);
        step(15, () -> furnaceEditorControl(160, 65));
        furnaceEditorSlider(0, 0.25);
        furnaceEditorSlider(1, 0.25);
        furnaceEditorSlider(5, 90);
        step(18, () -> {
            check(prefix + " all slots independently edited", Math.abs(station.geometry().fuel().u() + 0.25) < 0.01
                    && Math.abs(station.geometry().result().itemScale() - 0.9) < 0.01, "fuel and output settings");
            shot(prefix + "_all_zones");
            furnaceEditorControl(160, 65);
        });
        furnaceEditorSlider(0, 0.5);
        furnaceEditorSlider(1, 0.5);
        step(18, () -> {
            check(prefix + " gauge preview", Math.abs(station.geometry().cooking().v() - 0.5) < 0.01, "indicator moved independently");
            shot(prefix + "_gauge");
            furnaceEditorControl(160, 65);
        });
        furnaceEditorSlider(0, -0.5);
        furnaceEditorSlider(1, 0.25);
        furnaceEditorSlider(4, 0.125);
        step(18, () -> {
            check(prefix + " fuel gauge preview", Math.abs(station.geometry().burning().v() - 0.25) < 0.01
                    && Math.abs(station.geometry().cooking().v() - 0.5) < 0.01, "two independent indicators");
            shot(prefix + "_fuel_gauge");
            originalGui[0] = Minecraft.getInstance().options.guiScale().get();
            Minecraft.getInstance().options.guiScale().set(3); Minecraft.getInstance().resizeGui();
        });
        step(8, () -> {
            shot(prefix + "_gui_3");
            Minecraft.getInstance().options.guiScale().set(originalGui[0]); Minecraft.getInstance().resizeGui();
        });
        step(8, () -> {
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_W));
            check(prefix + " movement blocked", station.gridEditor.editing() && Scene.phase == Scene.Phase.ACTIVE, "same scene");
            furnaceEditorControl(45, 323);
        });
        step(25, () -> {
            check(prefix + " saves JSON", !station.gridEditor.editing() && java.nio.file.Files.exists(FurnaceLayouts.path())
                    && FurnaceLayouts.profiles(kind)[selected].input().u() > 0.7, "local profile saved");
            boolean boundedGlow = true;
            for (var glow : station.layout.glows) for (int zone = 0; zone < 3; zone++) {
                if (station.layout.zone(zone).face() != glow.face()) continue;
                double[] rect = station.geometry().glowRect(station.layout, glow, zone);
                if (rect != null) boundedGlow &= rect[0] >= 0 && rect[1] <= 1 && rect[2] >= 0 && rect[3] <= 1;
            }
            check(prefix + " warm light bounded", boundedGlow, "light remains on the configured face");
            check(prefix + " furnace types independent", java.util.Arrays.equals(otherProfiles[0], FurnaceLayouts.profiles(otherKinds[0]))
                    && java.util.Arrays.equals(otherProfiles[1], FurnaceLayouts.profiles(otherKinds[1])), "other furnaces unchanged");
            Settings.DEVELOPER_MODE.set(false);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            check(prefix + " saved layout with dev off", !station.gridEditor.editing() && station.geometry().input().u() > 0.7, "geometry applies, editor unavailable");
        });
        for (int i = 0; i < 3; i++) {
            int zone = i;
            step(3, () -> pointAt(station.geometry().center(station.layout, station.pos, station.sideDirection(), zone)));
            step(5, () -> check(prefix + " adjusted picking " + zone, station.hoverZone == zone, "hover=" + station.hoverZone));
        }
        step(3, () -> inventoryClick(16, 0));
        step(3, () -> pointAt(station.geometry().center(station.layout, station.pos, station.sideDirection(), 1)));
        step(5, () -> clickZone(1, 1));
        step(8, () -> {
            check(prefix + " real placement", ClientFurnaces.item(station.pos, 1).getCount() == 1 && Hands.carried().getCount() == 15, "one coal in logical fuel slot");
            inventoryClick(16, 0);
            server(p -> p.level().setBlockAndUpdate(station.pos, p.level().getBlockState(station.pos)
                    .setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT, true)));
        });
        step(8, () -> {
            shot(prefix + "_adjusted_warm_light");
            server(p -> p.level().setBlockAndUpdate(station.pos, p.level().getBlockState(station.pos)
                    .setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT, false)));
        });
        step(3, () -> pointAt(station.geometry().center(station.layout, station.pos, station.sideDirection(), 1)));
        step(5, () -> clickZone(1, 0));
        step(8, () -> inventoryClick(16, 0));
        step(3, () -> {
            Settings.DEVELOPER_MODE.set(true);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
        });
        step(20, () -> furnaceEditorSliderNow(0, -0.5));
        step(18, () -> Settings.DEVELOPER_MODE.set(false));
        step(25, () -> {
            check(prefix + " disabling dev cancels draft", !station.gridEditor.editing() && station.geometry().equals(FurnaceLayouts.profiles(kind)[selected]), "saved geometry restored");
            check(prefix + " inventory restored", Hands.carried().isEmpty() && Minecraft.getInstance().player.getInventory().getItem(16).getCount() == 16
                    && ClientFurnaces.item(station.pos, 1).isEmpty(), "coal returned");
            check(prefix + " restore profiles", FurnaceLayouts.save(kind, original, selected), "original settings restored");
            Settings.DEVELOPER_MODE.set(originalDev); pointAside();
        });
        step(25, () -> {});
    }

    /** Real scene input against edited spots, followed by the station's normal recipe walkthrough. */
    private static Runnable surfaceEditorScript() {
        SurfaceStation<?> station = (SurfaceStation<?>) Scene.station;
        StationKind kind = station.kind(), other = kind == StationKind.SMITHING ? StationKind.CARTOGRAPHY : StationKind.SMITHING;
        String prefix = kind == StationKind.SMITHING ? "sx" : "mx";
        SurfaceAppearance[] original = SurfaceLayouts.profiles(kind), untouched = SurfaceLayouts.profiles(other);
        int selected = SurfaceLayouts.selected(kind);
        boolean dev = Settings.developerMode();
        int[] stackCount = {0}, guiScale = {0};
        step(2, () -> {
            Settings.DEVELOPER_MODE.set(false);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            double[] at = station.gridEditor.togglePoint(); pointGui(at[0], at[1]); screenClick(0);
            check(prefix + " developer gate", !station.gridEditor.editing() && !station.gridEditor.over(at[0], at[1]), "G and icon unavailable");
            SurfaceAppearance moved = SurfaceAppearance.VANILLA.with(0, SurfaceAppearance.Region.VANILLA.with(0, 1).with(1, 1).with(5, 1));
            Vec3 p = moved.point(kind, Vec3.ZERO, Direction.SOUTH, 0);
            double expectedX = kind == StationKind.SMITHING ? -0.26 + 1.0 / 16 : -0.04 + 1.0 / 16;
            double expectedZ = kind == StationKind.SMITHING ? -0.12 + 1.0 / 16 : 0.04 + 1.0 / 16;
            check(prefix + " independent pixel geometry", p.distanceTo(new Vec3(expectedX, 1.0 / 16, expectedZ)) < 1e-9, "independent south-facing reference=" + p);
            var malformed = new SurfaceAppearance.Region(100, -100, Double.NaN, 100, 100, Double.POSITIVE_INFINITY, Double.NaN);
            check(prefix + " malformed values bounded", malformed.x() == 6 && malformed.z() == -6 && malformed.rotation() == 0
                    && malformed.width() == 1.5 && malformed.height() == 0 && malformed.itemScale() == 1, "finite values");
            Settings.DEVELOPER_MODE.set(true);
            stackCount[0] = Minecraft.getInstance().player.getInventory().getItem(0).getCount();
            invClick(0, 0);
        });
        step(4, () -> { double[] at = station.gridEditor.togglePoint(); pointGui(at[0], at[1]); screenClick(0); });
        step(20, () -> {
            check(prefix + " editor opens", station.gridEditor.editing() && !station.showsInventory() && Minecraft.getInstance().gui.screen() instanceof SceneScreen, "same scene");
            pointAside();
            shot(prefix + "0_editor_open");
        });
        surfaceSlider(0, 0.5); surfaceSlider(1, 0.25); surfaceSlider(2, 20); surfaceSlider(3, 90);
        surfaceSlider(4, 85); surfaceSlider(5, 0.25); surfaceSlider(6, 90);
        step(18, () -> {
            check(prefix + " live preview", Math.abs(station.geometry().first().rotation() - 20) < 0.1 && Math.abs(station.surfaceScale(0) - 0.9) < 0.01, "rotation and size");
            pointAside();
            shot(prefix + "1_editor_preview");
            pointAt(station.restPoint(0)); screenClick(1);
        });
        step(4, () -> {
            check(prefix + " editing does not move ingredients", carried().getCount() == stackCount[0] && station.slot(0).isEmpty(), "carried stack unchanged");
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE));
        });
        step(25, () -> {
            check(prefix + " cancel restores saved", !station.gridEditor.editing() && station.geometry().equals(original[selected]), "scene stays active");
            invClick(0, 0);
        });
        step(4, () -> Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G)));
        step(20, () -> surfaceControl(90, 314));
        surfaceSlider(0, -0.25); surfaceSlider(1, -0.25); surfaceSlider(2, 12); surfaceSlider(3, 90);
        surfaceSlider(4, 90); surfaceSlider(5, 0.25); surfaceSlider(6, 85);
        step(18, () -> surfaceControl(150, 38));
        step(18, () -> {
            check(prefix + " profiles separate", station.geometry().equals(original[(selected + 1) % 3]), "other profile");
            surfaceControl(18, 38);
        });
        step(20, () -> {
            check(prefix + " profile draft retained", Math.abs(station.geometry().first().rotation() - 12) < 0.1, "switch back");
            Vec3 at = station.restPoint(0); pointAt(at); screenClick(0);
            pointAt(at.add(station.right().scale(0.025)));
            double[] mouse = Picking.mouseGui();
            Minecraft.getInstance().gui.screen().mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 3, 0);
            Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        });
        step(20, () -> {
            check(prefix + " world drag", station.geometry().first().x() > 0.1, "drag along working right axis");
            pointAside();
            shot(prefix + "2_editor_drag");
            surfaceControl(150, 65);
        });
        surfaceSlider(0, -0.5); surfaceSlider(1, 0.25); surfaceSlider(2, -12); surfaceSlider(5, 0.5); surfaceSlider(6, 90);
        if (kind == StationKind.SMITHING) {
            step(5, () -> surfaceControl(150, 65));
            surfaceSlider(0, -0.25); surfaceSlider(1, 0.25); surfaceSlider(2, -20); surfaceSlider(5, 0.25); surfaceSlider(6, 85);
        }
        step(18, () -> {
            check(prefix + " zones independent", station.geometry().first().rotation() > 11.9 && station.geometry().second().rotation() < -11.9, "independent rotation and heights");
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_W));
            check(prefix + " movement blocked", Scene.phase == Scene.Phase.ACTIVE && station.gridEditor.editing(), "still editing");
            guiScale[0] = Minecraft.getInstance().options.guiScale().get();
            Minecraft.getInstance().options.guiScale().set(3); Minecraft.getInstance().resizeGui();
        });
        step(8, () -> {
            pointAside();
            shot(prefix + "3_editor_gui3");
            Minecraft.getInstance().options.guiScale().set(guiScale[0]); Minecraft.getInstance().resizeGui();
        });
        step(8, () -> surfaceControl(40, 343));
        step(25, () -> {
            check(prefix + " saved JSON", !station.gridEditor.editing() && java.nio.file.Files.exists(SurfaceLayouts.path())
                    && SurfaceLayouts.profiles(kind)[selected].first().rotation() == 12, "local profiles written");
            check(prefix + " station types independent", java.util.Arrays.equals(untouched, SurfaceLayouts.profiles(other)), "other station untouched");
            Settings.DEVELOPER_MODE.set(false);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            check(prefix + " saved geometry with dev off", !station.gridEditor.editing() && station.geometry().first().rotation() > 11.9, "saved layout remains active");
        });
        for (int i = 0; i < SurfaceAppearance.count(kind); i++) {
            int spot = i;
            step(3, () -> pointAt(station.restPoint(spot)));
            step(4, () -> check(prefix + " edited picking " + spot, station.surfaceSlot(false) == spot, "raised rotated contact plane"));
        }
        step(3, () -> { Settings.DEVELOPER_MODE.set(true); Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G)); });
        step(18, () -> surfaceControl(90, 314));
        step(25, () -> { check(prefix + " reset preview", station.geometry().equals(SurfaceAppearance.VANILLA), "vanilla draft"); Settings.DEVELOPER_MODE.set(false); });
        step(25, () -> {
            check(prefix + " disabling cancels draft", !station.gridEditor.editing() && station.geometry().first().rotation() > 11.9, "saved layout restored");
            check(prefix + " inventory restored", carried().isEmpty() && Minecraft.getInstance().player.getInventory().getItem(0).getCount() == stackCount[0], "no items created or lost");
            pointAside();
        });
        return () -> {
            check(prefix + " restore profiles", SurfaceLayouts.save(kind, original, selected), "original profiles restored");
            Settings.DEVELOPER_MODE.set(dev);
        };
    }

    /** Input, persistence and real operations exercise the same edited geometry. */
    private static Runnable spatialEditorScript() {
        SpatialTarget target = (SpatialTarget) Scene.station;
        ClientStation station = target.layoutStation();
        StationKind kind = station.kind();
        SpatialEditor editor = target.layoutEditor();
        String prefix = "x" + act;
        SpatialAppearance[] original = SpatialLayouts.profiles(kind);
        StationKind otherKind = kind == StationKind.BREWING ? StationKind.CRAFTER : StationKind.BREWING;
        SpatialAppearance[] otherProfiles = SpatialLayouts.profiles(otherKind);
        int selected = SpatialLayouts.selected(kind);
        boolean dev = Settings.developerMode();
        ItemStack hand = carried().copy();
        int[] guiScale = {0};
        step(2, () -> {
            Settings.DEVELOPER_MODE.set(false);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            double[] at = editor.togglePoint(); pointGui(at[0], at[1]); screenClick(0);
            check(prefix + " developer gate", !editor.editing() && !editor.over(at[0], at[1]), "G and gear unavailable");
            var flat = SpatialAppearance.Frame.of(StationKind.CRAFTER, BlockPos.ZERO, Direction.SOUTH, Direction.UP);
            var front = SpatialAppearance.Frame.of(StationKind.CRAFTER, BlockPos.ZERO, Direction.SOUTH, Direction.SOUTH);
            check(prefix + " independent flat geometry", SpatialAppearance.VANILLA.point(flat, 0).distanceTo(new Vec3(5 / 16.0, 1, 5 / 16.0)) < 1e-9, "texture reference");
            check(prefix + " independent upright geometry", SpatialAppearance.VANILLA.point(front, 0).distanceTo(new Vec3(5 / 16.0, 11 / 16.0, 1)) < 1e-9, "texture reference");
            var invalid = new SpatialAppearance(null, java.util.Arrays.asList((SurfaceAppearance.Region) null));
            check(prefix + " malformed profile normalized", invalid.equals(SpatialAppearance.VANILLA), "missing slots restored");
            if (kind == StationKind.LOOM) {
                var loomFrame = SpatialAppearance.Frame.of(kind, BlockPos.ZERO, Direction.SOUTH, Direction.UP);
                check(prefix + " original loom corners", SpatialAppearance.VANILLA.point(loomFrame, 1).distanceTo(new Vec3(0.87, 1, 0.84)) < 1e-9
                        && SpatialAppearance.VANILLA.point(loomFrame, 2).distanceTo(new Vec3(0.13, 1, 0.84)) < 1e-9, "original corner references");
                var lowered = SpatialAppearance.VANILLA.with(0, SurfaceAppearance.Region.VANILLA.with(5, -1));
                check(prefix + " cloth can move down", Math.abs(lowered.point(loomFrame, 0).y - 15 / 16.0) < 1e-9, "lower model surfaces supported");
            } else if (kind == StationKind.ENCHANTING) {
                var enchantFrame = SpatialAppearance.Frame.of(kind, BlockPos.ZERO, Direction.SOUTH, Direction.UP);
                check(prefix + " original enchanting positions", SpatialAppearance.VANILLA.point(enchantFrame, 0).distanceTo(new Vec3(0.5, 1.37, 0.5)) < 1e-9
                        && SpatialAppearance.VANILLA.point(enchantFrame, 1).distanceTo(new Vec3(0.94, 1.02, 0.68)) < 1e-9
                        && SpatialAppearance.VANILLA.point(enchantFrame, 3).distanceTo(new Vec3(0.5, 1.67, 0.38)) < 1e-9, "original item, socket and crown");
            }
            Settings.DEVELOPER_MODE.set(true);
        });
        step(5, () -> { pointAside(); shot(prefix + "0_gear"); });
        step(2, () -> { double[] gear = editor.togglePoint(); pointGui(gear[0], gear[1]); screenClick(0); });
        step(22, () -> {
            check(prefix + " editor opens", editor.editing() && !station.showsInventory(), "same scene");
            pointAside(); shot(prefix + "0_editor_open");
        });
        spatialSlider(0, 0.25); spatialSlider(1, -0.25); spatialSlider(2, 5);
        spatialSlider(3, 95); spatialSlider(4, 95); spatialSlider(5, 0.25); spatialSlider(6, 90);
        step(22, () -> {
            check(prefix + " whole layout preview", Math.abs(target.geometry().group().rotation() - 5) < 0.1, "smooth transform");
            pointAside(); shot(prefix + "1_editor_preview");
            pointAt(target.layoutPoint(0)); screenClick(1);
            check(prefix + " preview leaves items intact", ItemStack.matches(hand, carried()), "no menu click");
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE));
        });
        step(25, () -> {
            check(prefix + " cancel restores saved", !editor.editing() && target.geometry().equals(original[selected]), "still at station");
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
        });
        step(20, () -> spatialControl(90, 314));
        spatialSlider(0, 0.125); spatialSlider(1, -0.125); spatialSlider(2, 3);
        spatialSlider(3, 95); spatialSlider(4, 95); spatialSlider(5, 0.125); spatialSlider(6, 95);
        step(15, () -> spatialControl(150, 65));
        spatialSlider(0, 0.25); spatialSlider(1, 0.125); spatialSlider(2, -8); spatialSlider(6, 90);
        step(20, () -> {
            check(prefix + " slots independent", target.geometry().region(0).rotation() < -7.9 && target.geometry().region(1).rotation() == 0, "only first slot changed");
            Vec3 at = target.layoutPoint(0); pointAt(at); screenClick(0);
            pointAt(at.add(target.layoutFrame().across().scale(0.0125)));
            double[] mouse = Picking.mouseGui();
            Minecraft.getInstance().gui.screen().mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 3, 0);
            Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        });
        step(20, () -> {
            check(prefix + " direct world drag", target.geometry().region(0).x() > 0.3, "dragged first spot");
            pointAside(); shot(prefix + "2_editor_drag");
        });
        if (kind == StationKind.BREWING) {
            step(3, () -> {
                Vec3 at = target.layoutPoint(3); pointAt(at); screenClick(0);
                pointAt(at.add(0, 0.0125, 0));
                double[] mouse = Picking.mouseGui();
                Minecraft.getInstance().gui.screen().mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 0, -3);
                Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
            });
            step(20, () -> check(prefix + " bowl drag at eye level", target.geometry().region(3).height() > 0.1, "raised the bowl directly"));
        }
        if (kind == StationKind.ENCHANTING) {
            step(3, () -> {
                Vec3 at = target.layoutPoint(4); pointAt(at); screenClick(0);
                pointAt(at.add(0, 0.0125, 0)); double[] mouse = Picking.mouseGui();
                Minecraft.getInstance().gui.screen().mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), 0, -3);
                Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(mouse[0], mouse[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
            });
            step(20, () -> {
                check(prefix + " floating offer drag", target.geometry().region(4).height() > 0.1 && target.geometry().region(1).height() == 0, "offer moves independently");
                shot(prefix + "2b_offer_drag");
            });
        }
        step(3, () -> spatialControl(150, 38));
        step(20, () -> {
            check(prefix + " profiles separate", target.geometry().equals(original[(selected + 1) % 3]), "other profile"); spatialControl(18, 38);
        });
        step(20, () -> {
            check(prefix + " draft retained", target.geometry().region(0).rotation() < -7.9, "back to edited profile");
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_W));
            check(prefix + " movement blocked", editor.editing() && Scene.phase == Scene.Phase.ACTIVE, "no accidental exit");
            guiScale[0] = Minecraft.getInstance().options.guiScale().get();
            Minecraft.getInstance().options.guiScale().set(3); Minecraft.getInstance().resizeGui();
        });
        step(12, () -> {
            pointAside(); shot(prefix + "3_editor_gui3");
            Minecraft.getInstance().options.guiScale().set(guiScale[0]); Minecraft.getInstance().resizeGui();
        });
        step(12, () -> spatialControl(40, 343));
        step(25, () -> {
            check(prefix + " saved JSON", !editor.editing() && java.nio.file.Files.exists(SpatialLayouts.path())
                    && SpatialLayouts.profiles(kind)[selected].region(0).rotation() == -8, "saved local profile");
            check(prefix + " station profiles independent", java.util.Arrays.equals(otherProfiles, SpatialLayouts.profiles(otherKind)), "other type untouched");
            if (kind == StationKind.CHISELED_BOOKSHELF) {
                var mc = Minecraft.getInstance();
                var state = mc.level.getBlockState(station.pos);
                //? if >=26.1 {
                var model = mc.getModelManager().getBlockStateModelSet().get(state);
                //?} else if <26.1 {
                /*var model = mc.getBlockRenderer().getBlockModel(state);
                *///?}

                boolean occupiedPicture = false;
                for (Direction face : new Direction[]{null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN})
                    occupiedPicture |= modelParts(model).stream().flatMap(part -> part.getQuads(face).stream())
                            //? if >=26.1 {
                            .anyMatch(q -> q.materialInfo().sprite().contents().name().getPath().contains("chiseled_bookshelf_occupied"));
                            //?} else if <26.1 {
                            /*.anyMatch(q -> q.sprite().contents().name().getPath().contains("chiseled_bookshelf_occupied"));
                            *///?}
                check(prefix + " original spines hidden", !occupiedPicture, "real block state untouched");
                var slotted = ShelfBooks.inSlot(station.pos, station.sideDirection(), 0, 0);
                check(prefix + " book and hand contact agree", slotted.at(0, 0, OpenBook.STACK / 2).distanceTo(target.layoutPoint(0)) < 1e-8, "shared spine");
            }
            Settings.DEVELOPER_MODE.set(false);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            check(prefix + " saved with developer off", !editor.editing() && target.geometry().region(0).rotation() < -7.9, "geometry still active");
        });
        step(2, () -> check(prefix + " settings profile switch", LayoutProfiles.select(kind, (selected + 1) % 3), "saved selection changed"));
        step(25, () -> {
            check(prefix + " settings profile applies", target.geometry().equals(original[(selected + 1) % 3]), "actual world geometry changed");
            check(prefix + " settings preserve geometry", SpatialLayouts.profiles(kind)[selected].region(0).rotation() == -8, "edited profile retained");
            check(prefix + " settings restore selection", LayoutProfiles.select(kind, selected), "same profiles as the scene editor");
        });
        step(25, () -> check(prefix + " selected geometry returns", target.geometry().region(0).rotation() == -8, "smoothly restored"));
        for (int i = 0; i < SpatialAppearance.count(kind); i++) {
            int slot = i;
            step(3, () -> pointAt(target.layoutPoint(slot)));
            step(5, () -> {
                check(prefix + " edited picking " + slot, target.layoutPick() == slot, "surface contact");
                if (kind == StationKind.CHISELED_BOOKSHELF)
                    check(prefix + " initial use picking " + slot, ShelfStation.slotAt(station.pos, station.sideDirection(), target.layoutPoint(slot)) == slot,
                            "initial use and vanilla fallback follow layout");
            });
        }
        step(3, () -> { Settings.DEVELOPER_MODE.set(true); Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G)); });
        step(20, () -> spatialControl(90, 314));
        step(25, () -> {
            check(prefix + " reset preview", target.geometry().equals(SpatialAppearance.VANILLA), "vanilla draft");
            Settings.DEVELOPER_MODE.set(false);
        });
        step(25, () -> {
            check(prefix + " disabling cancels draft", !editor.editing() && target.geometry().region(0).rotation() < -7.9, "saved profile restored");
            pointAside();
        });
        return () -> {
            check(prefix + " restore profiles", SpatialLayouts.save(kind, original, selected), "original profiles restored");
            Settings.DEVELOPER_MODE.set(dev);
        };
    }
    private static void spatialControl(double x, double y) {
        SpatialEditor editor = ((SpatialTarget) Scene.station).layoutEditor();
        double[] at = editor.controlPoint(x, y); pointGui(at[0], at[1]); screenClick(0);
        Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
    }
    private static void spatialSlider(int row, double value) {
        step(3, () -> {
            SpatialEditor editor = ((SpatialTarget) Scene.station).layoutEditor();
            double[] at = editor.sliderPoint(row, value); pointGui(at[0], at[1]); screenClick(0);
            Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        });
    }

    private static void surfaceControl(double x, double y) {
        SurfaceStation<?> station = (SurfaceStation<?>) Scene.station;
        double[] at = station.gridEditor.controlPoint(x, y); pointGui(at[0], at[1]); screenClick(0);
        Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
    }
    private static void surfaceSlider(int row, double value) {
        step(3, () -> {
            SurfaceStation<?> station = (SurfaceStation<?>) Scene.station;
            double[] at = station.gridEditor.sliderPoint(row, value); pointGui(at[0], at[1]); screenClick(0);
            Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        });
    }

    private static void furnaceEditorControl(double x, double y) {
        double[] at = furnace().gridEditor.controlPoint(x, y); pointGui(at[0], at[1]); screenClick(0);
        Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
    }
    private static void furnaceEditorSliderNow(int row, double value) {
        double[] at = furnace().gridEditor.sliderPoint(row, value); pointGui(at[0], at[1]); screenClick(0);
        Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
    }
    private static void furnaceEditorSlider(int row, double value) { step(3, () -> furnaceEditorSliderNow(row, value)); }

    private static void developerOptionsScript() {
        step(8, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.gui.setScreen(new SettingsScreen(null));
        });
        step(15, () -> {
            check("advanced settings tab opens", clickSettingsSection(Minecraft.getInstance().gui.screen(),
                    Component.translatable("cinematic.settings.tab.advanced").getString()), "tab button");
            if (Minecraft.getInstance().gui.screen() instanceof SettingsScreen settings) settings.showDeveloper();
        });
        step(18, () -> {
            verifyProfileControls();
            shot("pz_developer_settings");
            check("developer mode defaults off", !Settings.DEVELOPER_MODE.getDefault(), "editor access is opt-in");
        });
        //? if >=26.2 {
        step(3, () -> Minecraft.getInstance().gui.setScreen(null));
        //?} else if <26.2 {
        /*step(3, () -> Minecraft.getInstance().setScreen(null));
        *///?}
    }

    private static void profileWidgets(net.minecraft.client.gui.components.events.GuiEventListener node, List<Object> result) {
        if (node instanceof net.minecraft.client.gui.components.CycleButton<?> cycle && cycle.getValue() instanceof Integer
                || node.getClass().getName().endsWith("IntegerSliderEntry")) result.add(node);
        if (node instanceof net.minecraft.client.gui.components.events.ContainerEventHandler parent)
            for (var child : parent.children()) profileWidgets(child, result);
    }
    private static void verifyProfileControls() {
        List<Object> selectors = new ArrayList<>(); profileWidgets(Minecraft.getInstance().gui.screen(), selectors);
        // (an addon station has no profiles of its own)
        int stations = StationKind.values().length - 1;
        check("settings have a profile for every station", selectors.size() == stations, "selectors=" + selectors.size());
        if (selectors.size() != stations) return;
        int selected = LayoutProfiles.selected(StationKind.TABLE), next = (selected + 1) % 3;
        Object first = selectors.getFirst();
        try {
            if (first instanceof net.minecraft.client.gui.components.CycleButton<?> cycle) {
                cycle.onPress(testKey(InputConstants.KEY_RETURN));
                check("profile choice waits for Done", LayoutProfiles.selected(StationKind.TABLE) == selected && cycle.getValue().equals(next),
                        "cancel does not write a profile");
                while (!cycle.getValue().equals(selected)) cycle.onPress(testKey(InputConstants.KEY_RETURN));
            } else {
                first.getClass().getMethod("setValue", int.class).invoke(first, next + 1);
                check("Cloth unsaved profile stays local", LayoutProfiles.selected(StationKind.TABLE) == selected, "cancel does not write a profile");
                first.getClass().getMethod("save").invoke(first);
                check("Cloth profile slider saves", LayoutProfiles.selected(StationKind.TABLE) == next, "shared JSON selection");
                first.getClass().getMethod("setValue", int.class).invoke(first, selected + 1);
                first.getClass().getMethod("save").invoke(first);
            }
            check("settings profile restored", LayoutProfiles.selected(StationKind.TABLE) == selected, "test preserves selection");
            int anvilProfile = LayoutProfiles.selected(StationKind.ANVIL);
            check("all station profile files writable", LayoutProfiles.select(StationKind.ANVIL, (anvilProfile + 1) % 3)
                    && LayoutProfiles.select(StationKind.ANVIL, anvilProfile), "format migration and selection saved");
        } catch (ReflectiveOperationException error) { check("profile control API", false, error.toString()); }
    }

    /** NeoForge pairs a section label with an Edit button; Cloth exposes a named tab directly. */
    private static boolean clickSettingsSection(net.minecraft.client.gui.components.events.GuiEventListener node, String label) {
        net.minecraft.client.gui.components.AbstractWidget target = null;
        if (node instanceof net.minecraft.client.gui.components.AbstractButton button && button.getMessage().getString().equals(label)) target = button;
        if (target == null && node instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) {
            for (var child : container.children()) if (clickSettingsSection(child, label)) return true;
            boolean named = container.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.AbstractWidget widget
                    && widget.getMessage().getString().contains(label));
            if (named) for (var child : container.children()) if (child instanceof net.minecraft.client.gui.components.Button button) { target = button; break; }
        }
        if (target == null) return false;
        double x = target.getX() + target.getWidth() / 2.0, y = target.getY() + target.getHeight() / 2.0;
        Minecraft.getInstance().gui.screen().mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)), false);
        Minecraft.getInstance().gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
        return true;
    }

    private static void furnaceScript() {
        furnaceEditorScript();
        shot("35_active");
        step(4, () -> inventoryClick(41, 0));                // coal in hand
        step(3, () -> pointZone(FURNACE, FurnaceZones.FUEL));
        step(12, () -> shot("36_carry_fuel"));
        step(2, () -> clickZone(FurnaceZones.FUEL, 0));      // all of it into the fire box
        step(8, () -> shot("37_fueled"));
        step(4, () -> inventoryClick(42, 0));                // raw iron
        step(3, () -> pointZone(FURNACE, FurnaceZones.INPUT));
        step(12, () -> shot("38_carry_ore"));
        step(2, () -> clickZone(FurnaceZones.INPUT, 1));
        step(6, () -> clickZone(FurnaceZones.INPUT, 1));
        step(6, () -> clickZone(FurnaceZones.INPUT, 1));
        step(6, () -> inventoryClick(42, 0));                // the rest back
        step(4, () -> pointLedge(FURNACE));
        step(30, () -> shot("39_lit"));
        step(2, () -> pointZone(FURNACE, FurnaceZones.INPUT));
        step(8, () -> shot("40_input_tooltip"));
        step(2, () -> pointLedge(FURNACE));
        step(380, () -> shot("42_smelted"));
        step(2, () -> pointZone(FURNACE, FurnaceZones.RESULT));
        step(10, () -> shot("43_result_hover"));
        step(2, () -> clickZone(FurnaceZones.RESULT, 0));
        step(10, () -> shot("44_ingot_in_hand"));
        step(2, () -> inventoryClick(43, 0));                // the ingots into hotbar slot 8
        step(4, () -> inventoryClick(40, 0));                // iron ingots are no fuel
        step(3, () -> pointZone(FURNACE, FurnaceZones.FUEL));
        step(10, () -> shot("45_not_fuel"));
        step(2, () -> clickZone(FurnaceZones.FUEL, 0));      // refused, stays in hand
        step(4, () -> inventoryClick(40, 0));
        step(4, () -> pointLedge(FURNACE));
        step(6, () -> report("furnace before leaving"));
        step(2, Scene::leave);
        step(50, () -> shot("39_after"));
        nextAct("4", new Vec3(2.5, -60, 5.4), 180f, SMOKER, new Vec3(0.5, 0.6, 1.0), Direction.SOUTH);
    }

    private static void smokerScript() {
        furnaceEditorScript();
        shot("45_active");
        step(4, () -> inventoryClick(9, 0));                 // a stack of coal
        step(3, () -> pointZone(SMOKER, FurnaceZones.FUEL));
        step(12, () -> shot("46_carry_fuel"));
        step(2, () -> clickZone(FurnaceZones.FUEL, 0));
        step(6, () -> inventoryClick(11, 0));                // raw beef
        step(3, () -> pointZone(SMOKER, FurnaceZones.INPUT));
        step(12, () -> shot("47_carry_food"));
        step(2, () -> clickZone(FurnaceZones.INPUT, 1));
        step(6, () -> clickZone(FurnaceZones.INPUT, 1));
        step(6, () -> inventoryClick(11, 0));
        step(4, () -> pointLedge(SMOKER));
        step(40, () -> shot("48_lit"));
        step(2, () -> pointZone(SMOKER, FurnaceZones.INPUT));
        step(8, () -> shot("49_input_tooltip"));
        step(2, () -> pointLedge(SMOKER));
        step(180, () -> shot("50_smoked"));
        step(2, () -> pointZone(SMOKER, FurnaceZones.RESULT));
        step(10, () -> shot("51_result_hover"));
        step(2, () -> report("smoker before taking"));
        step(2, () -> {
            pointZone(SMOKER, FurnaceZones.RESULT);
            FurnaceStation station = Scene.station(FurnaceStation.class);
            if (station != null) {
                station.click(0, true);                      // shift-click: straight into the inventory
            }
        });
        step(10, () -> report("smoker after taking"));
        step(2, Scene::leave);
        step(50, () -> shot("49_after"));
        nextAct("5", new Vec3(4.5, -60, 5.4), 180f, BLAST, new Vec3(0.5, 0.6, 1.0), Direction.SOUTH);
    }

    private static void blastScript() {
        furnaceEditorScript();
        shot("55_active");
        step(4, () -> inventoryClick(16, 0));                // coal
        step(3, () -> pointZone(BLAST, FurnaceZones.FUEL));
        step(12, () -> shot("56_carry_fuel"));
        step(2, () -> clickZone(FurnaceZones.FUEL, 0));
        step(6, () -> inventoryClick(15, 0));                // raw iron, charged from the top
        step(3, () -> pointZone(BLAST, FurnaceZones.INPUT));
        step(12, () -> shot("57_carry_ore"));
        step(2, () -> clickZone(FurnaceZones.INPUT, 0));
        step(8, () -> shot("58_charged"));
        step(4, () -> pointZone(BLAST, FurnaceZones.RESULT));
        step(40, () -> shot("59_lit"));
        step(170, () -> shot("60_blasted"));
        step(4, () -> shot("61_result_hover"));
        step(2, () -> report("blast furnace before taking"));
        step(2, () -> pointZone(BLAST, FurnaceZones.RESULT));
        step(2, () -> clickZone(FurnaceZones.RESULT, 0));
        step(10, () -> shot("62_ingots_in_hand"));
        step(2, () -> inventoryClick(17, 0));
        step(6, () -> report("blast furnace after taking"));
        step(2, Scene::leave);
        step(50, () -> shot("59_after"));
        nextAct("6", new Vec3(-4.5, -60, 11.6), 180f, ENCHANT, new Vec3(0.5, 0.75, 0.6), Direction.UP);
    }

    private static void pointZone(BlockPos pos, int slot) {
        StationKind kind = StationKind.of(Minecraft.getInstance().level.getBlockState(pos));
        FurnaceLayout layout = FurnaceLayout.of(kind);
        FurnaceLayout.Zone zone = layout.zone(slot);
        double[] c = layout.center(slot);
        pointAt(FurnaceZones.point(pos, Direction.SOUTH, zone.face(), c[0], c[1], 0));
    }

    private static void pointLedge(BlockPos pos) {
        pointAt(FurnaceZones.point(pos, Direction.SOUTH, 0.5, 0.45, 0));
    }

    private static void clickZone(int zone, int button) {
        FurnaceStation station = Scene.station(FurnaceStation.class);
        if (station == null || station.hoverZone != zone) {
            REPORT.add("cursor missed furnace zone " + zone + ": hover=" + (station == null ? "-" : station.hoverZone));
        }
        screenClick(button);
    }

    // ---- the enchanting table -----------------------------------------------------------------------

    private static void enchantScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("65_active");
        step(2, Showcase::pointAside);
        step(8, () -> {
            shot("65a_lapis_socket_empty");
            Minecraft mc = Minecraft.getInstance();
            double[] at = Picking.toGui(enchant().corner());
            double width = mc.getWindow().getGuiScaledWidth();
            double height = mc.getWindow().getGuiScaledHeight();
            boolean visible = at != null && at[0] > width * 0.1 && at[0] < width * 0.9
                    && at[1] > height * 0.15 && at[1] < height * 0.85;
            check("lapis socket stays in the usable frame", visible,
                    at == null ? "behind camera" : String.format("screen=%.3f,%.3f", at[0] / width, at[1] / height));
        });
        step(2, () -> pointAt(enchant().corner()));
        step(6, () -> {
            shot("65b_lapis_socket_hover");
            check("empty lapis socket is reachable", enchant().target == EnchantStation.Target.LAPIS && enchant().lapis() == 0,
                    "target=" + enchant().target + " lapis=" + enchant().lapis());
        });
        step(4, () -> invClickItem(Items.DIAMOND_SWORD));    // the diamond sword
        step(3, () -> pointAt(EnchantFx.top(ENCHANT).add(0, 0, 0.1)));
        step(12, () -> shot("66_carry_sword"));
        step(2, () -> clickEnchant(EnchantStation.Target.SURFACE, 0));
        step(20, () -> shot("67_sword_floats"));
        step(2, () -> invClickItem(Items.LAPIS_LAZULI));     // lapis
        step(3, () -> pointAt(enchant().corner()));
        step(10, () -> shot("68_carry_lapis"));
        step(2, () -> clickEnchant(EnchantStation.Target.LAPIS, 0));
        step(8, () -> {
            shot("68a_lapis_socket_filled");
            checkLapisCount("lapis placed in the visible socket", 12);
        });
        step(4, Showcase::pointAside);
        step(16, () -> shot("69_offers"));
        step(2, () -> pointAt(enchant().sigilPos(0)));
        step(10, () -> shot("70_hover_first"));
        step(2, () -> pointAt(enchant().sigilPos(2)));
        step(10, () -> shot("71_hover_third"));
        step(2, () -> report("enchanting before the rite"));
        step(2, () -> clickEnchant(EnchantStation.Target.OFFER, 0));      // the third, strongest offer
        step(2, () -> {
            boolean dev = Settings.developerMode(); Settings.DEVELOPER_MODE.set(true);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            check("enchant editor blocked during rite", !enchant().gridEditor.editing() && enchant().busy(), "ritual continues");
            Settings.DEVELOPER_MODE.set(dev); shot("72_rite_010");
        });
        step(3, () -> shot("72_rite_025"));
        step(3, () -> shot("72_rite_040"));
        step(3, () -> shot("72_rite_055"));
        step(3, () -> shot("72_rite_070"));
        step(6, () -> shot("72_rite_100"));
        step(10, () -> shot("72_rite_150"));
        step(20, () -> shot("73_enchanted"));
        step(2, () -> {
            checkLapisCount("lapis consumed by the third offer", 9);
            check("edited enchanting produced an enchanted sword", enchant().item().is(Items.DIAMOND_SWORD) && enchant().item().isEnchanted(), "vanilla enchantments applied");
        });
        step(2, () -> pointAt(enchant().itemPos()));
        step(8, () -> shot("74_item_hover"));
        step(2, () -> report("enchanting after the rite"));
        step(2, () -> clickEnchant(EnchantStation.Target.ITEM, 0));
        step(8, () -> shot("75_sword_in_hand"));
        step(2, () -> invClick(18, 0));
        step(6, () -> report("enchanting before leaving"));
        step(2, restoreLayout);
        step(25, Showcase::pointAside);
        step(2, Scene::leave);
        step(50, () -> shot("69_after"));
        step(2, () -> report("enchanting after leaving"));
        smithyAct("a", ANVIL);
    }

    private static EnchantStation enchant() {
        return Scene.station(EnchantStation.class);
    }

    private static void checkLapisCount(String name, int expected) {
        check(name + " client", enchant() != null && enchant().lapis() == expected,
                "lapis=" + (enchant() == null ? "-" : enchant().lapis()) + " expected=" + expected);
        server(p -> {
            int count = p.containerMenu instanceof EnchantmentMenu menu ? menu.getSlot(1).getItem().getCount() : -1;
            check(name + " server", count == expected, "lapis=" + count + " expected=" + expected);
        });
    }

    private static void clickEnchant(EnchantStation.Target expected, int button) {
        EnchantStation station = enchant();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed enchanting target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    // ---- the smithy ----------------------------------------------------------------------------------

    /** Walks up to one of the smithy's blocks from the south and uses it. */
    private static void smithyAct(String next, BlockPos station) {
        double y = station.equals(STONECUTTER) ? 0.56 : 1.0;
        nextAct(next, new Vec3(station.getX() + 0.5, -60, station.getZ() + 3.4), 180f, station, new Vec3(0.5, y, 0.5), Direction.UP);
    }

    /** Shots through a move: {@code times} in seconds since it started. */
    private static void moveShots(String prefix, double... times) {
        double at = 0;
        for (double time : times) {
            // a step runs one tick after its delay runs out
            int ticks = (int) Math.max(2, Math.round((time - at) * 20));
            at += ticks / 20.0;
            String name = String.format("%s_%03d", prefix, Math.round(time * 100));
            step(ticks - 1, () -> shot(name));
        }
    }

    private static AnvilStation anvil() {
        return Scene.station(AnvilStation.class);
    }

    private static void clickAnvil(AnvilStation.Target expected, int button) {
        AnvilStation station = anvil();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed anvil target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    private static void anvilScript() {
        Runnable restoreLayout = spatialEditorScript();
        var model = HammerModel.model();
        //? if >=26.3 {
        var quads = model.firstLayer().quads.all();
        //?} else if <26.3 {
        /*var quads = model.firstLayer().quads;
        *///?}
        var textures = new HashSet<Identifier>();
        for (var quad : quads) {
            //? if >=26.1 {
            textures.add(quad.materialInfo().sprite().contents().name());
            //?} else if <26.1 {
            /*textures.add(quad.sprite().contents().name());
            *///?}
        }
        check("imported hammer model and textures loaded", !model.isEmpty()
                && quads.size() == 24 && textures.size() == 4
                && textures.stream().allMatch(texture -> texture.getNamespace().equals(Cinematic.MODID)
                        && texture.getPath().startsWith("item/prop/hammer_")), "faces=" + quads.size() + " textures=" + textures);
        shot("a5_active");
        step(4, () -> invClickItem(Items.IRON_PICKAXE));         // a worn pickaxe
        step(3, () -> pointAt(anvil().restPoint(0)));
        step(12, () -> shot("a6_carry_pickaxe"));
        step(2, () -> clickAnvil(AnvilStation.Target.SURFACE, 0));
        step(10, () -> shot("a7_pickaxe_laid"));
        step(2, () -> invClickItem(Items.ENCHANTED_BOOK));       // Efficiency III goes into it
        step(3, () -> pointAt(anvil().restPoint(1)));
        step(10, () -> shot("a8_carry_book"));
        step(2, () -> clickAnvil(AnvilStation.Target.SURFACE, 0));
        step(4, Showcase::pointAside);
        step(14, () -> shot("a9_pile_cost"));
        step(2, () -> pointAt(anvil().restPoint(1)));
        step(8, () -> shot("aa_pile_tooltip"));
        step(2, () -> report("anvil before forging"));
        step(2, () -> clickAnvil(AnvilStation.Target.STACK, 0));
        moveShots("ab_forge", 0.25, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.3, 1.45, 1.65, 1.85, 2.05);
        step(10, () -> shot("ac_forged"));
        step(2, () -> {
            ItemStack result = Minecraft.getInstance().player.containerMenu.getCarried();
            check("hammer forging produces enchanted pickaxe client", result.is(Items.IRON_PICKAXE) && result.isEnchanted(),
                    "result=" + result);
            server(p -> {
                ItemStack forged = p.containerMenu.getCarried();
                check("hammer forging produces enchanted pickaxe server", forged.is(Items.IRON_PICKAXE) && forged.isEnchanted()
                        && p.containerMenu.getSlot(0).getItem().isEmpty() && p.containerMenu.getSlot(1).getItem().isEmpty(),
                        "result=" + forged);
            });
        });
        step(2, () -> report("anvil after forging"));
        // the new pickaxe back on the anvil, to engrave a name
        step(2, () -> pointAt(anvil().restPoint(0)));
        step(6, () -> clickAnvil(AnvilStation.Target.SURFACE, 0));
        step(8, () -> pointAt(anvil().namePoint()));
        step(8, () -> shot("ad_name_hover"));
        step(2, () -> clickAnvil(AnvilStation.Target.NAME, 0));
        step(6, () -> shot("ae_name_field"));
        step(2, () -> SceneScreen.commitEdit("Кирка мастера"));
        step(4, Showcase::pointAside);
        step(10, () -> shot("af_renamed"));
        step(2, () -> pointAt(anvil().restPoint(0)));
        step(6, () -> clickAnvil(AnvilStation.Target.STACK, 0));
        step(46, () -> shot("ag_named"));
        step(2, () -> invClick(28, 0));
        step(6, () -> report("anvil before leaving"));
        step(2, () -> server(p -> {
            ItemStack named = p.getInventory().getItem(28);
            check("hammer engraving preserves the forged item", named.is(Items.IRON_PICKAXE) && named.isEnchanted()
                    && named.getHoverName().getString().equals("Кирка мастера"), "result=" + named.getHoverName().getString());
        }));
        //? if >=26.2 {
        if (net.neoforged.fml.ModList.get().isLoaded("no-xp-anvils")) {
            // A zero-cost repair must use the same real result slot as the book and rename operations above.
            step(2, () -> invClick(28, 0));
            step(3, () -> pointAt(anvil().restPoint(0)));
            step(4, () -> clickAnvil(AnvilStation.Target.SURFACE, 0));
            step(2, () -> invClickItem(Items.IRON_INGOT));
            step(3, () -> pointAt(anvil().restPoint(1)));
            step(4, () -> clickAnvil(AnvilStation.Target.SURFACE, 0));
            step(3, () -> pointAt(anvil().restPoint(1)));
            step(6, () -> {
                var menu = anvil().menu();
                ItemStack repaired = anvil().slot(2);
                check("no-xp anvil offers repair at zero cost", menu != null && menu.getCost() == 0
                        && repaired.is(Items.IRON_PICKAXE) && repaired.getDamageValue() < 140,
                        "cost=" + (menu == null ? -1 : menu.getCost()) + " result=" + repaired);
            });
            step(2, () -> clickAnvil(AnvilStation.Target.STACK, 0));
            moveShots("ah_repair", 0.25, 0.6, 0.9, 1.3, 1.85, 2.05);
            step(6, () -> server(p -> {
                ItemStack repaired = p.containerMenu.getCarried();
                check("no-xp anvil repairs without spending levels", repaired.is(Items.IRON_PICKAXE)
                        && repaired.getDamageValue() < 140 && p.experienceLevel == 30,
                        "result=" + repaired + " xp=" + p.experienceLevel);
            }));
            step(2, () -> invClick(29, 0));
        }
        //?}
        step(2, restoreLayout);
        step(2, Scene::leave);
        step(50, () -> shot("a9_after"));
        smithyAct("g", GRINDSTONE);
    }

    private static GrindstoneStation grindstone() {
        return Scene.station(GrindstoneStation.class);
    }

    private static void clickGrindstone(GrindstoneStation.Target expected, int button) {
        GrindstoneStation station = grindstone();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed grindstone target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    private static void grindstoneScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("g5_active");
        step(4, () -> invClickItem(Items.GOLDEN_SWORD));         // Sharpness III, Looting II
        step(3, () -> pointAt(grindstone().restPoint(0)));
        step(12, () -> shot("g6_carry_sword"));
        step(2, () -> clickGrindstone(GrindstoneStation.Target.PLACE, 0));
        step(4, Showcase::pointAside);
        step(10, () -> shot("g7_on_wheel"));
        step(2, () -> pointAt(grindstone().restPoint(0)));
        step(8, () -> shot("g8_tooltip"));
        step(2, () -> report("grindstone before grinding"));
        step(2, () -> clickGrindstone(GrindstoneStation.Target.ITEM, 0));
        moveShots("g9_grind", 0.15, 0.4, 0.62, 0.8, 1.0, 1.2, 1.4, 1.55, 1.75, 1.95, 2.15, 2.4);
        step(12, () -> shot("ga_ground"));
        step(30, () -> report("grindstone after grinding"));
        step(2, () -> invClick(29, 0));
        step(4, Showcase::pointAside);
        step(2, restoreLayout);
        step(2, Scene::leave);
        step(50, () -> shot("g9_after"));
        smithyAct("s", SMITHING);
    }

    private static SmithingStation smithing() {
        return Scene.station(SmithingStation.class);
    }

    private static void clickSmithing(SmithingStation.Target expected, int button) {
        SmithingStation station = smithing();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed smithing target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    /** Takes an item from the inventory and lays it on its spot of the smithing table. */
    private static void laySmithing(net.minecraft.world.item.Item item, int slot, String shotName, int button) {
        step(4, () -> invClickItem(item));
        step(3, () -> pointAt(smithing().restPoint(slot)));
        if (shotName != null) {
            step(10, () -> shot(shotName));
        }
        step(4, () -> clickSmithing(SmithingStation.Target.PLACE, button));
    }

    private static void smithingScript() {
        Runnable restoreLayout = surfaceEditorScript();
        shot("s5_active");
        laySmithing(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 0, "s6_carry_template", 0);
        laySmithing(Items.DIAMOND_CHESTPLATE, 1, null, 0);
        laySmithing(Items.NETHERITE_INGOT, 2, "s6_carry_ingot", 0);
        step(4, Showcase::pointAside);
        step(10, () -> shot("s7_laid"));
        step(2, () -> pointAt(smithing().restPoint(1)));
        step(8, () -> shot("s8_tooltip"));
        step(2, () -> report("smithing before the upgrade"));
        step(2, () -> clickSmithing(SmithingStation.Target.ITEM, 0));
        moveShots("s9_upgrade", 0.2, 0.4, 0.5, 0.75, 0.95, 1.15, 1.3, 1.4, 1.6, 1.9, 2.2, 2.6);
        step(10, () -> shot("sa_netherite"));
        step(2, () -> check("edited smithing produces netherite", carried().is(Items.NETHERITE_CHESTPLATE), "real vanilla result=" + carried()));
        step(2, () -> report("smithing after the upgrade"));
        // the new chestplate goes straight back with a coast trim of amethyst
        step(3, () -> pointAt(smithing().restPoint(1)));
        step(6, () -> clickSmithing(SmithingStation.Target.PLACE, 0));
        laySmithing(Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, 0, null, 0);
        laySmithing(Items.AMETHYST_SHARD, 2, null, 1);                // one shard; the other two back
        step(4, () -> invClick(30, 0));
        step(2, () -> pointAt(smithing().restPoint(1)));
        step(8, () -> shot("sb_trim_laid"));
        step(2, () -> clickSmithing(SmithingStation.Target.ITEM, 0));
        moveShots("sc_trim", 0.5, 0.95, 1.2, 1.4, 1.9);
        step(16, () -> shot("sd_trimmed"));
        step(2, () -> check("edited smithing applies trim", carried().has(DataComponents.TRIM), "real trim=" + carried()));
        step(2, () -> invClick(31, 0));
        step(6, () -> report("smithing after the trim"));
        step(2, restoreLayout);
        step(25, Showcase::pointAside);
        step(2, Scene::leave);
        step(50, () -> shot("s9_after"));
        smithyAct("c", STONECUTTER);
    }

    private static StonecutterStation stonecutter() {
        return Scene.station(StonecutterStation.class);
    }

    private static void clickStonecutter(StonecutterStation.Target expected, int button) {
        StonecutterStation station = stonecutter();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed stonecutter target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    /** Index of the shape that makes {@code item} on the stonecutter's dial, or -1. */
    private static int shape(net.minecraft.world.item.Item item) {
        Minecraft mc = Minecraft.getInstance();
        var menu = stonecutter().menu();
        for (int i = 0; menu != null && i < menu.getVisibleRecipes().entries().size(); i++) {
            if (menu.getVisibleRecipes().entries().get(i).recipe().optionDisplay().resolveForFirstStack(net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(mc.level)).is(item)) {
                return i;
            }
        }
        REPORT.add("no stonecutter shape for " + item);
        return 0;
    }

    private static void stonecutterScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("c5_active");
        step(4, () -> invClickItem(Items.STONE));
        step(3, () -> pointAt(stonecutter().restPoint(0)));
        step(10, () -> shot("c6_carry_stone"));
        step(2, () -> clickStonecutter(StonecutterStation.Target.PLACE, 0));
        step(4, Showcase::pointAside);
        step(14, () -> shot("c7_dial"));
        step(2, () -> pointAt(stonecutter().shapePoint(shape(Items.STONE_BRICKS))));
        step(8, () -> shot("c8_hover_bricks"));
        step(2, () -> clickStonecutter(StonecutterStation.Target.SHAPE, 0));
        moveShots("c9_cut", 0.1, 0.25, 0.4, 0.5, 0.6, 0.75, 0.95);
        step(8, () -> shot("ca_bricks_in_hand"));
        step(2, () -> invClick(32, 0));
        step(2, () -> pointAt(stonecutter().shapePoint(shape(Items.STONE_SLAB))));
        step(6, () -> clickStonecutter(StonecutterStation.Target.SHAPE, 0));
        step(12, () -> shot("cb_cut_slab"));
        step(14, () -> invClick(33, 0));
        step(2, () -> report("stonecutter after two cuts"));
        step(2, () -> pointAt(stonecutter().shapePoint(shape(Items.STONE_STAIRS))));
        step(6, () -> {
            StonecutterStation station = stonecutter();
            if (station == null || station.target != StonecutterStation.Target.SHAPE) {
                REPORT.add("cursor missed the stairs");
            }
            if (station != null) {
                station.click(0, true);                              // shift: the whole stack
            }
        });
        moveShots("cc_cut_all", 0.3, 0.6, 0.9, 1.3);
        step(10, () -> report("stonecutter after cutting all"));
        step(2, () -> invClickItem(Items.COBBLED_DEEPSLATE));      // many shapes: the dial turns
        step(3, () -> pointAt(stonecutter().restPoint(0)));
        step(6, () -> clickStonecutter(StonecutterStation.Target.PLACE, 0));
        step(4, Showcase::pointAside);
        step(14, () -> shot("cd_dial_many"));
        step(2, () -> {
            for (int i = 0; i < 4; i++) {
                stonecutter().scroll(-1);
            }
        });
        step(12, () -> shot("ce_dial_turned"));
        step(4, () -> report("stonecutter before leaving"));
        step(2, restoreLayout);
        step(2, Scene::leave);
        step(50, () -> shot("c9_after"));
        restock();
        smithyAct("l", LOOM);
    }

    // ---- the second workshop ---------------------------------------------------------------------------

    /** Fresh things for the second workshop in the main inventory (the first workshop's results are reported by now). */
    private static void restock() {
        step(2, () -> server(p -> {
            ServerLevel level = p.level();
            for (int i = 9; i < 36; i++) {
                p.getInventory().setItem(i, ItemStack.EMPTY);
            }
            ItemStack map = net.minecraft.world.item.MapItem.create(level, 11, 14, (byte) 0, true, false);
            // chart the land round the workshop, as walking about with the map would
            var data = net.minecraft.world.item.MapItem.getSavedData(map, level);
            for (int pass = 0; pass < 8 && data != null; pass++) {
                ((net.minecraft.world.item.MapItem) Items.FILLED_MAP).update(level, p, data);
            }
            ItemStack water = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,
                    net.minecraft.world.item.alchemy.Potions.WATER);
            //? if >=26.2 {
            ItemStack[] stock = {new ItemStack(Items.BANNER.white(), 2), new ItemStack(Items.DYE.red(), 4), map, new ItemStack(Items.PAPER, 2),
            //?} else if <26.2 {
            /*ItemStack[] stock = {new ItemStack(Items.WHITE_BANNER, 2), new ItemStack(Items.RED_DYE, 4), map, new ItemStack(Items.PAPER, 2),
            *///?}
                    new ItemStack(Items.GLASS_PANE), water.copy(), water.copy(), water.copy(), new ItemStack(Items.NETHER_WART, 2),
                    new ItemStack(Items.BLAZE_POWDER, 2), new ItemStack(Items.CHICKEN, 3), new ItemStack(Items.MUTTON, 2),
                    new ItemStack(Items.OAK_PLANKS, 8), new ItemStack(Items.GOLD_INGOT, 2), new ItemStack(Items.BEEF, 3)};
            for (int i = 0; i < stock.length; i++) {
                p.getInventory().setItem(9 + i, stock[i]);
            }
            p.inventoryMenu.broadcastChanges();
        }));
    }

    /** Puts what is in hand into the first empty inventory slot. */
    private static void stash() {
        LocalPlayer player = Minecraft.getInstance().player;
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                invClick(i, 0);
                return;
            }
        }
        REPORT.add("no empty slot to stash " + player.containerMenu.getCarried());
    }

    private static ItemStack carried() {
        return Minecraft.getInstance().player.containerMenu.getCarried();
    }

    private static LoomStation loom() {
        return Scene.station(LoomStation.class);
    }

    private static void clickLoom(LoomStation.Target expected, int button) {
        LoomStation station = loom();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed loom target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    private static void pointPattern(String asset) {
        LoomStation station = loom();
        int k = station == null ? -1 : station.patternIndex(asset);
        double[] at = k < 0 ? null : station.boardCell(k);
        if (at == null) {
            REPORT.add("pattern " + asset + " not on the board");
            return;
        }
        pointGui(at[0], at[1]);
    }

    private static void loomScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("l5_active");
        //? if >=26.2 {
        step(4, () -> invClickItem(Items.BANNER.white()));
        //?} else if <26.2 {
        /*step(4, () -> invClickItem(Items.WHITE_BANNER));
        *///?}
        step(3, () -> pointAt(loom().restPoint(0)));
        step(10, () -> shot("l6_carry_banner"));
        step(2, () -> clickLoom(LoomStation.Target.PLACE, 1));         // one banner onto the warp
        step(4, Showcase::stash);
        //? if >=26.2 {
        step(4, () -> invClickItem(Items.DYE.red()));
        //?} else if <26.2 {
        /*step(4, () -> invClickItem(Items.RED_DYE));
        *///?}
        step(6, () -> pointAt(loom().restPoint(1)));
        step(1, () -> clickLoom(LoomStation.Target.PLACE, 0));
        step(4, Showcase::pointAside);
        step(16, () -> {
            shot("l7_board"); Settings.DEVELOPER_MODE.set(true);
            double[] gear = loom().gridEditor.togglePoint(); pointGui(gear[0], gear[1]); screenClick(0);
        });
        step(22, () -> {
            check("loom gear accessible with pattern board", loom().gridEditor.editing() && loom().boardCell(0) == null,
                    "editor consumes input, patterns hidden");
            //? if >=26.2 {
            check("loom live inputs preserved", loom().slot(0).is(Items.BANNER.white()) && loom().slot(1).is(Items.DYE.red()), "banner and dye intact");
            //?} else if <26.2 {
            /*check("loom live inputs preserved", loom().slot(0).is(Items.WHITE_BANNER) && loom().slot(1).is(Items.RED_DYE), "banner and dye intact");
            *///?}
            pointAside(); shot("xl4_editor_with_cloth"); Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE));
        });
        step(25, () -> { Settings.DEVELOPER_MODE.set(false); check("loom patterns return after editor", loom().boardCell(0) != null, "board visible"); });
        step(3, () -> server(p -> p.getInventory().add(new ItemStack(Items.FLOWER_BANNER_PATTERN))));
        step(5, () -> invClickItem(Items.FLOWER_BANNER_PATTERN));
        step(3, () -> pointAt(loom().restPoint(2)));
        step(5, () -> clickLoom(LoomStation.Target.PLACE, 0));
        step(8, () -> {
            check("loom edited pattern slot accepts template", loom().slot(2).is(Items.FLOWER_BANNER_PATTERN)
                    && loom().patternIndex("minecraft:flower") >= 0, "vanilla special pattern available");
            pointAt(loom().restPoint(2));
        });
        step(5, () -> clickLoom(LoomStation.Target.ITEM, 0));
        step(5, () -> {
            check("loom template retrieved from edited slot", carried().is(Items.FLOWER_BANNER_PATTERN) && loom().slot(2).isEmpty(), "same logical slot");
            stash();
        });
        step(2, () -> loom().scroll(-1));
        step(12, () -> shot("l7b_board_scrolled"));
        step(2, () -> loom().scroll(1));
        step(12, () -> pointPattern("minecraft:cross"));
        step(10, () -> shot("l8_hover_cross"));
        step(2, () -> report("loom before weaving"));
        step(2, () -> pointPattern("minecraft:cross"));
        step(1, () -> check("loom pattern under the cursor", loom() != null && loom().target == LoomStation.Target.PATTERN,
                "target=" + (loom() == null ? "-" : loom().target)));
        step(1, () -> clickLoom(LoomStation.Target.PATTERN, 0));
        step(1, () -> {
            Settings.DEVELOPER_MODE.set(true); Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_G));
            check("loom editor blocked during weaving", !loom().gridEditor.editing() && loom().moving(), "shuttle continues");
            Settings.DEVELOPER_MODE.set(false);
        });
        moveShots("l9_weave", 0.15, 0.4, 0.7, 1.0, 1.3, 1.6, 1.9, 2.1, 2.3);
        step(12, () -> shot("la_woven"));
        step(2, () -> {
            var layers = carried().getOrDefault(net.minecraft.core.component.DataComponents.BANNER_PATTERNS,
                    net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY).layers();
            //? if >=26.2 {
            check("woven banner in hand", carried().is(Items.BANNER.white()) && layers.size() == 1
            //?} else if <26.2 {
            /*check("woven banner in hand", carried().is(Items.WHITE_BANNER) && layers.size() == 1
            *///?}
                    && layers.get(0).pattern().value().assetId().getPath().equals("cross"), "carried=" + carried() + " layers=" + layers.size());
        });
        step(2, Showcase::stash);
        step(4, () -> report("loom after weaving"));
        step(2, restoreLayout);
        step(25, Showcase::pointAside);
        step(2, Scene::leave);
        step(50, () -> shot("l9_after"));
        smithyAct("m", CARTOGRAPHY);
    }

    private static CartographyStation cartography() {
        return Scene.station(CartographyStation.class);
    }

    private static void clickCartography(CartographyStation.Target expected, int button) {
        CartographyStation station = cartography();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed cartography target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    private static void cartographyScript() {
        Runnable restoreLayout = surfaceEditorScript();
        shot("m5_active");
        step(4, () -> invClickItem(Items.FILLED_MAP));
        step(3, () -> pointAt(cartography().restPoint(0)));
        step(6, () -> clickCartography(CartographyStation.Target.PLACE, 0));
        step(4, () -> invClickItem(Items.PAPER));
        step(3, () -> pointAt(cartography().restPoint(1)));
        step(10, () -> shot("m6_carry_paper"));
        step(2, () -> clickCartography(CartographyStation.Target.PLACE, 1));
        step(4, Showcase::stash);
        step(4, Showcase::pointAside);
        step(10, () -> shot("m7_laid"));
        step(2, () -> pointAt(cartography().restPoint(0)));
        step(8, () -> shot("m8_tooltip"));
        step(2, () -> report("cartography before zooming"));
        step(2, () -> clickCartography(CartographyStation.Target.ITEM, 0));
        moveShots("m9_zoom", 0.2, 0.45, 0.7, 1.0, 1.3, 1.6, 1.9);
        step(10, () -> shot("ma_zoomed"));
        step(2, () -> check("zoomed map in hand", carried().is(Items.FILLED_MAP), "carried=" + carried()));
        step(2, Showcase::stash);
        // Copy with the edited map and addition spots before locking one of the resulting maps.
        step(8, () -> server(p -> p.getInventory().add(new ItemStack(Items.MAP))));
        step(4, () -> invClickItem(Items.FILLED_MAP));
        step(3, () -> pointAt(cartography().restPoint(0)));
        step(6, () -> clickCartography(CartographyStation.Target.PLACE, 1));
        step(4, Showcase::stash);
        step(4, () -> invClickItem(Items.MAP));
        step(3, () -> pointAt(cartography().restPoint(1)));
        step(6, () -> clickCartography(CartographyStation.Target.PLACE, 0));
        step(4, () -> pointAt(cartography().restPoint(0)));
        step(6, () -> clickCartography(CartographyStation.Target.ITEM, 0));
        moveShots("mc_copy", 0.3, 0.6, 1.0, 1.2, 1.4, 1.7);
        step(10, () -> check("copied maps in hand", carried().is(Items.FILLED_MAP) && carried().getCount() == 2,
                "carried=" + carried()));
        step(2, Showcase::stash);
        // lock the new map under glass
        step(4, () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            for (int i = 35; i >= 9; i--) {
                if (player.getInventory().getItem(i).is(Items.FILLED_MAP)) {
                    invClick(i, 0);
                    return;
                }
            }
        });
        step(3, () -> pointAt(cartography().restPoint(0)));
        step(6, () -> clickCartography(CartographyStation.Target.PLACE, 0));
        step(4, () -> invClickItem(Items.GLASS_PANE));
        step(3, () -> pointAt(cartography().restPoint(1)));
        step(6, () -> clickCartography(CartographyStation.Target.PLACE, 0));
        step(4, () -> pointAt(cartography().restPoint(0)));
        step(6, () -> clickCartography(CartographyStation.Target.ITEM, 0));
        moveShots("mb_lock", 0.3, 0.6, 1.0, 1.2, 1.4, 1.7);
        step(10, () -> server(p -> {
            ItemStack held = p.containerMenu.getCarried();
            var data = net.minecraft.world.item.MapItem.getSavedData(held, p.level());
            check("locked map in hand", held.is(Items.FILLED_MAP) && data != null && data.locked, "carried=" + held);
        }));
        step(2, Showcase::stash);
        step(4, () -> report("cartography before leaving"));
        step(2, restoreLayout);
        step(25, Showcase::pointAside);
        step(2, Scene::leave);
        step(50, () -> shot("m9_after"));
        smithyAct("b", BREWING);
    }

    private static BrewingStation brewing() {
        return Scene.station(BrewingStation.class);
    }

    private static void clickBrewing(BrewingStation.Target expected, int button) {
        BrewingStation station = brewing();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed brewing target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    private static void brewingScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("b5_active");
        for (int i = 0; i < 3; i++) {
            int bottle = i;
            step(4, () -> invClickItem(Items.POTION));
            step(3, () -> pointAt(brewing().handle(bottle)));
            if (i == 0) {
                step(10, () -> shot("b6_carry_bottle"));
            }
            step(6, () -> clickBrewing(BrewingStation.Target.PLACE, 0));
        }
        step(4, () -> invClickItem(Items.BLAZE_POWDER));
        step(3, () -> pointAt(brewing().handle(BrewingStation.FUEL)));
        step(6, () -> clickBrewing(BrewingStation.Target.PLACE, 0));
        step(4, () -> invClickItem(Items.NETHER_WART));
        step(3, () -> pointAt(brewing().handle(BrewingStation.INGREDIENT)));
        step(10, () -> shot("b6_carry_wart"));
        step(2, () -> clickBrewing(BrewingStation.Target.PLACE, 1));
        step(4, Showcase::stash);
        step(4, Showcase::pointAside);
        step(30, () -> shot("b7_brewing_start"));
        step(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.level.block.state.BlockState empty = Blocks.BREWING_STAND.defaultBlockState();
            net.minecraft.world.level.block.state.BlockState full = empty;
            for (var bottle : net.minecraft.world.level.block.BrewingStandBlock.HAS_BOTTLE) {
                full = full.setValue(bottle, true);
            }
            // the stand's multipart model picks its parts by the state: count what it would draw for each
            int withBottles = 0, without = 0;
            for (Direction face : new Direction[]{null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}) {
                //? if >=26.1 {
                withBottles += modelParts(mc.getModelManager().getBlockStateModelSet().get(full)).stream().mapToInt(part -> part.getQuads(face).size()).sum();
                without += modelParts(mc.getModelManager().getBlockStateModelSet().get(empty)).stream().mapToInt(part -> part.getQuads(face).size()).sum();
                //?} else if <26.1 {
                /*withBottles += modelParts(mc.getBlockRenderer().getBlockModel(full)).stream().mapToInt(part -> part.getQuads(face).size()).sum();
                without += modelParts(mc.getBlockRenderer().getBlockModel(empty)).stream().mapToInt(part -> part.getQuads(face).size()).sum();
                *///?}
            }
            check("stand's own bottle pictures left out", withBottles == without, "quads with bottles=" + withBottles + " without=" + without);
            boolean known = mc.level.getBlockEntity(BREWING) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand
                    && ClientBrewing.item(stand, 0).is(Items.POTION) && ClientBrewing.item(stand, 2).is(Items.POTION);
            check("client knows the potions in the stand", known, "");
        });
        step(100, () -> shot("b8_brewing_mid"));
        step(2, () -> pointAt(brewing().handle(BrewingStation.INGREDIENT)));
        step(10, () -> shot("b8_ingredient_tooltip"));
        step(2, Showcase::pointAside);
        step(150, () -> shot("b8_brewing_late"));
        step(150, () -> shot("b9_done"));
        step(4, () -> server(p -> {
            if (p.level().getBlockEntity(BREWING) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand) {
                int awkward = 0;
                for (int i = 0; i < 3; i++) {
                    var contents = stand.getItem(i).get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
                    if (contents != null && contents.is(net.minecraft.world.item.alchemy.Potions.AWKWARD)) {
                        awkward++;
                    }
                }
                check("three awkward potions brewed", awkward == 3, "awkward=" + awkward + " wart=" + stand.getItem(3));
            }
        }));
        step(2, () -> pointAt(brewing().handle(0)));
        step(6, () -> clickBrewing(BrewingStation.Target.ITEM, 0));
        step(8, () -> shot("b9_bottle_in_hand"));
        step(2, Showcase::stash);
        step(4, restoreLayout);
        step(4, () -> report("brewing before leaving"));
        step(2, Scene::leave);
        step(50, () -> shot("b9_after"));
        smithyAct("r", CRAFTER);
    }

    private static CrafterStation crafter() {
        return Scene.station(CrafterStation.class);
    }

    private static void clickCrafter(CrafterStation.Target expected, int button) {
        CrafterStation station = crafter();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed crafter target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    private static void crafterScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("r5_active");
        step(4, () -> invClickItem(Items.OAK_PLANKS));
        for (int cell : new int[]{0, 1, 3, 4}) {
            step(3, () -> pointAt(crafter().cell(cell)));
            if (cell == 0) {
                step(10, () -> shot("r6_carry_planks"));
            }
            step(5, () -> clickCrafter(CrafterStation.Target.CELL, 1));
        }
        step(4, Showcase::stash);
        step(4, Showcase::pointAside);
        step(14, () -> shot("r7_grid"));
        step(2, () -> check("crafter shows a crafting table", crafter() != null && crafter().slot(CrafterStation.RESULT).is(Items.CRAFTING_TABLE),
                "result=" + (crafter() == null ? "-" : crafter().slot(CrafterStation.RESULT))));
        step(2, () -> pointAt(crafter().cell(8)));
        step(6, () -> clickCrafter(CrafterStation.Target.CELL, 0));      // an empty window, empty hand: shut it
        step(10, () -> shot("r8_shut"));
        step(2, () -> check("crafter window shut", crafter() != null && crafter().menu() != null && crafter().menu().isSlotDisabled(8), ""));
        step(2, () -> pointAt(crafter().resultPoint()));
        step(10, () -> shot("r8_result_tooltip"));
        step(2, () -> server(p -> p.level().setBlockAndUpdate(CRAFTER.east(), Blocks.REDSTONE_BLOCK.defaultBlockState())));
        step(5, () -> shot("r9_signal"));
        step(10, () -> shot("r9_crafted"));
        step(2, () -> server(p -> {
            List<ItemEntity> made = p.level().getEntitiesOfClass(ItemEntity.class, new AABB(CRAFTER).inflate(3));
            // thrown out of its mouth towards the player, who may have picked it up already
            boolean table = made.stream().anyMatch(e -> e.getItem().is(Items.CRAFTING_TABLE)) || p.getInventory().contains(new ItemStack(Items.CRAFTING_TABLE));
            check("crafter made a crafting table on the signal", table, "items=" + made.size());
            p.level().setBlockAndUpdate(CRAFTER.east(), Blocks.AIR.defaultBlockState());
        }));
        step(4, restoreLayout);
        step(4, () -> report("crafter before leaving"));
        step(2, Scene::leave);
        step(50, () -> shot("r9_after"));
        if (ONLY) step(2, () -> finish(Minecraft.getInstance()));
        else campfireAct();
    }

    private static final long[] beefPace = new long[3];

    /** Cooked beef dropped round the campfire or already picked up by the player. */
    private static long cookedBeef(ServerPlayer p, BlockPos fire) {
        long count = 0;
        var box = new net.minecraft.world.phys.AABB(fire.getX() - 4, fire.getY() - 2, fire.getZ() - 4,
                fire.getX() + 5, fire.getY() + 3, fire.getZ() + 5);
        for (var item : p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (item.getItem().is(Items.COOKED_BEEF)) count += item.getItem().getCount();
        }
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(Items.COOKED_BEEF)) count += p.getInventory().getItem(i).getCount();
        }
        ItemStack carried = p.containerMenu.getCarried();
        return carried.is(Items.COOKED_BEEF) ? count + carried.getCount() : count;
    }

    private static CampfireStation campfire() {
        return Scene.station(CampfireStation.class);
    }

    private static void clickCampfire(CampfireStation.Target expected, int button) {
        CampfireStation station = campfire();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed campfire target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    /** Looks at the campfire (the invitation shows) and presses the sit key, as a player would. */
    private static void campfireAct() {
        step(2, () -> {
            act = "f";
            server(p -> p.teleportTo(p.level(), CAMPFIRE.getX() + 0.5, -60, CAMPFIRE.getZ() + 2.9, java.util.Set.of(), 180f, 24f, true));
        });
        step(30, () -> shot("f0_hint"));
        campfireHintFadeScript("f");
        step(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            boolean looking = mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(CAMPFIRE);
            check("looking at the campfire for the hint", looking, "hit=" + mc.hitResult);
            introShot = 0;
            outroShot = 0;
            waitingForActive = true;
            net.minecraft.client.KeyMapping.click(CampfireHint.SIT.getKey());
        });
        step(6, () -> check("sit key starts the campfire scene", Scene.station instanceof CampfireStation, "station=" + Scene.station));
    }

    /** Film the low-opacity tail, where vanilla Font used to turn transparent text fully opaque again. */
    private static void campfireHintFadeScript(String prefix) {
        step(2, () -> Minecraft.getInstance().player.setYRot(90f));
        step(1, () -> shot(prefix + "0_hint_fade_100"));
        step(0, () -> shot(prefix + "0_hint_fade_150"));
        step(0, () -> shot(prefix + "0_hint_fade_200"));
        step(0, () -> shot(prefix + "0_hint_fade_250"));
        step(0, () -> shot(prefix + "0_hint_fade_300"));
        step(0, () -> shot(prefix + "0_hint_fade_350"));
        step(4, () -> shot(prefix + "0_hint_fade_600"));
        step(2, () -> Minecraft.getInstance().player.setYRot(180f));
        step(20, () -> shot(prefix + "0_hint_restored"));
    }

    private static void campfireScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("f5_active");
        step(4, () -> invClickItem(Items.CHICKEN));
        step(3, () -> pointAt(campfire().spot(0)));
        step(10, () -> shot("f6_carry_chicken"));
        step(2, () -> clickCampfire(CampfireStation.Target.PLACE, 1));
        step(4, () -> pointAt(campfire().spot(2)));
        step(6, () -> clickCampfire(CampfireStation.Target.PLACE, 1));
        step(4, Showcase::stash);
        step(4, () -> invClickItem(Items.MUTTON));
        step(3, () -> pointAt(campfire().spot(1)));
        step(6, () -> clickCampfire(CampfireStation.Target.PLACE, 1));
        step(4, Showcase::stash);
        step(4, Showcase::pointAside);
        step(14, () -> shot("f7_food_on_fire"));
        step(2, () -> server(p -> {
            int food = 0;
            if (p.level().getBlockEntity(CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                for (ItemStack stack : fire.getItems()) {
                    food += stack.isEmpty() ? 0 : 1;
                }
            }
            check("three pieces on the fire", food == 3, "food=" + food);
        }));
        int[] beforeCooldown = {0};
        step(2, () -> {
            CampfireStation station = campfire();
            check("campfire timers reach the seated player", station != null && station.syncedCookingTime(0) > 0
                    && station.syncedProgress(0) > 0 && station.syncedLit(),
                    "time=" + (station == null ? "-" : station.syncedProgress(0) + "/" + station.syncedCookingTime(0)));
            beforeCooldown[0] = station == null ? 0 : station.syncedProgress(0);
            server(p -> {
                var level = p.level();
                var state = level.getBlockState(CAMPFIRE);
                level.setBlock(CAMPFIRE, state.setValue(CampfireBlock.LIT, false), 3);
            });
        });
        step(20, () -> shot("f7_cooling"));
        step(2, () -> {
            CampfireStation station = campfire();
            check("extinguished campfire cools its progress", station != null && !station.syncedLit()
                    && station.syncedProgress(0) < beforeCooldown[0],
                    "progress=" + (station == null ? "-" : station.syncedProgress(0)));
            server(p -> {
                var level = p.level();
                var state = level.getBlockState(CAMPFIRE);
                level.setBlock(CAMPFIRE, state.setValue(CampfireBlock.LIT, true), 3);
            });
        });
        step(12, () -> shot("f7_relit"));
        step(2, () -> check("relit campfire resumes cooking", campfire() != null && campfire().syncedLit()
                && campfire().syncedProgress(0) > 0, "progress=" + (campfire() == null ? "-" : campfire().syncedProgress(0))));
        step(2, () -> pointAt(campfire().spot(farSpot())));
        step(14, () -> shot("f8_lean_far_spot"));
        step(2, () -> check("leaning in to reach the far side of the fire",
                CameraRig.last() != null && Minecraft.getInstance().player != null
                        && Math.hypot(CameraRig.last().pos().x - CAMPFIRE.getX() - 0.5, CameraRig.last().pos().z - CAMPFIRE.getZ() - 0.5) < 1.2,
                "camera=" + (CameraRig.last() == null ? "-" : CameraRig.last().pos())));
        step(2, () -> pointAt(campfire().spot(0)));
        step(8, () -> shot("f8_hover_food"));
        step(2, () -> clickCampfire(CampfireStation.Target.FOOD, 0));
        step(8, () -> shot("f8_taken"));
        step(2, () -> check("chicken taken off the fire", carried().is(Items.CHICKEN), "carried=" + carried()));
        step(2, () -> pointAt(campfire().spot(0)));
        step(6, () -> clickCampfire(CampfireStation.Target.PLACE, 0));
        step(60, () -> shot("f9_resting"));
        // Reproduce a report about raw beef staying visible at 0% when placed from the seated scene.
        step(4, () -> invClickItem(Items.BEEF));
        step(3, () -> pointAt(campfire().spot(3)));
        step(6, () -> clickCampfire(CampfireStation.Target.PLACE, 1));
        step(4, Showcase::stash);
        step(24, () -> {
            CampfireStation station = campfire();
            check("seated beef timer advances on the client", station != null && station.syncedLit()
                            && station.syncedCookingTime(3) > 0 && station.syncedProgress(3) > 0,
                    "timer=" + (station == null ? "-" : station.syncedProgress(3) + "/" + station.syncedCookingTime(3)));
            server(p -> {
                if (p.level().getBlockEntity(CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                    check("seated beef cooks on the server", fire.getItems().get(3).is(Items.BEEF)
                                    && fire.cookingTime[3] > 0 && fire.cookingProgress[3] > 0,
                            "timer=" + fire.cookingProgress[3] + "/" + fire.cookingTime[3]);
                    beefPace[0] = p.level().getGameTime();
                    beefPace[1] = fire.cookingProgress[3];
                }
            });
        });
        // The repair must leave a healthy fire alone: one step of progress per game tick, as in vanilla.
        step(20, () -> server(p -> {
            if (p.level().getBlockEntity(CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                long ticks = p.level().getGameTime() - beefPace[0], cooked = fire.cookingProgress[3] - beefPace[1];
                check("seated beef cooks at the vanilla pace", ticks > 0 && cooked > 0 && cooked <= ticks,
                        "progress +" + cooked + " in " + ticks + " ticks");
            }
        }));
        step(2, () -> server(p -> {
            if (p.level().getBlockEntity(CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                fire.cookingTime[3] = 0; // Simulate a timer lost after another block-entity update.
                fire.cookingProgress[3] = 0;
                fire.setChanged();
            }
        }));
        step(12, () -> {
            CampfireStation station = campfire();
            check("seated beef recovers a missing timer", station != null && station.syncedCookingTime(3) > 0
                            && station.syncedProgress(3) > 0,
                    "timer=" + (station == null ? "-" : station.syncedProgress(3) + "/" + station.syncedCookingTime(3)));
        });
        // Finish the piece: exactly one cooked beef pops off, as from the vanilla ticker.
        step(2, () -> server(p -> {
            beefPace[2] = cookedBeef(p, CAMPFIRE);
            if (p.level().getBlockEntity(CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                fire.cookingProgress[3] = Math.max(0, fire.cookingTime[3] - 4);
                fire.setChanged();
            }
        }));
        step(16, () -> server(p -> {
            if (p.level().getBlockEntity(CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                long cooked = cookedBeef(p, CAMPFIRE) - beefPace[2];
                check("seated beef pops off cooked once", fire.getItems().get(3).isEmpty() && cooked == 1,
                        "spot=" + fire.getItems().get(3) + " cooked=" + cooked);
            }
        }));
        step(4, () -> report("campfire before standing up"));
        step(2, Scene::leave);
        step(50, () -> shot("f9_after"));
        step(2, restoreLayout);
        soulCampfireAct();
    }

    // ---- the soul campfire ----------------------------------------------------------------------------

    private static void soulCampfireAct() {
        step(2, () -> {
            act = "u";
            server(p -> p.teleportTo(p.level(), SOUL_CAMPFIRE.getX() + 0.5, -60, SOUL_CAMPFIRE.getZ() + 2.9, java.util.Set.of(), 180f, 24f, true));
        });
        step(30, () -> shot("u0_hint"));
        campfireHintFadeScript("u");
        step(2, () -> {
            introShot = 0;
            outroShot = 0;
            waitingForActive = true;
            net.minecraft.client.KeyMapping.click(CampfireHint.SIT.getKey());
        });
        step(6, () -> check("sit key works at a soul campfire too", campfire() != null && campfire().pos.equals(SOUL_CAMPFIRE),
                "station=" + Scene.station));
    }

    private static void soulCampfireScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("u5_active");
        step(4, () -> invClickItem(Items.CHICKEN));
        step(3, () -> pointAt(campfire().spot(0)));
        step(6, () -> clickCampfire(CampfireStation.Target.PLACE, 1));
        step(2, Showcase::stash);
        step(2, () -> server(p -> {
            if (p.level().getBlockEntity(SOUL_CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                fire.cookingTime[0] = 80; // A short recipe for filming both the rising bar and its disappearance.
                fire.setChanged();
            }
        }));
        step(12, () -> shot("u6_cooking_early"));
        int[] early = {0};
        step(2, () -> {
            CampfireStation station = campfire();
            check("soul campfire has its own cooking timer", station != null && station.syncedCookingTime(0) == 80
                    && station.syncedProgress(0) > 0 && station.syncedLit(),
                    "time=" + (station == null ? "-" : station.syncedProgress(0) + "/" + station.syncedCookingTime(0)));
            early[0] = station == null ? 0 : station.syncedProgress(0);
        });
        step(24, () -> shot("u7_cooking_late"));
        step(2, () -> check("soul campfire progress rises", campfire() != null && campfire().syncedProgress(0) > early[0],
                "progress=" + (campfire() == null ? "-" : campfire().syncedProgress(0))));
        step(52, () -> shot("u8_cooked"));
        step(2, () -> check("cooking indicator clears when food finishes", campfire() != null && campfire().syncedCookingTime(0) == 0
                && Minecraft.getInstance().level.getBlockEntity(SOUL_CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire
                && fire.getItems().get(0).isEmpty(), "time=" + (campfire() == null ? "-" : campfire().syncedCookingTime(0))));
        // Raw beef from the seated scene, on its own spot of the soul fire.
        step(4, () -> invClickItem(Items.BEEF));
        step(3, () -> pointAt(campfire().spot(1)));
        step(6, () -> clickCampfire(CampfireStation.Target.PLACE, 1));
        step(4, Showcase::stash);
        step(24, () -> {
            CampfireStation station = campfire();
            check("soul campfire beef timer advances on the client", station != null && station.syncedLit()
                            && station.syncedCookingTime(1) > 0 && station.syncedProgress(1) > 0,
                    "timer=" + (station == null ? "-" : station.syncedProgress(1) + "/" + station.syncedCookingTime(1)));
            server(p -> {
                if (p.level().getBlockEntity(SOUL_CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                    check("soul campfire beef cooks on the server", fire.getItems().get(1).is(Items.BEEF)
                                    && fire.cookingTime[1] > 0 && fire.cookingProgress[1] > 0,
                            "timer=" + fire.cookingProgress[1] + "/" + fire.cookingTime[1]);
                    beefPace[0] = p.level().getGameTime();
                    beefPace[1] = fire.cookingProgress[1];
                }
            });
        });
        step(20, () -> server(p -> {
            if (p.level().getBlockEntity(SOUL_CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                long ticks = p.level().getGameTime() - beefPace[0], cooked = fire.cookingProgress[1] - beefPace[1];
                check("soul campfire beef cooks at the vanilla pace", ticks > 0 && cooked > 0 && cooked <= ticks,
                        "progress +" + cooked + " in " + ticks + " ticks");
                beefPace[2] = cookedBeef(p, SOUL_CAMPFIRE);
                fire.cookingProgress[1] = Math.max(0, fire.cookingTime[1] - 4);
                fire.setChanged();
            }
        }));
        step(16, () -> server(p -> {
            if (p.level().getBlockEntity(SOUL_CAMPFIRE) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire) {
                long cooked = cookedBeef(p, SOUL_CAMPFIRE) - beefPace[2];
                check("soul campfire beef pops off cooked once", fire.getItems().get(1).isEmpty() && cooked == 1,
                        "spot=" + fire.getItems().get(1) + " cooked=" + cooked);
            }
        }));
        step(2, restoreLayout);
        step(2, Scene::leave);
        step(50, () -> shot("u9_after"));
        lecternAct();
    }

    // ---- the lectern ----------------------------------------------------------------------------------

    /** A written book of a few pages, to read on the lectern. */
    /** A book and quill with a few pages of notes in it. */
    private static ItemStack notesBook() {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, new net.minecraft.world.item.component.WritableBookContent(List.of(
                net.minecraft.server.network.Filterable.passThrough("Записки мастера.\n\nКниги на резной полке достают по одной: корешок под рукой чуть выдвигается, а клик вытягивает книгу."),
                net.minecraft.server.network.Filterable.passThrough("Книгу с текстом можно раскрыть прямо у полки и читать, как на кафедре: лист за листом."),
                net.minecraft.server.network.Filterable.passThrough("Зачарованные книги мерцают на полке, а их чары видны в подсказке."))));
        return book;
    }

    private static ItemStack storyBook() {
        String[][] text = {
                {"Кинематографичные взаимодействия", "Эта книга лежит на кафедре, чтобы её можно было прочитать прямо в мире. Камера склоняется над страницами, а рука переворачивает лист за уголок."},
                {"Глава первая", "В мастерской пахнет деревом и углём. На верстаке лежат доски, в печи догорает уголь, а у стены ждёт наковальня с тяжёлым молотом."},
                {"", "Если подойти к столу зачарований, над книгой загораются руны. Лазурит ложится в чашу, предмет поднимается в воздух, и полки отдают ему свои знаки."},
                {"Глава вторая", "Ткацкий станок держит белый флаг. Челнок летает из руки в руку, и узор проступает ряд за рядом, пока не станет красным крестом."},
                {"", "У варочной стойки можно присесть. Бутылки стоят на её каменных ножках, незерский нарост тает в чаше, и капли стекают вниз."},
                {"Глава третья", "Маяк стоит на пирамиде из железа, золота, изумрудов и алмазов. Над ним висят силы, а плата парит над стеклом."},
                {"Конец", "Закрой книгу и забери её с собой. Или оставь на кафедре: следующий читатель откроет её на той же странице."},
        };
        List<net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component>> pages = new ArrayList<>();
        for (String[] page : text) {
            net.minecraft.network.chat.MutableComponent c = net.minecraft.network.chat.Component.empty();
            if (!page[0].isEmpty()) {
                c.append(net.minecraft.network.chat.Component.literal(page[0]).withStyle(net.minecraft.ChatFormatting.BOLD,
                        net.minecraft.ChatFormatting.DARK_RED)).append("\n\n");
            }
            c.append(page[1]);
            pages.add(net.minecraft.server.network.Filterable.passThrough(c));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new net.minecraft.world.item.component.WrittenBookContent(
                net.minecraft.server.network.Filterable.passThrough("Мастерская"), "dcvke", 0, pages, true));
        return book;
    }

    private static LecternStation lectern() {
        return Scene.station(LecternStation.class);
    }

    private static void lecternAct() {
        nextAct("k", new Vec3(LECTERN.getX() + 0.5, -60, LECTERN.getZ() + 2.6), 180f, LECTERN, new Vec3(0.5, 0.9, 0.6), Direction.UP);
    }

    private static int lecternPage(ServerPlayer p) {
        return p.level().getBlockEntity(LECTERN) instanceof net.minecraft.world.level.block.entity.LecternBlockEntity lectern ? lectern.getPage() : -1;
    }

    private static void lecternScript() {
        Runnable restoreLayout = spatialEditorScript();
        step(2, Scene::leave);
        step(40, () -> { check("lectern profile outside scene", StationDisplayLayouts.customLectern() && Scene.phase == Scene.Phase.NONE, "vanilla display uses saved geometry"); shot("xk4_book_outside"); });
        step(2, () -> useAt(LECTERN, new Vec3(0.5, 0.9, 0.6), Direction.UP));
        step(65, () -> { check("lectern reopens with saved geometry", lectern() != null && Scene.phase == Scene.Phase.ACTIVE, "book remains readable"); shot("k5_active"); });
        step(2, () -> check("lectern book open at the first spread", lectern() != null && lectern().spread() == 0,
                "spread=" + (lectern() == null ? "-" : lectern().spread())));
        step(2, () -> pointAt(lectern().pageCenter(false)));
        step(10, () -> shot("k6_peek"));
        step(2, () -> screenClick(0));
        step(2, () -> shot("k6_turn_010"));
        step(2, () -> shot("k6_turn_020"));
        step(2, () -> shot("k6_turn_030"));
        step(2, () -> shot("k6_turn_040"));
        step(3, () -> shot("k6_turn_055"));
        step(14, () -> shot("k7_spread1"));
        step(2, () -> screenClick(0));
        step(20, () -> shot("k7_spread2"));
        step(2, () -> server(p -> check("lectern turned to page 4 (the left of the third spread)", lecternPage(p) == 3, "page=" + lecternPage(p))));
        step(2, () -> pointAt(lectern().pageCenter(true)));
        step(10, () -> shot("k7_peek_back"));
        step(2, () -> lectern().scroll(1));
        step(4, () -> shot("k7_back_020"));
        step(16, () -> shot("k7_back_done"));
        step(2, () -> server(p -> check("lectern turned back by the wheel", lecternPage(p) == 1, "page=" + lecternPage(p))));
        step(2, () -> lectern().keyPressed(InputConstants.KEY_RIGHT));
        step(4, () -> lectern().keyPressed(InputConstants.KEY_RIGHT));
        step(4, () -> lectern().keyPressed(InputConstants.KEY_RIGHT));
        step(30, () -> shot("k8_last_spread"));
        step(2, () -> check("arrow keys turned to the last spread", lectern() != null && lectern().spread() == 3,
                "spread=" + (lectern() == null ? "-" : lectern().spread())));
        step(2, () -> pointAt(lectern().pageCenter(false)));
        step(4, () -> lectern().click(0, true));
        step(5, () -> shot("k8_take_025"));
        step(5, () -> shot("k8_take_050"));
        step(5, () -> shot("k8_take_075"));
        step(30, () -> server(p -> {
            boolean taken = p.getInventory().contains(stack -> stack.is(Items.WRITTEN_BOOK));
            boolean empty = !p.level().getBlockState(LECTERN).getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK);
            check("book taken off the lectern", taken && empty, "inventory=" + taken + " lecternEmpty=" + empty);
        }));
        step(2, restoreLayout);
        step(30, () -> shot("k9_after"));
        step(2, () -> server(p -> {
            // put it back for the third-person shot
            net.minecraft.world.level.block.LecternBlock.tryPlaceBook(null, p.level(), LECTERN, p.level().getBlockState(LECTERN), storyBook());
        }));
        shelfAct();
    }

    // ---- the chiseled bookshelf -------------------------------------------------------------------------

    private static ShelfStation shelf() {
        return Scene.station(ShelfStation.class);
    }

    private static void clickShelf(ShelfStation.Target expected, int button) {
        ShelfStation station = shelf();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed shelf target " + expected + ": was " + (station == null ? "-" : station.target + " slot " + station.hoverSlot));
        }
        screenClick(button);
    }

    private static ItemStack shelfItem(ServerPlayer p, int slot) {
        return p.level().getBlockEntity(SHELF) instanceof net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity shelf
                ? shelf.getItem(slot) : ItemStack.EMPTY;
    }

    /** Selects a hotbar slot on both sides, with {@code stack} put in it. */
    private static void hold(int slot, ItemStack stack) {
        server(p -> {
            p.getInventory().setItem(slot, stack);
            p.getInventory().setSelectedSlot(slot);
        });
        Minecraft.getInstance().player.getInventory().setSelectedSlot(slot);
        Minecraft.getInstance().getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
    }

    private static void shelfAct() {
        if (ONLY && !"e".equals(FROM)) {
            step(2, () -> finish(Minecraft.getInstance()));
            return;
        }
        step(2, () -> hold(7, ItemStack.EMPTY));
        nextAct("e", new Vec3(SHELF.getX() + 0.5, -60, SHELF.getZ() + 3.0), 180f, SHELF, SHELF_SLOTS[1], Direction.SOUTH);
    }

    private static void shelfScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("e5_active");
        // an enchanted book slides out under the cursor and says what it carries
        step(2, () -> pointAt(shelf().slotPoint(0)));
        step(12, () -> shot("e6_hover_enchanted"));
        step(2, () -> clickShelf(ShelfStation.Target.BOOK, 0));
        moveShots("e7_take", 0.15, 0.3, 0.45, 0.6, 0.8, 1.0);
        step(10, () -> {
            ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
            check("shelf: the enchanted book taken into the hand", carried.is(Items.ENCHANTED_BOOK), "carried=" + describe(carried));
            server(p -> check("shelf: its place is empty on the server", shelfItem(p, 0).isEmpty(), "slot 0=" + describe(shelfItem(p, 0))));
        });
        // into the empty place
        step(2, () -> pointAt(shelf().slotPoint(2)));
        step(8, () -> shot("e8_place_hover"));
        step(2, () -> clickShelf(ShelfStation.Target.PLACE, 0));
        moveShots("e9_put", 0.15, 0.3, 0.45, 0.6, 0.8);
        step(14, () -> {
            shot("ea_put_done");
            server(p -> check("shelf: the book stands in the other place", shelfItem(p, 2).is(Items.ENCHANTED_BOOK) && p.containerMenu.getCarried().isEmpty(),
                    "slot 2=" + describe(shelfItem(p, 2)) + " carried=" + describe(p.containerMenu.getCarried())));
        });
        // the master's notes: drawn out, opened and read
        step(2, () -> pointAt(shelf().slotPoint(1)));
        step(10, () -> shot("eb_hover_notes"));
        step(2, () -> clickShelf(ShelfStation.Target.BOOK, 0));
        moveShots("ec_open", 0.15, 0.35, 0.55, 0.75, 0.95, 1.15, 1.3, 1.5);
        step(10, () -> {
            shot("ed_reading");
            check("shelf: the notes open in the hands", shelf() != null && shelf().reading() && shelf().spread() == 0,
                    "reading=" + (shelf() != null && shelf().reading()) + " spread=" + (shelf() == null ? "-" : shelf().spread()));
            server(p -> check("shelf: the notes stay on the shelf while read", shelfItem(p, 1).is(Items.WRITABLE_BOOK), "slot 1=" + describe(shelfItem(p, 1))));
        });
        step(2, () -> pointAt(shelf().pageCenter(false)));
        step(8, () -> shot("ee_peek"));
        step(2, () -> screenClick(0));
        step(4, () -> shot("ee_turn_020"));
        step(4, () -> shot("ee_turn_040"));
        step(16, () -> {
            shot("ef_spread1");
            check("shelf: a leaf of the notes turned", shelf() != null && shelf().spread() == 1, "spread=" + (shelf() == null ? "-" : shelf().spread()));
        });
        step(2, () -> Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE)));
        moveShots("eg_back", 0.2, 0.45, 0.7, 0.95, 1.15, 1.35);
        step(14, () -> {
            shot("eh_back_done");
            check("shelf: Esc put the notes back first", shelf() != null && !shelf().reading() && Scene.phase == Scene.Phase.ACTIVE,
                    "phase=" + Scene.phase);
        });
        // the plain book straight into the inventory
        step(2, () -> pointAt(shelf().slotPoint(4)));
        step(8, () -> {
            ShelfStation station = shelf();
            if (station == null || station.target != ShelfStation.Target.BOOK) {
                REPORT.add("cursor missed the plain book");
            }
            if (station != null) {
                station.click(0, true);
            }
        });
        moveShots("ei_bag", 0.3, 0.6, 0.9);
        step(10, () -> server(p -> check("shelf: the plain book into the inventory", shelfItem(p, 4).isEmpty()
                && p.getInventory().contains(stack -> stack.is(Items.BOOK)), "slot 4=" + describe(shelfItem(p, 4)))));
        step(4, () -> report("shelf before leaving"));
        step(2, Scene::leave);
        step(40, () -> shot("e9_after"));
        step(4, restoreLayout);
        // a book in hand used on an empty place: the scene puts it straight in
        step(2, () -> {
            act = "ej";
            hold(7, new ItemStack(Items.BOOK));
        });
        step(10, () -> {
            introShot = 0;
            useAt(SHELF, SHELF_SLOTS[4], Direction.SOUTH);
        });
        step(100, () -> server(p -> check("shelf: the book in hand went into the place used", shelfItem(p, 4).is(Items.BOOK) && p.getInventory().getItem(7).isEmpty(),
                "slot 4=" + describe(shelfItem(p, 4)) + " hand=" + describe(p.getInventory().getItem(7)))));
        step(2, () -> shot("ej_put_from_hand"));
        step(2, Scene::leave);
        // the scene turned off: using the shelf takes the book out the vanilla way
        step(40, () -> {
            act = "ek";
            Settings.SCENES.get(StationKind.CHISELED_BOOKSHELF).set(false);
        });
        step(4, () -> useAt(SHELF, SHELF_SLOTS[3], Direction.SOUTH));
        step(20, () -> {
            check("shelf, scene off: no scene", Scene.phase == Scene.Phase.NONE, "phase=" + Scene.phase);
            server(p -> check("shelf, scene off: the written book taken out the vanilla way", shelfItem(p, 3).isEmpty()
                    && p.getInventory().contains(stack -> stack.is(Items.WRITTEN_BOOK)), "slot 3=" + describe(shelfItem(p, 3))));
            Settings.SCENES.get(StationKind.CHISELED_BOOKSHELF).set(true);
        });
        beaconAct();
    }

    // ---- the beacon -----------------------------------------------------------------------------------

    private static BeaconStation beacon() {
        return Scene.station(BeaconStation.class);
    }

    private static void clickBeacon(BeaconStation.Target expected, int button) {
        BeaconStation station = beacon();
        if (station == null || station.target != expected) {
            REPORT.add("cursor missed beacon target " + expected + ": was " + (station == null ? "-" : station.target));
        }
        screenClick(button);
    }

    private static void beaconAct() {
        nextAct("n", new Vec3(BEACON.getX() + 0.5, BEACON.getY(), BEACON.getZ() + 1.75), 180f, BEACON, new Vec3(0.5, 0.9, 0.5), Direction.UP);
    }

    private static void beaconScript() {
        Runnable restoreLayout = spatialEditorScript();
        shot("n5_active");
        step(2, () -> check("beacon stands on a full pyramid", beacon() != null && beacon().menu() != null && beacon().menu().getLevels() == 4,
                "levels=" + (beacon() == null || beacon().menu() == null ? "-" : beacon().menu().getLevels())));
        step(2, () -> pointAt(beacon().powerPos(0)));
        step(10, () -> shot("n6_hover_speed"));
        step(2, () -> clickBeacon(BeaconStation.Target.POWER, 0));
        step(6, () -> pointAt(beacon().powerPos(5)));
        step(4, () -> clickBeacon(BeaconStation.Target.POWER, 0));
        step(8, () -> shot("n6_chosen"));
        step(2, () -> invClickItem(Items.GOLD_INGOT));
        step(3, () -> pointAt(beacon().paymentPos()));
        step(10, () -> shot("n7_carry_gold"));
        step(2, () -> clickBeacon(BeaconStation.Target.TOP, 0));
        step(4, Showcase::stash);
        step(4, () -> pointAt(beacon().paymentPos()));
        step(10, () -> shot("n7_payment_tooltip"));
        step(2, () -> clickBeacon(BeaconStation.Target.PAYMENT, 0));
        moveShots("n8_light", 0.2, 0.45, 0.62, 0.72, 0.85, 1.05, 1.4, 1.9, 2.6);
        step(10, () -> server(p -> {
            net.minecraft.nbt.CompoundTag tag = p.level().getBlockEntity(BEACON).saveWithoutMetadata(p.level().registryAccess());
            String primary = tag.getStringOr("primary_effect", ""), secondary = tag.getStringOr("secondary_effect", "");
            check("beacon lit with speed and regeneration", primary.equals("minecraft:speed") && secondary.equals("minecraft:regeneration"),
                    "primary=" + primary + " secondary=" + secondary);
        }));
        step(2, () -> check("payment used up", beacon() != null && beacon().slot(0).isEmpty(), "slot=" + (beacon() == null ? "-" : beacon().slot(0))));
        step(4, () -> report("beacon before leaving"));
        step(2, restoreLayout);
        step(2, Scene::leave);
        step(50, () -> shot("n9_after"));
        heightAct();
    }

    /** The campfire spot furthest from where the player sits. */
    private static int farSpot() {
        CampfireStation station = campfire();
        Vec3 eye = Minecraft.getInstance().player.position();
        int far = 0;
        for (int j = 1; j < 4; j++) {
            if (station.spot(j).distanceToSqr(eye) > station.spot(far).distanceToSqr(eye)) {
                far = j;
            }
        }
        return far;
    }

    // ---- heights: the camera stays with the player's head ------------------------------------------

    /** Where height case {@code i} stands. */
    private static BlockPos heightSpot(int i) {
        return new BlockPos(HEIGHTS_X - 4 * i, -60, 22);
    }

    private static void buildHeights(ServerLevel level) {
        net.minecraft.world.level.block.state.BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState table = Blocks.CRAFTING_TABLE.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState south = Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH);
        // 0: a table on a pillar, its top above the head
        BlockPos b = heightSpot(0);
        level.setBlockAndUpdate(b, stone);
        level.setBlockAndUpdate(b.above(), table);
        // 1: a furnace on a block, its front at eye level
        b = heightSpot(1);
        level.setBlockAndUpdate(b, stone);
        level.setBlockAndUpdate(b.above(), south);
        // 2: a furnace two blocks up
        b = heightSpot(2);
        level.setBlockAndUpdate(b, stone);
        level.setBlockAndUpdate(b.above(), stone);
        level.setBlockAndUpdate(b.above(2), south);
        // 3: a table on the ground in front of a floor raised one block
        b = heightSpot(3);
        level.setBlockAndUpdate(b, table);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                level.setBlockAndUpdate(b.offset(dx, 0, dz), Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        // 4: a table sunk into the ground, its top flush with it
        b = heightSpot(4);
        level.setBlockAndUpdate(b.below(), table);
        // 5: a table at the bottom of a hole, its top a block below the feet
        b = heightSpot(5);
        level.setBlockAndUpdate(b.below(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(b.below(2), table);
        // 6: a furnace sunk into the ground, its front to a pit on the player's side
        b = heightSpot(6);
        level.setBlockAndUpdate(b.below(), south);
        level.setBlockAndUpdate(b.below().south(), Blocks.AIR.defaultBlockState());
        // 7: a furnace sunk the same way, its front to a pit on the far side: only its top shows
        b = heightSpot(7);
        level.setBlockAndUpdate(b.below(), Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(b.below().north(), Blocks.AIR.defaultBlockState());
        // 8: a table with a slab lying on its top
        b = heightSpot(8);
        level.setBlockAndUpdate(b, table);
        level.setBlockAndUpdate(b.above(), Blocks.STONE_SLAB.defaultBlockState());
        // 9: a table the player stands on
        b = heightSpot(9);
        level.setBlockAndUpdate(b, table);
        // 10: a furnace with lava in front of it
        b = heightSpot(10);
        level.setBlockAndUpdate(b, south);
        for (int dz = 1; dz <= 2; dz++) {
            level.setBlockAndUpdate(b.south(dz).below(), Blocks.LAVA.defaultBlockState());
        }
        // 11: a table to fall from (the ground under the player gives way)
        level.setBlockAndUpdate(heightSpot(11), table);
        // 12: a furnace at the foot of a raised floor, its front against the floor: only its top shows
        b = heightSpot(12);
        level.setBlockAndUpdate(b, south);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                level.setBlockAndUpdate(b.offset(dx, 0, dz), Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        // 13: a furnace sunk into the ground, its front to a pit on its east side, the player to the south
        b = heightSpot(13);
        level.setBlockAndUpdate(b.below(), Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(b.below().east(), Blocks.AIR.defaultBlockState());
        // 14: a brewing stand on the ground in front of a raised floor
        b = heightSpot(14);
        level.setBlockAndUpdate(b, Blocks.BREWING_STAND.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                level.setBlockAndUpdate(b.offset(dx, 0, dz), Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        // 16-18: a furnace, a smoker and a blast furnace sunk into the ground, their fronts to a pit, the player far off
        net.minecraft.world.level.block.Block[] furnaces = {Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE};
        for (int k = 0; k < 3; k++) {
            b = heightSpot(16 + k);
            level.setBlockAndUpdate(b.below(), furnaces[k].defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(b.below().south(), Blocks.AIR.defaultBlockState());
        }
        // 19: a furnace on the ground, a raised floor beginning a block in front of it
        b = heightSpot(19);
        level.setBlockAndUpdate(b, south);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 2; dz <= 4; dz++) {
                level.setBlockAndUpdate(b.offset(dx, 0, dz), Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        // 20: a furnace with a wall between it and the player (the way goes round)
        b = heightSpot(20);
        level.setBlockAndUpdate(b, south);
        for (int dx = -1; dx <= 1; dx++) {
            level.setBlockAndUpdate(b.offset(dx, 0, 2), stone);
            level.setBlockAndUpdate(b.offset(dx, 1, 2), stone);
        }
        // 21: a furnace buried in the ground: only its top shows
        level.setBlockAndUpdate(heightSpot(21).below(), south);
        // 22: a furnace facing a wall
        b = heightSpot(22);
        level.setBlockAndUpdate(b, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(b.north(), stone);
        level.setBlockAndUpdate(b.north().above(), stone);
        // 23: a furnace with lava all over the ground before it, far round to either side
        b = heightSpot(23);
        level.setBlockAndUpdate(b, south);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                level.setBlockAndUpdate(b.offset(dx, -1, dz), Blocks.LAVA.defaultBlockState());
            }
        }
        // 15: a lectern with a carpet laid in front of it (the carpet is stood on, not in the way)
        b = heightSpot(15);
        level.setBlockAndUpdate(b, Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, Direction.SOUTH));
        net.minecraft.world.level.block.LecternBlock.tryPlaceBook(null, level, b, level.getBlockState(b), storyBook());
        for (int dz = 1; dz <= 3; dz++) {
            //? if >=26.2 {
            level.setBlockAndUpdate(b.south(dz), Blocks.CARPET.red().defaultBlockState());
            //?} else if <26.2 {
            /*level.setBlockAndUpdate(b.south(dz), Blocks.RED_CARPET.defaultBlockState());
            *///?}
        }
    }

    /** Stands the player at {@code feet} looking at {@code point}. */
    private static void standLooking(Vec3 feet, Vec3 point) {
        server(p -> {
            Vec3 eye = feet.add(0, p.getEyeHeight(), 0);
            Vec3 d = point.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            p.teleportTo(p.level(), feet.x, feet.y, feet.z, java.util.Set.of(), yaw, pitch, true);
        });
    }

    private static void closeWindow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gui.screen() != null && !(mc.gui.screen() instanceof SceneScreen)) {
            mc.player.closeContainer();
        }
    }

    /** The camera's height over the player's eyes right now. */
    private static double cameraOverEyes() {
        Minecraft mc = Minecraft.getInstance();
        return CameraRig.last() == null || mc.player == null ? 99 : CameraRig.last().pos().y - mc.player.getEyeY();
    }

    /**
     * One height case: the player stands at {@code feet} (relative to the case's spot), uses the station and gets
     * either the scene ({@code scene}, then {@code during} runs in it) or the block's own window.
     */
    private static void heightCase(int i, String name, BlockPos station, Vec3 feet, Vec3 local, Direction face, boolean scene,
            @javax.annotation.Nullable Runnable during) {
        heightCase(i, name, station, feet, local, face, scene, during, true);
    }

    private static void heightCase(int i, String name, BlockPos station, Vec3 feet, Vec3 local, Direction face, boolean scene,
            @javax.annotation.Nullable Runnable during, boolean leave) {
        BlockPos base = heightSpot(i);
        Vec3 at = Vec3.atLowerCornerOf(base).add(feet);
        step(2, () -> {
            act = name;
            // a window left open by the case before (a scene that did not start) would take the use
            closeWindow();
            standLooking(at, Vec3.atCenterOf(station));
        });
        step(18, () -> shot(name + "_0_before"));
        step(2, () -> {
            introShot = 0;
            outroShot = 0;
            Scene.refused = "";
            Minecraft mc = Minecraft.getInstance();
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atLowerCornerOf(station).add(local), face, station, false));
        });
        if (scene) {
            // (a long way round takes a while)
            step(90, () -> {
                check(name + ": a scene", Scene.phase == Scene.Phase.ACTIVE, "phase=" + Scene.phase + " refused=" + Scene.refused
                        + String.format(" camera over eyes=%.2f crouching=%s", cameraOverEyes(), Minecraft.getInstance().player.isCrouching()));
                shot(name + "_5_scene");
            });
            if (during != null) {
                step(2, during);
            }
            if (leave) {
                step(2, () -> {
                    if (Scene.phase == Scene.Phase.ACTIVE) {
                        Scene.leave();
                    }
                });
            }
            step(36, () -> {});
        } else {
            // the hand reaches out to the block as the window opens, as vanilla's use swings it
            //? if >=26.3 {
            step(1, () -> check(name + ": the hand reaches out", Minecraft.getInstance().player.isSwinging(), "swing=" + Minecraft.getInstance().player.getSwingAnimation(0)));
            //?} else if <26.3 {
            /*step(1, () -> check(name + ": the hand reaches out", Minecraft.getInstance().player.isSwinging(), "swing=" + Minecraft.getInstance().player.getAttackAnim(0)));
            *///?}
            step(15, () -> {
                Minecraft mc = Minecraft.getInstance();
                check(name + ": its own window", mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> && !Scene.active(),
                        "screen=" + (mc.gui.screen() == null ? "-" : mc.gui.screen().getClass().getSimpleName()) + " phase=" + Scene.phase + " why=" + Scene.refused);
                shot(name + "_5_window");
            });
            step(4, Showcase::closeWindow);
        }
    }

    private static void heightAct() {
        if (EDITOR_BATCH) { developerOptionsScript(); jukeboxAct(); return; }
        if (stopHere("h")) {
            return;
        }
        BlockPos b;
        b = heightSpot(0);
        heightCase(0, "h0_table_on_pillar", b.above(), new Vec3(0.5, 0, 2.2), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, false, null);
        b = heightSpot(1);
        heightCase(1, "h1_furnace_on_block", b.above(), new Vec3(0.5, 0, 2.4), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, true,
                () -> check("furnace on a block: the camera stays with the head, the player stands up",
                        cameraOverEyes() <= 0.17 && !Minecraft.getInstance().player.isCrouching(), String.format("over eyes=%.2f", cameraOverEyes())));
        b = heightSpot(2);
        heightCase(2, "h2_furnace_two_up", b.above(2), new Vec3(0.5, 0, 2.0), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, false, null);
        b = heightSpot(3);
        heightCase(3, "h3_table_below_floor", b, new Vec3(0.5, 1, 2.4), new Vec3(0.5, 1.0, 0.7), Direction.UP, true,
                () -> check("table below the floor: the player crouches to it", Minecraft.getInstance().player.isCrouching() && cameraOverEyes() >= -0.97,
                        String.format("over eyes=%.2f", cameraOverEyes())));
        b = heightSpot(4);
        heightCase(4, "h4_table_flush", b.below(), new Vec3(0.5, 0, 2.2), new Vec3(0.5, 1.0, 0.7), Direction.UP, true,
                () -> check("table flush with the ground: the player bends down to it", Minecraft.getInstance().player.isCrouching(), ""));
        b = heightSpot(5);
        heightCase(5, "h5_table_in_a_hole", b.below(2), new Vec3(0.5, 0, 1.9), new Vec3(0.5, 1.0, 0.5), Direction.UP, false, null);
        b = heightSpot(6);
        heightCase(6, "h6_furnace_sunk_facing", b.below(), new Vec3(0.5, 0, 2.35), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, true,
                () -> check("sunk furnace facing the player: bending down to it", Scene.station instanceof FurnaceStation
                        && Minecraft.getInstance().player.isCrouching(), String.format("over eyes=%.2f", cameraOverEyes())));
        b = heightSpot(7);
        heightCase(7, "h7_furnace_sunk_away", b.below(), new Vec3(0.5, 0, 1.4), new Vec3(0.5, 1.0, 0.6), Direction.UP, true,
                () -> check("sunk furnace facing away: the player walks round to its front", Scene.station instanceof FurnaceStation
                        && Minecraft.getInstance().player.getZ() < heightSpot(7).getZ() - 0.4, String.format("z=%.2f", Minecraft.getInstance().player.getZ())));
        b = heightSpot(8);
        heightCase(8, "h8_table_covered", b, new Vec3(0.5, 0, 2.2), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, false, null);
        b = heightSpot(9);
        heightCase(9, "h9_standing_on_table", b, new Vec3(0.5, 1, 0.5), new Vec3(0.5, 1.0, 0.5), Direction.UP, false, null);
        b = heightSpot(10);
        heightCase(10, "ha_lava_in_front", b, new Vec3(0.5, 0, 3.4), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, true,
                () -> check("lava before the furnace: the player goes round it and works from beside, crouching",
                        Scene.station instanceof FurnaceStation && Math.abs(Scene.station.swing) > 1 && Minecraft.getInstance().player.isCrouching()
                                && Math.abs(Minecraft.getInstance().player.getX() - (heightSpot(10).getX() + 0.5)) > 0.75,
                        String.format("swing=%.0f x=%.2f z=%.2f", Scene.station == null ? 0 : Scene.station.swing,
                                Minecraft.getInstance().player.getX() - heightSpot(10).getX(), Minecraft.getInstance().player.getZ() - heightSpot(10).getZ())));
        heightCase(23, "hp_lava_all_round", heightSpot(23), new Vec3(0.5, 0, 4.6), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, false, null);
        String[] names = {"hg_furnace_sunk_far", "hh_smoker_sunk_far", "hi_blast_furnace_sunk_far"};
        for (int k = 0; k < 3; k++) {
            int spot = 16 + k;
            heightCase(spot, names[k], heightSpot(spot).below(), new Vec3(0.5, 0, 4.6), new Vec3(0.5, 1.0, 0.6), Direction.UP, true,
                    () -> {
                        check(names[spot - 16] + ": walked to the edge of the pit and bends down", Scene.station instanceof FurnaceStation
                                && Minecraft.getInstance().player.isCrouching() && Minecraft.getInstance().player.getZ() < heightSpot(spot).getZ() + 2.3,
                                String.format("z=%.2f", Minecraft.getInstance().player.getZ() - heightSpot(spot).getZ()));
                        Vec3 eye = CameraRig.last() == null ? Vec3.ZERO : CameraRig.last().pos();
                        double arm = Math.max(Hands.position(true).distanceTo(eye), Hands.position(false).distanceTo(eye));
                        check(names[spot - 16] + ": the resting hands are not stretched down into the pit", arm < 1.15, String.format("hands from the camera=%.2f", arm));
                    });
        }
        heightCase(19, "hj_furnace_below_floor_far", heightSpot(19), new Vec3(0.5, 1, 4.6), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, true,
                () -> check("furnace below the floor's edge: walked to the edge and bends down", Scene.station instanceof FurnaceStation
                        && Minecraft.getInstance().player.isCrouching(), String.format("z=%.2f", Minecraft.getInstance().player.getZ() - heightSpot(19).getZ())));
        heightCase(20, "hk_furnace_behind_wall", heightSpot(20), new Vec3(0.5, 0, 4.5), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, true,
                () -> check("furnace behind a wall: the player went round it", Scene.station instanceof FurnaceStation
                        && Math.abs(Minecraft.getInstance().player.getZ() - heightSpot(20).getZ() - 1.42) < 0.2,
                        String.format("z=%.2f", Minecraft.getInstance().player.getZ() - heightSpot(20).getZ())));
        heightCase(21, "hm_furnace_buried", heightSpot(21).below(), new Vec3(0.5, 0, 3.0), new Vec3(0.5, 1.0, 0.5), Direction.UP, false, null);
        heightCase(22, "hn_furnace_facing_wall", heightSpot(22), new Vec3(0.5, 0, 1.6), new Vec3(0.5, 1.0, 0.5), Direction.UP, false, null);
        b = heightSpot(12);
        heightCase(12, "hc_furnace_front_against_floor", b, new Vec3(0.5, 1, 2.2), new Vec3(0.5, 1.0, 0.6), Direction.UP, false, null);
        b = heightSpot(13);
        heightCase(13, "hd_furnace_sunk_sideways", b.below(), new Vec3(0.9, 0, 1.6), new Vec3(0.5, 1.0, 0.5), Direction.UP, true,
                () -> check("sunk furnace facing sideways: the player walks round to its front", Scene.station instanceof FurnaceStation
                        && Minecraft.getInstance().player.getX() > heightSpot(13).getX() + 1.4, String.format("x=%.2f", Minecraft.getInstance().player.getX() - heightSpot(13).getX())));
        b = heightSpot(14);
        heightCase(14, "he_brewing_below_floor", b, new Vec3(0.5, 1, 2.3), new Vec3(0.5, 0.5, 0.5), Direction.SOUTH, true,
                () -> check("brewing stand below the floor: bending down to it", Scene.station instanceof BrewingStation
                        && Minecraft.getInstance().player.isCrouching() && cameraOverEyes() >= -0.97, String.format("over eyes=%.2f", cameraOverEyes())));
        b = heightSpot(15);
        heightCase(15, "hf_lectern_carpet_in_front", b, new Vec3(0.5, 0.0625, 2.6), new Vec3(0.5, 0.9, 0.6), Direction.UP, true,
                () -> check("lectern with a carpet in front: reading it", Scene.station instanceof LecternStation, "station=" + Scene.station));
        b = heightSpot(11);
        heightCase(11, "hb_ground_gives_way", b, new Vec3(0.5, 0, 2.2), new Vec3(0.5, 1.0, 0.7), Direction.UP, true, () -> {
            server(p -> {
                BlockPos under = p.blockPosition().below();
                p.level().setBlockAndUpdate(under, Blocks.AIR.defaultBlockState());
                p.level().setBlockAndUpdate(under.below(), Blocks.AIR.defaultBlockState());
            });
        }, false);
        step(2, () -> {
            check("falling lets the player go", Scene.phase != Scene.Phase.ACTIVE, "phase=" + Scene.phase);
            shot("hb_ground_gives_way_6_fallen");
        });
        optionsAct();
    }

    // ---- settings: the screen, the height logic off, a scene turned off -----------------------------

    private static void optionsAct() {
        if (stopHere("o")) {
            return;
        }
        step(30, () -> {
            act = "o";
            closeWindow();
            Minecraft mc = Minecraft.getInstance();
            mc.gui.setScreen(new SettingsScreen(null));
            check("the settings screen opens", mc.gui.screen() instanceof SettingsScreen, mc.gui.screen() == null ? "-" : mc.gui.screen().getClass().getName());
            pointGui(mc.getWindow().getGuiScaledWidth() * 0.5, mc.getWindow().getGuiScaledHeight() - 4);
        });
        step(20, () -> shot("o1_settings_general"));
        // over the first option, for its description under the list
        step(2, () -> pointGui(Minecraft.getInstance().getWindow().getGuiScaledWidth() * 0.4, 78));
        step(16, () -> shot("o2_settings_description"));
        step(2, () -> clickWidget(Component.translatable("cinematic.settings.tab.stations").getString()));
        step(16, () -> shot("o3_settings_stations"));
        step(2, () -> clickWidget(Component.translatable("cinematic.settings.tab.camera").getString()));
        step(16, () -> shot("o4_settings_camera"));
        step(2, () -> check("advanced tab opens", clickSettingsSection(Minecraft.getInstance().gui.screen(),
                Component.translatable("cinematic.settings.tab.advanced").getString()), "client-only switch visible in-game"));
        step(16, () -> shot("o5_settings_advanced"));
        step(2, () -> { if (Minecraft.getInstance().gui.screen() instanceof SettingsScreen settings) settings.showDeveloper(); });
        step(16, () -> { verifyProfileControls(); shot("o6_settings_profiles"); });
        //? if >=26.2 {
        step(2, () -> Minecraft.getInstance().gui.setScreen(null));
        //?} else if <26.2 {
        /*step(2, () -> Minecraft.getInstance().setScreen(null));
        *///?}

        // the height logic off: no window, a word on why
        step(10, () -> Settings.HEIGHT_LOGIC.set(false));
        noticeCase(0, "o6_too_high", heightSpot(0).above(), new Vec3(0.5, 0, 2.2), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, "too_high");
        noticeCase(5, "o6_too_low", heightSpot(5).below(2), new Vec3(0.5, 0, 1.9), new Vec3(0.5, 1.0, 0.5), Direction.UP, "too_low");
        noticeCase(22, "o6_facing_wall", heightSpot(22), new Vec3(0.5, 0, 1.6), new Vec3(0.5, 1.0, 0.5), Direction.UP, "no_way");
        noticeCase(8, "o6_covered", heightSpot(8), new Vec3(0.5, 0, 2.2), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, "covered");
        noticeCase(9, "o6_on_top", heightSpot(9), new Vec3(0.5, 1, 0.5), new Vec3(0.5, 1.0, 0.5), Direction.UP, "on_top");
        noticeCase(23, "o6_no_way", heightSpot(23), new Vec3(0.5, 0, 4.6), new Vec3(0.5, 0.5, 1.0), Direction.SOUTH, "no_way");
        step(2, () -> Settings.HEIGHT_LOGIC.set(true));

        // the pace: twice as fast, the lean is over in 0.6 s instead of 1.2 (standing right at the table, no walk)
        Vec3 atTable = Vec3.atLowerCornerOf(heightSpot(4)).add(0.5, 0, 1.36);
        BlockPos flush = heightSpot(4).below();
        step(4, () -> {
            Settings.APPROACH_SPEED.set(200);
            Settings.QUICK_MODE.set(0);
        });
        paceCase("o9_pace_fast", atTable, flush, true);
        step(4, () -> Settings.APPROACH_SPEED.set(100));
        paceCase("o9_pace_usual", atTable, flush, false);
        // the camera's lean: none, the camera stays at the eyes and looks at the work
        step(4, () -> {
            Settings.LEAN_STRENGTH.set(0);
            Settings.QUICK_MODE.set(1);
        });
        step(2, () -> {
            act = "o8_lean_0";
            standLooking(atTable, Vec3.atCenterOf(flush));
        });
        step(10, () -> useAt(flush, new Vec3(0.5, 1.0, 0.7), Direction.UP));
        step(60, () -> {
            Minecraft mc = Minecraft.getInstance();
            Vec3 eye = mc.player.getEyePosition();
            double off = CameraRig.last() == null ? 99 : CameraRig.last().pos().distanceTo(eye);
            check("camera lean 0%: the camera stays at the eyes", Scene.phase == Scene.Phase.ACTIVE && off < 0.08, String.format("off the eyes=%.3f", off));
            shot("o8_lean_0");
        });
        step(2, Scene::leave);
        step(36, () -> Settings.LEAN_STRENGTH.set(100));
        step(2, () -> {
            act = "o8_lean_100";
            standLooking(atTable, Vec3.atCenterOf(flush));
        });
        step(10, () -> useAt(flush, new Vec3(0.5, 1.0, 0.7), Direction.UP));
        step(60, () -> shot("o8_lean_100"));
        step(2, Scene::leave);
        step(36, () -> {});

        // a scene turned off: the block keeps its usual window
        step(2, () -> Settings.SCENES.get(StationKind.TABLE).set(false));
        heightCase(4, "o7_table_scene_off", heightSpot(4).below(), new Vec3(0.5, 0, 2.2), new Vec3(0.5, 1.0, 0.7), Direction.UP, false, null);
        step(2, () -> Settings.SCENES.get(StationKind.TABLE).set(true));
        transitionAct();
    }

    // ---- moving over from one station to the next without stepping away ------------------------------

    private static void transitionAct() {
        if (stopHere("t")) {
            return;
        }
        // at the table, with planks in hand; the furnace stands right beside it
        step(10, () -> {
            act = "t";
            closeWindow();
            standLooking(new Vec3(0.5, -60, 3.36), Vec3.atCenterOf(TABLE));
        });
        step(10, () -> useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP));
        step(70, () -> check("moving over: working at the table", Scene.station instanceof TableStation && Scene.phase == Scene.Phase.ACTIVE,
                "phase=" + Scene.phase));
        step(2, () -> pointGui(Minecraft.getInstance().getWindow().getGuiScaledWidth() * 0.5, Minecraft.getInstance().getWindow().getGuiScaledHeight() * 0.6));
        step(4, () -> invClickItem(Items.OAK_PLANKS));
        step(4, () -> pointAt(new Vec3(FURNACE.getX() + 0.6, FURNACE.getY() + 1.0, FURNACE.getZ() + 0.75)));
        step(8, () -> {
            check("moving over: the furnace beside is offered", FURNACE.equals(Scene.neighbor), "neighbor=" + Scene.neighbor
                    + " cursor=" + java.util.Arrays.toString(Picking.mouseGui()));
            shot("t0_offer_furnace");
        });
        step(2, () -> {
            // film the move over itself
            act = "t2";
            introShot = 0;
            screenClick(1);
        });
        step(2, () -> check("moving over: the item in hand is put away", Hands.carried().isEmpty(), "carried=" + describe(Hands.carried())));
        step(60, () -> {
            check("moving over: working at the furnace, no window", Scene.station instanceof FurnaceStation && Scene.phase == Scene.Phase.ACTIVE
                    && Minecraft.getInstance().gui.screen() instanceof SceneScreen, "phase=" + Scene.phase + " station=" + Scene.station);
            shot("t5_at_furnace");
            server(p -> {
                dev.dyslexic.cinematic.station.Sessions.Session s = dev.dyslexic.cinematic.station.Sessions.get(p);
                boolean planks = false;
                for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
                    planks |= stack.is(Items.OAK_PLANKS);
                }
                check("moving over: the server moved the session, the planks are back", s != null && s.kind() == StationKind.FURNACE
                        && planks && p.containerMenu.getCarried().isEmpty(), "session=" + (s == null ? "-" : s.kind()) + " planks=" + planks);
            });
        });
        // and back to the table, beside the furnace
        step(2, () -> pointAt(new Vec3(TABLE.getX() + 0.3, TABLE.getY() + 0.9, TABLE.getZ() + 1.0)));
        step(8, () -> {
            check("moving over: the table beside is offered", TABLE.equals(Scene.neighbor), "neighbor=" + Scene.neighbor
                    + " cursor=" + java.util.Arrays.toString(Picking.mouseGui()));
            shot("t6_offer_table");
        });
        step(2, () -> {
            act = "t7";
            introShot = 0;
            screenClick(1);
        });
        step(60, () -> {
            check("moving over: back at the table", Scene.station instanceof TableStation && Scene.phase == Scene.Phase.ACTIVE, "phase=" + Scene.phase);
            shot("t9_back_at_table");
        });
        step(2, Scene::leave);
        // two steps over: from the loom to the cartography table, walking to it
        step(40, () -> {
            act = "u_t";
            standLooking(new Vec3(LOOM.getX() + 0.5, -60, LOOM.getZ() + 1.86), Vec3.atCenterOf(LOOM));
        });
        step(10, () -> useAt(LOOM, new Vec3(0.5, 1.0, 0.7), Direction.UP));
        step(70, () -> check("moving over: working at the loom", Scene.station instanceof LoomStation && Scene.phase == Scene.Phase.ACTIVE,
                "phase=" + Scene.phase));
        step(2, () -> pointAt(new Vec3(CARTOGRAPHY.getX() + 0.3, CARTOGRAPHY.getY() + 1.0, CARTOGRAPHY.getZ() + 0.6)));
        step(8, () -> {
            check("moving over: the cartography table is offered", CARTOGRAPHY.equals(Scene.neighbor), "neighbor=" + Scene.neighbor
                    + " cursor=" + java.util.Arrays.toString(Picking.mouseGui()));
            shot("t10_offer_cartography");
        });
        step(2, () -> {
            act = "t11";
            introShot = 0;
            screenClick(1);
        });
        step(90, () -> {
            // (it stands at the side nearest the loom, between the two)
            check("moving over: walked to the cartography table", Scene.station instanceof CartographyStation && Scene.phase == Scene.Phase.ACTIVE
                    && Minecraft.getInstance().player.getX() > LOOM.getX() + 1.2, String.format("phase=%s x=%.2f side=%s",
                    Scene.phase, Minecraft.getInstance().player.getX(), Scene.station == null ? "-" : Scene.station.sideDirection()));
            shot("t15_at_cartography");
        });
        step(2, Scene::leave);
        layoutAct();
    }

    // ---- laying out a recipe from the reference --------------------------------------------------

    private static void layoutAct() {
        if (stopHere("y")) {
            return;
        }
        // a clear table, and planks and sticks for a wooden pickaxe
        step(40, () -> {
            act = "y";
            closeWindow();
            server(p -> {
                dev.dyslexic.cinematic.table.TableActions.spill(p.level(), TABLE);
                p.getInventory().add(new ItemStack(Items.OAK_PLANKS, 6));
                p.getInventory().add(new ItemStack(Items.STICK, 4));
            });
            standLooking(new Vec3(0.5, -60, 3.36), Vec3.atCenterOf(TABLE));
        });
        step(30, () -> useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP));
        step(70, () -> check("lay out: at the table", Scene.station instanceof TableStation && Scene.phase == Scene.Phase.ACTIVE, "phase=" + Scene.phase));
        step(4, () -> {
            var at = table().recipeBook.togglePoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(15, () -> recipeSearch("wooden_pickaxe"));
        step(4, () -> pointRecipe("wooden_pickaxe"));
        step(3, () -> screenClick(0));
        step(10, () -> {
            double[] at = table().recipeBook.layPoint();
            pointGui(at[0], at[1]);
        });
        step(6, () -> {
            shot("y0_lay_button");
            screenClick(0);
        });
        step(9, () -> shot("y1_laying"));
        step(9, () -> shot("y2_laying"));
        step(45, () -> {
            ClientTables.Entry entry = ClientTables.get(TABLE);
            int laid = 0;
            for (int i = 0; entry != null && i < 9; i++) {
                laid += entry.items[i].isEmpty() ? 0 : 1;
            }
            ItemStack result = entry == null ? ItemStack.EMPTY : dev.dyslexic.cinematic.table.TableCrafting.match(Minecraft.getInstance().level,
                    entry.list(), Scene.station.sideDirection()).map(dev.dyslexic.cinematic.table.TableCrafting.Match::result).orElse(ItemStack.EMPTY);
            check("lay out: the pickaxe laid out from the inventory", laid == 5 && result.is(Items.WOODEN_PICKAXE), "laid=" + laid + " result=" + describe(result));
            shot("y3_laid_out");
        });
        // a recipe there is not enough for: what there is goes down, the rest stays see-through
        step(4, () -> server(p -> dev.dyslexic.cinematic.table.TableActions.spill(p.level(), TABLE)));
        step(20, () -> check("lay out: the table cleared", ClientTables.get(TABLE) == null || ClientTables.get(TABLE).list().stream().allMatch(ItemStack::isEmpty), ""));
        step(4, () -> {
            useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP);
        });
        step(70, () -> {
            var at = table().recipeBook.togglePoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(15, () -> recipeSearch("diamond_pickaxe"));
        step(4, () -> pointRecipe("diamond_pickaxe"));
        step(3, () -> screenClick(0));
        step(10, () -> {
            double[] at = table().recipeBook.layPoint();
            pointGui(at[0], at[1]);
            screenClick(0);
        });
        step(40, () -> {
            ClientTables.Entry entry = ClientTables.get(TABLE);
            int laid = 0;
            for (int i = 0; entry != null && i < 9; i++) {
                laid += entry.items[i].isEmpty() ? 0 : 1;
            }
            check("lay out: only the sticks go down, the diamonds stay see-through", laid == 2 && table().recipeBook.guide.missingCount == 3,
                    "laid=" + laid + " missing=" + table().recipeBook.guide.missingCount);
            pointAside();
            shot("y4_partial");
        });
        step(2, () -> recipeControl(113, 194));
        step(2, () -> recipeControl(113, 12));
        lookAct();
    }

    // ---- looking round from behind while at work ----------------------------------------------------

    private static void lookAct() {
        if (stopHere("v")) {
            return;
        }
        step(10, () -> {
            act = "v";
            if (!(Scene.station instanceof TableStation) || Scene.phase != Scene.Phase.ACTIVE) {
                closeWindow();
                standLooking(new Vec3(0.5, -60, 3.36), Vec3.atCenterOf(TABLE));
            }
        });
        step(10, () -> {
            if (Scene.phase == Scene.Phase.NONE) {
                useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP);
            }
        });
        step(70, () -> {
            pointAside();
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_LALT));
        });
        step(30, () -> {
            Minecraft mc = Minecraft.getInstance();
            double away = CameraRig.last() == null ? 0 : CameraRig.last().pos().distanceTo(mc.player.getEyePosition());
            check("look round: the camera goes behind the player, the body is drawn", FreeLook.amount() > 0.95
                    && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK && away > 1.4, String.format("amount=%.2f camera=%s away=%.2f",
                    FreeLook.amount(), mc.options.getCameraType(), away));
            shot("v1_look");
        });
        step(2, () -> Minecraft.getInstance().mouseHandler.xpos += 500);
        step(14, () -> shot("v2_look_turned"));
        step(2, () -> Minecraft.getInstance().mouseHandler.xpos += 700);
        step(14, () -> shot("v3_look_behind"));
        step(2, () -> Minecraft.getInstance().gui.screen().keyReleased(testKey(InputConstants.KEY_LALT)));
        step(30, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("look round: letting go, back at work", FreeLook.amount() < 0.02 && mc.options.getCameraType().isFirstPerson()
                    && Scene.phase == Scene.Phase.ACTIVE, String.format("amount=%.2f camera=%s phase=%s", FreeLook.amount(), mc.options.getCameraType(), Scene.phase));
            shot("v4_back");
        });
        // pressed on and off, as the settings allow
        step(2, () -> {
            Settings.LOOK_TOGGLES.set(true);
            Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_LALT));
            Minecraft.getInstance().gui.screen().keyReleased(testKey(InputConstants.KEY_LALT));
        });
        step(30, () -> check("look round, pressed on: still looking after the key is let go", FreeLook.amount() > 0.95, String.format("amount=%.2f", FreeLook.amount())));
        step(2, () -> Minecraft.getInstance().gui.screen().keyPressed(testKey(InputConstants.KEY_ESCAPE)));
        step(30, () -> check("look round: Esc comes back to work first", FreeLook.amount() < 0.02 && Scene.phase == Scene.Phase.ACTIVE,
                String.format("amount=%.2f phase=%s", FreeLook.amount(), Scene.phase)));
        step(2, () -> Settings.LOOK_TOGGLES.set(false));
        step(2, Scene::leave);
        jukeboxAct();
    }

    // ---- the jukebox ----------------------------------------------------------------------------------

    private static void jukeboxAct() {
        if (stopHere("j")) return;
        boolean developer = Settings.developerMode();
        step(35, () -> {
            act = "jx"; closeWindow(); Settings.DEVELOPER_MODE.set(true);
            hold(8, new ItemStack(Items.MUSIC_DISC_CAT));
            standLooking(new Vec3(JUKEBOX.getX() - 1.6, -60, JUKEBOX.getZ() + 0.5), Vec3.atCenterOf(JUKEBOX));
        });
        step(20, () -> useAt(JUKEBOX, new Vec3(0.5, 1, 0.5), Direction.UP));
        step(75, () -> {
            check("jukebox waits for editing", Scene.phase == Scene.Phase.ACTIVE && Scene.station.kind() == StationKind.JUKEBOX && !Scene.station.busy(), "no automatic insertion in developer mode");
            Runnable restoreLayout = spatialEditorScript();
            step(2, Scene::leave); step(45, () -> Settings.DEVELOPER_MODE.set(false));
            jukeboxOperations(developer, restoreLayout);
        });
    }

    private static void jukeboxOperations(boolean developer, Runnable restoreLayout) {
        step(40, () -> {
            act = "j";
            closeWindow();
            server(p -> {
                p.getInventory().setItem(8, new ItemStack(Items.MUSIC_DISC_CAT));
                p.getInventory().setSelectedSlot(8);
            });
            Minecraft.getInstance().player.getInventory().setSelectedSlot(8);
            Minecraft.getInstance().getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(8));
            standLooking(new Vec3(JUKEBOX.getX() - 1.6, -60, JUKEBOX.getZ() + 0.5), Vec3.atCenterOf(JUKEBOX));
        });
        step(30, () -> shot("j0_before"));
        step(4, () -> {
            introShot = 0;
            moveShot = 0;
            useAt(JUKEBOX, new Vec3(0.5, 1.0, 0.5), Direction.UP);
        });
        step(110, () -> {
            check("jukebox: a short scene, over by itself", Scene.phase == Scene.Phase.NONE, "phase=" + Scene.phase);
            server(p -> check("jukebox: the record went in and plays", p.level().getBlockState(JUKEBOX).getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)
                    && p.getInventory().getItem(8).isEmpty(), "slot 8=" + describe(p.getInventory().getItem(8))));
        });
        step(30, () -> {
            act = "j2";
            introShot = 0;
            moveShot = 0;
            useAt(JUKEBOX, new Vec3(0.5, 1.0, 0.5), Direction.UP);
        });
        step(110, () -> server(p -> {
            boolean back = false;
            for (ItemStack stack : p.getInventory().getNonEquipmentItems()) {
                back |= stack.is(Items.MUSIC_DISC_CAT);
            }
            check("jukebox: the record taken out into the inventory", !p.level().getBlockState(JUKEBOX).getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)
                    && back, "back=" + back);
        }));
        // the scene turned off: the jukebox works as in vanilla
        step(20, () -> {
            act = "j3";
            Settings.SCENES.get(StationKind.JUKEBOX).set(false);
            server(p -> {
                for (int i = 0; i < p.getInventory().getNonEquipmentItems().size(); i++) {
                    if (p.getInventory().getNonEquipmentItems().get(i).is(Items.MUSIC_DISC_CAT)) {
                        p.getInventory().setItem(8, p.getInventory().removeItemNoUpdate(i));
                        break;
                    }
                }
            });
        });
        step(10, () -> useAt(JUKEBOX, new Vec3(0.5, 1.0, 0.5), Direction.UP));
        step(20, () -> {
            check("jukebox, scene off: no scene", Scene.phase == Scene.Phase.NONE, "phase=" + Scene.phase);
            server(p -> check("jukebox, scene off: the record went in the vanilla way",
                    p.level().getBlockState(JUKEBOX).getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD), ""));
        });
        step(10, () -> useAt(JUKEBOX, new Vec3(0.5, 1.0, 0.5), Direction.UP));
        step(20, () -> {
            server(p -> check("jukebox, scene off: the record pops out the vanilla way",
                    !p.level().getBlockState(JUKEBOX).getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD), ""));
            Settings.SCENES.get(StationKind.JUKEBOX).set(true);
            restoreLayout.run();
            Settings.DEVELOPER_MODE.set(developer);
        });
        quickAct();
    }

    // ---- the quick way back to the station just stepped away from ------------------------------------

    private static void quickAct() {
        if (EDITOR_BATCH) { step(5, () -> finish(Minecraft.getInstance())); return; }
        if (stopHere("q")) {
            return;
        }
        Vec3 atTable = new Vec3(0.5, -60, 3.36);
        step(40, () -> {
            act = "q";
            closeWindow();
            Settings.QUICK_WINDOW.set(12);
            standLooking(atTable, Vec3.atCenterOf(TABLE));
        });
        step(10, () -> useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP));
        step(70, () -> check("quick return: the first time, the usual way in", Scene.phase == Scene.Phase.ACTIVE && !Scene.quick, "quick=" + Scene.quick));
        step(2, Scene::leave);
        // straight back: a quick way in (the usual one would still be leaning in by now)
        step(30, () -> {
            act = "q2";
            introShot = 0;
            useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP);
        });
        step(34, () -> {
            check("quick return: back at the same table, at work within a second and a bit", Scene.quick && Scene.phase == Scene.Phase.ACTIVE,
                    "quick=" + Scene.quick + " phase=" + Scene.phase);
            shot("q3_quick_back");
        });
        step(2, Scene::leave);
        // another station: the usual way
        step(30, () -> {
            act = "q4";
            introShot = 0;
            useAt(FURNACE, new Vec3(0.5, 0.6, 1.0), Direction.SOUTH);
        });
        step(20, () -> check("quick return: another station, the usual way in", !Scene.quick && Scene.phase == Scene.Phase.INTRO,
                "quick=" + Scene.quick + " phase=" + Scene.phase));
        step(80, Scene::leave);
        // after the window: the usual way again
        step(30, () -> {
            Settings.QUICK_WINDOW.set(5);
            standLooking(atTable, Vec3.atCenterOf(TABLE));
        });
        step(10, () -> useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP));
        step(70, Scene::leave);
        step(130, () -> useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP));
        step(20, () -> check("quick return: past the window, the usual way in", !Scene.quick && Scene.phase == Scene.Phase.INTRO,
                "quick=" + Scene.quick + " phase=" + Scene.phase));
        step(70, Scene::leave);
        // turned off: always the usual way
        step(30, () -> {
            Settings.QUICK_WINDOW.set(12);
            Settings.QUICK_MODE.set(0);
            useAt(TABLE, new Vec3(0.5, 1.0, 0.8), Direction.UP);
        });
        step(20, () -> check("quick return turned off: the usual way in", !Scene.quick && Scene.phase == Scene.Phase.INTRO,
                "quick=" + Scene.quick + " phase=" + Scene.phase));
        step(70, () -> {
            Settings.QUICK_MODE.set(1);
            Scene.leave();
        });
        watchAct();
    }

    // ---- what others see: another player at work, watched -----------------------------------------------

    /** The stand-in for another player, made on this client only. */
    private static net.minecraft.client.player.RemotePlayer smith;
    private static final int SMITH_ID = 424242;

    /** The stand-in steps up to a station: standing (or crouching) at {@code feet}, facing {@code yaw}, at work there. */
    private static void smithAt(Vec3 feet, float yaw, StationKind kind, BlockPos pos, boolean crouch) {
        smith.snapTo(feet.x, feet.y, feet.z, yaw, 30f);
        smith.setYHeadRot(yaw);
        smith.yBodyRot = yaw;
        smith.yBodyRotO = yaw;
        smith.yHeadRotO = yaw;
        smith.setShiftKeyDown(crouch);
        smith.setPose(crouch ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
        ClientPayloads.AT_STATION.put(SMITH_ID, new ClientPayloads.AtStation(kind, pos));
    }

    /** The server's word to onlookers that the stand-in began a move at a station. */
    private static void smithMoves(BlockPos pos, StationKind kind, Direction side, List<ItemStack> items, ItemStack result, boolean bag) {
        ClientPayloads.menuDisplay(new dev.dyslexic.cinematic.net.MenuDisplayPayload(pos, kind, side.get2DDataValue(), items));
        ClientPayloads.menuFx(new dev.dyslexic.cinematic.net.MenuFxPayload(pos, kind, SMITH_ID, dev.dyslexic.cinematic.net.MenuFxPayload.Cue.MOVE,
                bag ? 1 : 0, items, result));
    }

    private static void smithShelf(dev.dyslexic.cinematic.net.MenuFxPayload.Cue cue, int slot, ItemStack book) {
        ClientPayloads.menuFx(new dev.dyslexic.cinematic.net.MenuFxPayload(SHELF, StationKind.CHISELED_BOOKSHELF, SMITH_ID, cue, slot, List.of(), book));
    }

    private static void watchAct() {
        if (stopHere("w")) {
            return;
        }
        step(40, () -> {
            act = "w";
            closeWindow();
            Minecraft mc = Minecraft.getInstance();
            smith = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(new byte[]{7}), "Smith"));
            smith.setId(SMITH_ID);
            mc.level.addEntity(smith);
            // the anvil faces east: the smith stands at its south side, the onlooker off to the east, seeing their right arm
            smithAt(new Vec3(ANVIL.getX() + 0.5, -60, ANVIL.getZ() + 0.5 + 0.86), 180f, StationKind.ANVIL, ANVIL, false);
            standLooking(new Vec3(ANVIL.getX() + 3.1, -60, ANVIL.getZ() + 1.7), new Vec3(ANVIL.getX() + 0.5, -59.0, ANVIL.getZ() + 1.0));
        });
        step(20, () -> shot("w0_smith"));
        step(2, () -> {
            var enchantments = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            ItemStack forged = new ItemStack(Items.IRON_PICKAXE);
            forged.enchant(enchantments.getOrThrow(Enchantments.EFFICIENCY), 3);
            smithMoves(ANVIL, StationKind.ANVIL, Direction.SOUTH, List.of(new ItemStack(Items.IRON_PICKAXE),
                    net.minecraft.world.item.enchantment.EnchantmentHelper.createBook(new EnchantmentInstance(enchantments.getOrThrow(Enchantments.EFFICIENCY), 3))), forged, false);
        });
        step(1, () -> check("watching: onlookers are told the smith began forging", ClientMenus.sinceMove(SMITH_ID) < 0.3
                && ClientMenus.BLOCKS.get(ANVIL) != null && ClientMenus.BLOCKS.get(ANVIL).moveResult.isEnchanted(), "since=" + ClientMenus.sinceMove(SMITH_ID)));
        moveShots("w1_anvil", 0.2, 0.45, 0.6, 0.66, 0.7, 0.8, 1.02, 1.3, 1.5, 1.75, 2.05);
        step(2, () -> check("watching: the hammer is put away after forging", PropLayer.hammer(smith) == 0, "shown=" + PropLayer.hammer(smith)));
        // the grindstone faces south: the smith stands south of it, the onlooker to the south-east
        step(20, () -> {
            smithAt(new Vec3(GRINDSTONE.getX() + 0.5, -60, GRINDSTONE.getZ() + 0.5 + 0.9), 180f, StationKind.GRINDSTONE, GRINDSTONE, false);
            standLooking(new Vec3(GRINDSTONE.getX() + 3.0, -60, GRINDSTONE.getZ() + 2.0), new Vec3(GRINDSTONE.getX() + 0.5, -59.2, GRINDSTONE.getZ() + 1.0));
        });
        step(16, () -> {
            var enchantments = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            ItemStack sword = new ItemStack(Items.GOLDEN_SWORD);
            sword.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3);
            smithMoves(GRINDSTONE, StationKind.GRINDSTONE, Direction.SOUTH, List.of(sword, ItemStack.EMPTY), new ItemStack(Items.GOLDEN_SWORD), false);
        });
        moveShots("w2_grind", 0.2, 0.45, 0.7, 1.0, 1.3, 1.55, 1.9, 2.2);
        // the stonecutter faces south too
        step(20, () -> {
            smithAt(new Vec3(STONECUTTER.getX() + 0.5, -60, STONECUTTER.getZ() + 0.5 + 0.95), 180f, StationKind.STONECUTTER, STONECUTTER, false);
            standLooking(new Vec3(STONECUTTER.getX() - 2.3, -60, STONECUTTER.getZ() + 2.1), new Vec3(STONECUTTER.getX() + 0.5, -59.3, STONECUTTER.getZ() + 1.0));
        });
        step(16, () -> smithMoves(STONECUTTER, StationKind.STONECUTTER, Direction.SOUTH, List.of(new ItemStack(Items.STONE, 24)),
                new ItemStack(Items.STONE_BRICKS), false));
        moveShots("w3_cut", 0.15, 0.3, 0.45, 0.6, 0.8, 1.0);
        // the chiseled bookshelf: a book taken, another read and put back
        step(20, () -> {
            smithAt(new Vec3(SHELF.getX() + 0.5, -60, SHELF.getZ() + 0.5 + 1.77), 180f, StationKind.CHISELED_BOOKSHELF, SHELF, true);
            standLooking(new Vec3(SHELF.getX() + 2.4, -60, SHELF.getZ() + 1.4), new Vec3(SHELF.getX() + 0.3, -59.4, SHELF.getZ() + 1.9));
        });
        step(16, () -> smithShelf(dev.dyslexic.cinematic.net.MenuFxPayload.Cue.PULL, 5, new ItemStack(Items.ENCHANTED_BOOK)));
        moveShots("w4_pull", 0.1, 0.3, 0.5, 0.75);
        step(30, () -> smithShelf(dev.dyslexic.cinematic.net.MenuFxPayload.Cue.READ, 1, new ItemStack(Items.WRITABLE_BOOK)));
        moveShots("w5_read", 0.25, 0.6, 0.9, 1.3, 2.0);
        step(2, () -> check("watching: the stand-in reads a book off the shelf", ShelfBooks.reading(SMITH_ID), ""));
        step(2, () -> smithShelf(dev.dyslexic.cinematic.net.MenuFxPayload.Cue.READ_END, 1, new ItemStack(Items.WRITABLE_BOOK)));
        moveShots("w6_back", 0.2, 0.5, 0.85, 1.2);
        step(10, () -> {
            check("watching: the book is back on the shelf", !ShelfBooks.reading(SMITH_ID), "");
            ClientPayloads.AT_STATION.remove(SMITH_ID);
            Minecraft.getInstance().level.removeEntity(SMITH_ID, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        });
        step(30, Showcase::thirdPersonShots);
    }

    private static void useAt(BlockPos station, Vec3 local, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atLowerCornerOf(station).add(local), face, station, false));
    }

    /** Standing right at a table: {@code fast} — the lean is over by 0.9 s, otherwise it is still going on. */
    private static void paceCase(String name, Vec3 feet, BlockPos station, boolean fast) {
        step(2, () -> {
            act = name;
            standLooking(feet, Vec3.atCenterOf(station));
        });
        step(10, () -> useAt(station, new Vec3(0.5, 1.0, 0.7), Direction.UP));
        step(18, () -> {
            check(name + ": " + (fast ? "already working" : "still leaning in") + " after 0.9 s",
                    fast ? Scene.phase == Scene.Phase.ACTIVE : Scene.phase == Scene.Phase.INTRO, "phase=" + Scene.phase + " why=" + Scene.refused);
            shot(name);
        });
        step(30, () -> {
            if (Scene.phase == Scene.Phase.ACTIVE) {
                Scene.leave();
            }
        });
        step(36, () -> {});
    }

    /** Clicks the widget of the open screen whose text is {@code text} (a tab of the settings screen). */
    private static void clickWidget(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null) {
            return;
        }
        for (net.minecraft.client.gui.components.events.GuiEventListener child : mc.gui.screen().children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.getMessage().getString().equals(text)) {
                double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
                mc.gui.screen().mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)), false);
                mc.gui.screen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(0), 0)));
                return;
            }
        }
        REPORT.add("no widget \"" + text + "\" on " + mc.gui.screen().getClass().getSimpleName());
    }

    /**
     * A height case with the height logic off: no window opens and a notice says why ({@code reason}: the end of its
     * translation key).
     */
    private static void noticeCase(int i, String name, BlockPos station, Vec3 feet, Vec3 local, Direction face, String reason) {
        Vec3 at = Vec3.atLowerCornerOf(heightSpot(i)).add(feet);
        step(2, () -> {
            act = name;
            closeWindow();
            standLooking(at, Vec3.atCenterOf(station));
        });
        step(18, () -> {
            Scene.refused = "";
            Minecraft mc = Minecraft.getInstance();
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atLowerCornerOf(station).add(local), face, station, false));
        });
        step(12, () -> {
            Minecraft mc = Minecraft.getInstance();
            String want = net.minecraft.locale.Language.getInstance().getOrDefault("cinematic.notice." + reason).split("%s")[0];
            String got = ReachNotice.text();
            check(name + ": a notice instead of a window", mc.gui.screen() == null && !Scene.active() && got != null && got.contains(want),
                    "screen=" + (mc.gui.screen() == null ? "-" : mc.gui.screen().getClass().getSimpleName()) + " notice=" + got + " why=" + Scene.refused);
            shot(name);
        });
    }

    // ---- how others see it --------------------------------------------------------------------------

    private static void thirdPersonShots() {
        step(2, () -> server(p -> p.teleportTo(p.level(), -0.4, -60, 5.2, java.util.Set.of(), 168f, 12f, true)));
        step(30, () -> shot("80_workshop"));
        poseShot("81_pose_furnace", StationKind.FURNACE, FURNACE);
        poseShot("82_pose_table", StationKind.TABLE, TABLE);
        poseShot("83_pose_enchanting", StationKind.ENCHANTING, ENCHANT);
        // mid-blow at the anvil when the shot is taken
        step(2, () -> ClientMenus.MOVE_BY_ENTITY.put(Minecraft.getInstance().player.getId(), Scene.clock() - 0.2));
        poseShot("84_pose_anvil", StationKind.ANVIL, ANVIL);
        poseShot("85_pose_grindstone", StationKind.GRINDSTONE, GRINDSTONE);
        poseShot("86_pose_stonecutter", StationKind.STONECUTTER, STONECUTTER);
        poseShot("87_pose_loom", StationKind.LOOM, LOOM);
        poseShot("88_pose_brewing", StationKind.BREWING, BREWING);
        poseShot("89_pose_campfire", StationKind.CAMPFIRE, CAMPFIRE);
        poseShot("8a_pose_lectern", StationKind.LECTERN, LECTERN);
        poseShot("8b_pose_beacon", StationKind.BEACON, BEACON);
        poseShot("8c_pose_shelf", StationKind.CHISELED_BOOKSHELF, SHELF);
        step(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            ClientPayloads.AT_STATION.remove(mc.player.getId());
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
        step(4, () -> server(p -> p.level().destroyBlock(TABLE, true)));
        step(10, () -> server(p -> {
            List<ItemEntity> drops = p.level().getEntitiesOfClass(ItemEntity.class, new AABB(TABLE).inflate(2));
            StringBuilder s = new StringBuilder();
            for (ItemEntity drop : drops) {
                s.append(drop.getItem().getCount()).append('x').append(drop.getItem().getItem()).append(' ');
            }
            REPORT.add("table broken, drops: " + s + "| stored: " + (TableStore.get(p.level()).get(TABLE) != null));
        }));
        step(6, () -> report("the end"));
        step(6, () -> finish(Minecraft.getInstance()));
    }

    private static void poseShot(String name, StationKind kind, BlockPos pos) {
        step(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            ClientPayloads.AT_STATION.put(mc.player.getId(), new ClientPayloads.AtStation(kind, pos));
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            mc.player.setYRot(0f);
            mc.player.setYHeadRot(0f);
            mc.player.setYBodyRot(0f);
            mc.player.setXRot(10f);
        });
        step(8, () -> shot(name));
    }

    // ---- simulated cursor -------------------------------------------------------------------------

    private static void step(int delay, Runnable action) {
        STEPS.add(new Step(delay, action));
    }

    private static void pointAt(Vec3 world) {
        double[] gui = Picking.toGui(world);
        if (gui != null) {
            pointGui(gui[0], gui[1]);
        }
    }

    private static void pointGui(double gx, double gy) {
        Minecraft mc = Minecraft.getInstance();
        double sx = mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth();
        double sy = mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight();
        mc.mouseHandler.xpos = gx * sx;
        mc.mouseHandler.ypos = gy * sy;
    }

    private static void pointAside() {
        Minecraft mc = Minecraft.getInstance();
        pointGui(mc.getWindow().getGuiScaledWidth() * 0.86, mc.getWindow().getGuiScaledHeight() * 0.62);
    }

    private static void hoverDrawer() {
        Minecraft mc = Minecraft.getInstance();
        pointGui(mc.getWindow().getGuiScaledWidth() * 0.5 + 20, 14);
    }

    private static void screenClick(int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) {
            double[] at = Picking.mouseGui();
            mc.gui.screen().mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(SceneScreen.physicalButton(button), 0)), false);
        }
    }

    /** A click on an inventory-menu slot (hotbar 36..44, main 9..35). */
    private static void inventoryClick(int slot, int button) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, button, ContainerInput.PICKUP, mc.player);
    }

    /** A click on an inventory slot (0..35) through whatever menu is open. */
    private static void invClick(int inventoryIndex, int button) {
        LocalPlayer player = Minecraft.getInstance().player;
        List<Slot> slots = player.containerMenu.slots;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).container == player.getInventory() && slots.get(i).getContainerSlot() == inventoryIndex) {
                inventoryClick(i, button);
                return;
            }
        }
        REPORT.add("no menu slot for inventory " + inventoryIndex);
    }

    /** Picks up the first stack of an item from the inventory, through whatever menu is open. */
    private static void invClickItem(net.minecraft.world.item.Item item) {
        LocalPlayer player = Minecraft.getInstance().player;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).is(item)) {
                invClick(i, 0);
                return;
            }
        }
        REPORT.add("no " + item + " in the inventory");
    }

    // ---- reporting ------------------------------------------------------------------------------

    private static void check(String name, boolean passed, String details) {
        String line = "check " + (passed ? "passed " : "FAILED ") + name + ": " + details;
        REPORT.add(line);
        Cinematic.LOGGER.info("[Showcase] {}", line);
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "-" : stack.getCount() + "x" + stack.getItem();
    }

    private static void report(String when) {
        Minecraft mc = Minecraft.getInstance();
        REPORT.add(when + " client carried=" + mc.player.containerMenu.getCarried() + " menu=" + mc.player.containerMenu.getClass().getSimpleName());
        server(p -> {
            StringBuilder s = new StringBuilder();
            TableStore.Table table = TableStore.get(p.level()).get(TABLE);
            if (table != null) {
                s.append("table: ");
                for (int i = 0; i < TableGrid.CELLS; i++) {
                    s.append(i).append('=').append(describe(table.items.get(i))).append(' ');
                }
            }
            for (BlockPos pos : new BlockPos[]{FURNACE, SMOKER, BLAST}) {
                if (p.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity f) {
                    s.append("| ").append(p.level().getBlockState(pos).getBlock().getName().getString()).append(": ");
                    for (int slot = 0; slot < 3; slot++) {
                        s.append(describe(f.getItem(slot))).append(' ');
                    }
                    s.append(String.format("lit=%d cook=%d/%d ", f.litTimeRemaining, f.cookingTimer, f.cookingTotalTime));
                }
            }
            StringBuilder inv = new StringBuilder();
            for (int i = 0; i < p.getInventory().getNonEquipmentItems().size(); i++) {
                ItemStack stack = p.getInventory().getNonEquipmentItems().get(i);
                if (!stack.isEmpty()) {
                    inv.append(i).append(':').append(describe(stack));
                    if (stack.isEnchanted()) {
                        inv.append(stack.getEnchantments());
                    }
                    if (stack.has(DataComponents.STORED_ENCHANTMENTS)) {
                        inv.append(stack.get(DataComponents.STORED_ENCHANTMENTS));
                    }
                    if (stack.has(DataComponents.CUSTOM_NAME)) {
                        inv.append('«').append(stack.getHoverName().getString()).append('»');
                    }
                    if (stack.has(DataComponents.TRIM)) {
                        inv.append("[trim ").append(stack.get(DataComponents.TRIM).material().getRegisteredName()).append(']');
                    }
                    if (stack.isDamaged()) {
                        inv.append("{damage ").append(stack.getDamageValue()).append('}');
                    }
                    inv.append(' ');
                }
            }
            if (p.containerMenu != p.inventoryMenu) {
                s.append("| menu slots: ");
                for (int slot = 0; slot < 4 && slot < p.containerMenu.slots.size(); slot++) {
                    s.append(slot).append('=').append(describe(p.containerMenu.getSlot(slot).getItem())).append(' ');
                }
            }
            s.append("| anvil=").append(p.level().getBlockState(ANVIL).getBlock().getName().getString()).append(' ');
            String line = when + " server: " + s + "| inventory: " + inv + "| carried=" + p.containerMenu.getCarried()
                    + " menu=" + p.containerMenu.getClass().getSimpleName()
                    + String.format(" xp=%d+%.2f", p.experienceLevel, p.experienceProgress)
                    + String.format(" pos=%.2f %.2f %.2f yaw %.1f pitch %.1f crouch=%s", p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(),
                    p.isCrouching());
            Cinematic.LOGGER.info("[Showcase] {}", line);
            REPORT.add(line);
        });
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        String hover = Scene.station instanceof TableStation ts ? "cell=" + ts.hoverCell + " result=" + ts.hoverResult
                + String.format(" centreOpacity=%.3f", ts.itemOpacity(4))
                : Scene.station instanceof FurnaceStation fs ? "zone=" + fs.hoverZone
                : Scene.station instanceof EnchantStation es ? "target=" + es.target + " offer=" + es.hoverOffer
                : Scene.station instanceof AnvilStation as ? "target=" + as.target + " move=" + String.format("%.2f", as.moveTime())
                : Scene.station instanceof GrindstoneStation gs ? "target=" + gs.target + " move=" + String.format("%.2f", gs.moveTime())
                : Scene.station instanceof SmithingStation ss ? "target=" + ss.target + " move=" + String.format("%.2f", ss.moveTime())
                : Scene.station instanceof LoomStation ls ? "target=" + ls.target + " pattern=" + ls.hoverPattern + " move=" + String.format("%.2f", ls.moveTime())
                : Scene.station instanceof CartographyStation ms ? "target=" + ms.target + " move=" + String.format("%.2f", ms.moveTime())
                : Scene.station instanceof BrewingStation bs ? "target=" + bs.target + " slot=" + bs.hoverSlot
                : Scene.station instanceof CrafterStation rs ? "target=" + rs.target + " cell=" + rs.hoverCell
                : Scene.station instanceof CampfireStation fs2 ? "target=" + fs2.target + " spot=" + fs2.hoverSpot
                : Scene.station instanceof ShelfStation es ? "target=" + es.target + " slot=" + es.hoverSlot + " move=" + es.moveName()
                : Scene.station instanceof StonecutterStation cs ? "target=" + cs.target + " shape=" + cs.hoverShape
                + " move=" + String.format("%.2f", cs.moveTime()) : "-";
        String line = String.format("%s fps=%d phase=%s t=%.2f %s", name, mc.getFps(), Scene.phase, Scene.time, hover);
        REPORT.add(line);
        Cinematic.LOGGER.info("[Showcase] {}", line);
        //? if >=26.2 {
        mc.gui.toastManager().clear();
        //?} else if <26.2 {
        /*mc.getToastManager().clear();
        *///?}
        FrameProbe.shotTaken();
        //? if >=26.2 {
        Screenshot.grab(mc.gameDirectory, "showcase_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
        //?} else if <26.2 {
        /*Screenshot.grab(mc.gameDirectory, "showcase_" + name + ".png", mc.getMainRenderTarget(), 1, message -> {});
        *///?}
    }

    private static void finish(Minecraft mc) {
        done = true;
        for (String line : REPORT) {
            Cinematic.LOGGER.info("[Showcase] report {}", line);
        }
        mc.stop();
    }

    private static void server(Consumer<ServerPlayer> action) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            return;
        }
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        });
    }

    private static void cmd(String command) {
        server(p -> p.level().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), command));
    }
    //? if >=26.1 {
    private static List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> modelParts(net.minecraft.client.renderer.block.dispatch.BlockStateModel model) {
        List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new ArrayList<>();
    //?} else if <26.1 {
    /*private static List<net.minecraft.client.renderer.block.model.BlockModelPart> modelParts(net.minecraft.client.renderer.block.model.BlockStateModel model) {
        List<net.minecraft.client.renderer.block.model.BlockModelPart> parts = new ArrayList<>();
    *///?}
        model.collectParts(RandomSource.create(1), parts);
        return parts;
    }
    private static net.minecraft.client.input.KeyEvent testKey(int key) {
        //? if >=26.3 {
        return new net.minecraft.client.input.KeyEvent(key, org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false), 0);
        //?} else if <26.3 {
        /*return new net.minecraft.client.input.KeyEvent(key, org.lwjgl.glfw.GLFW.glfwGetKeyScancode(key), 0);
        *///?}
    }
}
