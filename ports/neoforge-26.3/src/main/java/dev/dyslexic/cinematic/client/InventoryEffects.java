package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Read-only visual slots for optional inventory effects; never attached to a menu or sent to the server. */
final class InventoryEffects {
    record Target(String id, ItemStack stack, double x, double y, boolean hovered) {}
    private record Change(Target target, boolean take, boolean put) {}

    private final List<Target> targets = new ArrayList<>();
    private final Map<String, ItemStack> previous = new HashMap<>();
    private Bridge bridge;
    private ClientStation station;
    private boolean visible;
    private int hoveredWorld = -1;
    private double actionUntil = -1;
    private long inventoryBefore;
    private final List<Change> awaitingTransfer = new ArrayList<>();
    private double changedAt;
    private double transferredAt = -10;
    private ContainerInput action = ContainerInput.PICKUP;

    InventoryEffects() {
        if (ModList.get().isLoaded("inventory_particles") && supported("inventory_particles", "3.")) {
            try {
                bridge = new ParticlesBridge();
            } catch (LinkageError | RuntimeException e) {
                failed(e);
            }
        }
    }

    private static boolean supported(String id, String major) {
        return ModList.get().getModContainerById(id).map(mod -> mod.getModInfo().getVersion().toString().startsWith(major)).orElse(false);
    }

    private void failed(Throwable e) {
        bridge = null;
        Cinematic.LOGGER.warn("[Cinematic] Inventory effects integration unavailable; using the usual scene interface", e);
    }

    void beginFrame(boolean visible) {
        targets.clear();
        this.visible = visible;
        if (station != Scene.station || !visible) {
            previous.clear();
            awaitingTransfer.clear();
            actionUntil = -1;
            transferredAt = -10;
        }
        station = Scene.station;
    }

    void drawer(int index, ItemStack stack, double x, double y, boolean hovered) {
        if (bridge != null && visible) targets.add(new Target("inventory:" + index, stack, x, y, hovered));
    }

    void endFrame(boolean overPanel) {
        if (bridge == null || !visible || station == null) return;
        hoveredWorld = overPanel || station.busy() ? -1 : station instanceof TableStation table ? table.hoverResult ? 9 : table.hoverCell
                : station instanceof FurnaceStation furnace ? furnace.hoverZone
                : station instanceof SurfaceStation<?> surface ? surface.surfaceSlot(false)
                : station instanceof SpatialTarget spatial ? spatial.layoutPick() : -1;
        if (station instanceof TableStation table) {
            ClientTables.Entry entry = ClientTables.get(table.pos);
            for (int i = 0; i < 9; i++) world(i, entry == null ? ItemStack.EMPTY : entry.items[i], table.cellPoint(i), overPanel);
            world(9, table.result, table.resultPosition(), overPanel);
        } else if (station instanceof FurnaceStation furnace) {
            for (var bed : furnace.layout.beds) {
                Vec3 at = furnace.geometry().point(furnace.layout, furnace.pos, furnace.sideDirection(), bed.slot(),
                        (bed.u0() + bed.u1()) / 2, bed.floor() + 0.002, -bed.depth(), true);
                world(bed.slot(), ClientFurnaces.item(furnace.pos, bed.slot()), at, overPanel);
            }
        } else if (station instanceof SurfaceStation<?> surface) {
            for (int i = 0; i < SurfaceAppearance.count(surface.kind()); i++) world(i, surface.slot(i), surface.restPoint(i), overPanel);
        } else if (station instanceof SpatialTarget spatial) {
            // Editor targets also include controls, runes and previews: only real item places become effect slots.
            int count = switch (station.kind()) {
                case ANVIL, GRINDSTONE, ENCHANTING -> 2;
                case STONECUTTER, BEACON, LECTERN -> 1;
                case JUKEBOX -> 0; // its record is hidden inside the block, not an exposed inventory slot
                default -> SpatialAppearance.count(station.kind());
            };
            for (int i = 0; i < count; i++) {
                ItemStack item = spatial.layoutItem(i);
                if (station instanceof EnchantStation && EnchantStation.menu() != null) item = EnchantStation.menu().getSlot(i).getItem();
                if (station instanceof LecternStation && Minecraft.getInstance().level != null
                        && Minecraft.getInstance().level.getBlockEntity(station.pos) instanceof LecternBlockEntity lectern) item = lectern.getBook();
                world(i, item, spatial.layoutPoint(i), overPanel);
            }
        }
        // The hands carry a real world item, so its particles follow the hand rather than the hidden cursor icon.
        if (Hands.itemInHand() && !Hands.carried().isEmpty()) world(-1, Hands.carried(), Hands.heldPoint(), true);
    }

    private void world(int index, ItemStack stack, Vec3 at, boolean overPanel) {
        double[] screen = Picking.toGui(at);
        if (screen == null) return;
        var window = Minecraft.getInstance().getWindow();
        if (screen[0] < 0 || screen[0] >= window.getGuiScaledWidth() || screen[1] < 0 || screen[1] >= window.getGuiScaledHeight()) return;
        boolean hover = !overPanel && index >= 0 && index == hoveredWorld;
        targets.add(new Target("station:" + index, stack, screen[0], screen[1], hover));
    }

    void action(ContainerInput type) {
        if (bridge == null) return;
        if (Scene.clock() > actionUntil) inventoryBefore = inventoryFingerprint();
        action = type;
        actionUntil = Scene.clock() + 6;
    }

    private static long inventoryFingerprint() {
        var player = Minecraft.getInstance().player;
        if (player == null) return 0;
        long result = 1;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) result = result * 31 + ItemStack.hashItemAndComponents(stack) * 31L + stack.getCount();
        ItemStack carried = player.containerMenu.getCarried();
        return result * 31 + ItemStack.hashItemAndComponents(carried) * 31L + carried.getCount();
    }

    void tick() {
        if (bridge == null) return;
        try {
            boolean working = visible && Scene.phase == Scene.Phase.ACTIVE && station == Scene.station && !FreeLook.looking();
            ItemStack cursor = working && !Hands.itemInHand() && station.handsOverride() == null ? Hands.carried() : ItemStack.EMPTY;
            List<Target> shown = working ? targets : List.of();
            List<Change> changes = new ArrayList<>();
            for (Target target : shown) {
                ItemStack before = previous.put(target.id(), target.stack().copy());
                if (before != null && Scene.clock() <= actionUntil && !target.id().equals("station:-1")
                        && !ItemStack.matches(before, target.stack())) {
                    ItemStack affected = target.stack().isEmpty() ? before : target.stack();
                    boolean same = ItemStack.isSameItemSameComponents(before, target.stack());
                    changes.add(new Change(new Target(target.id(), affected, target.x(), target.y(), target.hovered()),
                            !before.isEmpty() && (!same || before.getCount() > target.stack().getCount()),
                            !target.stack().isEmpty() && (!same || before.getCount() < target.stack().getCount())));
                }
            }
            if (!working) {
                previous.clear();
                awaitingTransfer.clear();
            }
            if (!changes.isEmpty()) {
                awaitingTransfer.addAll(changes);
                changedAt = Scene.clock();
            }
            long now = inventoryFingerprint();
            // Separate server packets may update the station and carried stack on adjacent ticks. A timer or
            // rejected click must not masquerade as a transfer, so wait briefly for the player's items to change.
            List<Change> confirmed = List.of();
            if (now != inventoryBefore && Scene.clock() <= actionUntil) transferredAt = Scene.clock();
            if (Scene.clock() - transferredAt <= 0.25 && Scene.clock() <= actionUntil) {
                confirmed = List.copyOf(awaitingTransfer);
                awaitingTransfer.clear();
                inventoryBefore = now;
            } else if (Scene.clock() - changedAt > 0.25) awaitingTransfer.clear();
            bridge.tick(shown, confirmed, action, cursor, working);
        } catch (LinkageError | RuntimeException e) {
            failed(e);
        }
    }

    void carried(GuiGraphicsExtractor graphics, ItemStack stack, int mouseX, int mouseY, BiConsumer<Integer, Integer> draw) {
        if (bridge != null) {
            try {
                if (bridge.carried(graphics, stack, mouseX, mouseY, draw)) return;
            } catch (LinkageError | RuntimeException e) {
                failed(e);
            }
        }
        draw.accept(mouseX - 8, mouseY - 8);
    }

    /** Kept separate so an absent optional mod never resolves any of its classes. */
    private interface Bridge {
        void tick(List<Target> targets, List<Change> changes, ContainerInput action, ItemStack cursor, boolean active);
        boolean carried(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, BiConsumer<Integer, Integer> draw);
    }

    private static final class ParticlesBridge implements Bridge {
        private boolean active;
        private final InteractionsBridge interactions = supported("inventory_interactions", "2.") ? new InteractionsBridge() : null;
        private final net.lopymine.ip.element.mod.spawner.context.ParticleSpawnContext handContext =
                new net.lopymine.ip.element.mod.spawner.context.ParticleSpawnContext(
                        net.lopymine.ip.config.sub.InventoryParticleConfig::isCursorSpawnEnabled,
                        net.lopymine.ip.config.sub.InventoryParticlesCoefficientsConfig::getCursorConfig);

        @Override
        public void tick(List<Target> targets, List<Change> changes, ContainerInput action, ItemStack carried, boolean active) {
            var config = net.lopymine.ip.config.InventoryParticlesConfig.getInstance();
            if (!config.getMainConfig().isModEnabled()) return;
            var renderer = net.lopymine.ip.renderer.InventoryParticlesRenderer.getInstance();
            if (this.active && !active) {
                renderer.clear();
                renderer.init();
            }
            this.active = active;
            var cursor = renderer.getCursor();
            double[] mouse = Picking.mouseGui();
            cursor.setMouseX(mouse[0]);
            cursor.setMouseY(mouse[1]);
            cursor.setCurrentStack(carried);
            cursor.setHoveredSlot(null);
            var options = config.getParticleConfig();
            boolean moving = Scene.station != null && Scene.station.busy();
            for (Target target : targets) {
                if (target.hovered()) cursor.setHoveredSlot(slot(target));
                if (target.stack().isEmpty()) continue;
                boolean hand = target.id().equals("station:-1");
                boolean hover = target.hovered() && options.isHoveredSlotSpawnEnabled();
                // Still observe transfers on an animation's beat, but do not leave ambient particles at an item's old resting place.
                if (moving && !hand && target.id().startsWith("station:")) continue;
                if (renderer.isStoppedTicking() || (hand ? !options.isCursorSpawnEnabled() : !hover && !options.isGuiSlotsSpawnEnabled())) continue;
                var spawners = net.lopymine.ip.resourcepack.manager.ParticlesConfigsManager.getSpawnersForItem(target.stack().getItem());
                if (spawners == null) continue;
                var context = hand ? handContext : hover ? net.lopymine.ip.element.mod.spawner.context.ParticleSpawnContext.hoveredSlot(slot(target), 0, 0)
                        : net.lopymine.ip.element.mod.spawner.context.ParticleSpawnContext.slots(slot(target), 0, 0);
                if (hand) {
                    context.setStack(target.stack());
                    context.setX(target.x());
                    context.setY(target.y());
                }
                for (var spawner : spawners) for (var particle : spawner.tickAndSpawn(context)) renderer.addPendingElement(particle);
            }
            if (options.isGuiActionsSpawnEnabled()) {
                for (Change change : changes) {
                    boolean enabled = action == ContainerInput.QUICK_MOVE ? options.isGuiActionQuickMoveSpawnEnabled()
                            : change.take() && options.isGuiActionTakeSpawnEnabled() || change.put() && options.isGuiActionPutSpawnEnabled();
                    Target target = change.target();
                    if (enabled) renderer.onGuiAction(slot(target), target.stack(), 0, 0);
                }
            }
            // ScreenMixin already renders these particles once. Only the missing container tick is supplied here.
            renderer.tick(null, null, null);
            if (interactions != null) interactions.tick(carried);
        }

        private static Slot slot(Target target) {
            // Native spawners accept Slot coordinates. This detached container is only a visual snapshot.
            Slot slot = new Slot(new SimpleContainer(target.stack()), 0, (int) Math.round(target.x() - 8), (int) Math.round(target.y() - 8));
            slot.index = target.id().hashCode();
            return slot;
        }

        @Override
        public boolean carried(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, BiConsumer<Integer, Integer> draw) {
            return net.lopymine.ip.config.InventoryParticlesConfig.getInstance().getMainConfig().isModEnabled()
                    && interactions != null && interactions.carried(graphics, stack, x, y, draw);
        }
    }

    private static final class InteractionsBridge {
        void tick(ItemStack carried) {
            if (net.lopymine.ipi.config.InventoryInteractionsConfig.getInstance().getMainConfig().isModEnabled()) {
                net.lopymine.ipi.renderer.CursorItemRenderer.getInstance().tick(carried,
                        net.lopymine.ip.renderer.InventoryParticlesRenderer.getInstance().getCursor());
            }
        }

        boolean carried(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, BiConsumer<Integer, Integer> draw) {
            if (!net.lopymine.ipi.config.InventoryInteractionsConfig.getInstance().getMainConfig().isModEnabled()) return false;
            net.lopymine.ipi.renderer.CursorItemRenderer.getInstance().render(graphics, stack, x, y, x - 8, y - 8, draw::accept);
            return true;
        }
    }
}
