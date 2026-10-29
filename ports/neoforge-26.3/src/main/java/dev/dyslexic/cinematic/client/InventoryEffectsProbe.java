package dev.dyslexic.cinematic.client;

import net.lopymine.ip.config.InventoryParticlesConfig;
import net.lopymine.ip.renderer.InventoryParticlesRenderer;
import net.lopymine.ipi.config.InventoryInteractionsConfig;
import net.lopymine.ipi.renderer.CursorItemRenderer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Native mod state inspected only by the explicitly selected inventory-compatibility Showcase act. */
final class InventoryEffectsProbe {
    private InventoryEffectsProbe() {}

    static void defaults() {
        var config = InventoryParticlesConfig.getInstance();
        config.getMainConfig().setModEnabled(true);
        var particles = config.getParticleConfig();
        particles.setCursorSpawnEnabled(true);
        particles.setHoveredSlotSpawnEnabled(false);
        particles.setGuiSlotsSpawnEnabled(false);
        particles.setGuiActionsSpawnEnabled(true);
        particles.setGuiActionTakeSpawnEnabled(true);
        particles.setGuiActionPutSpawnEnabled(true);
        particles.setGuiActionQuickMoveSpawnEnabled(true);
        if (hasInteractions()) InventoryInteractionsConfig.getInstance().getMainConfig().setModEnabled(true);
        clear();
    }

    static void clear() {
        var renderer = InventoryParticlesRenderer.getInstance();
        renderer.getScreenElements().clear();
        renderer.getPendingElements().clear();
    }

    static int particles() {
        var renderer = InventoryParticlesRenderer.getInstance();
        return renderer.getScreenElements().size() + renderer.getPendingElements().size();
    }

    static Item cursor() { return InventoryParticlesRenderer.getInstance().getCursor().getCurrentStack().getItem(); }
    static boolean hasInteractions() { return ModList.get().isLoaded("inventory_interactions"); }
    static Item physicsItem() { return hasInteractions() ? CursorItemRenderer.getInstance().getCursorItem().getItem() : Items.AIR; }
    static float angle() { return hasInteractions() ? CursorItemRenderer.getInstance().getCursorItem().getRenderAngle(1) : 0; }
    static double x() { return InventoryParticlesRenderer.getInstance().getCursor().getX(); }
    static double y() { return InventoryParticlesRenderer.getInstance().getCursor().getY(); }

    static boolean near(double x, double y) {
        return InventoryParticlesRenderer.getInstance().getScreenElements().stream()
                .anyMatch(p -> Math.abs(p.getX() - x) < 40 && Math.abs(p.getY() - y) < 40);
    }

    static void actionOnly() {
        defaults();
        InventoryParticlesConfig.getInstance().getParticleConfig().setCursorSpawnEnabled(false);
        clear();
    }

    static void allSlots() {
        actionOnly();
        var p = InventoryParticlesConfig.getInstance().getParticleConfig();
        p.setGuiActionsSpawnEnabled(false);
        p.setHoveredSlotSpawnEnabled(true);
        p.setGuiSlotsSpawnEnabled(true);
    }

    static void disable() {
        var p = InventoryParticlesConfig.getInstance().getParticleConfig();
        p.setCursorSpawnEnabled(false);
        p.setGuiActionsSpawnEnabled(false);
        p.setHoveredSlotSpawnEnabled(false);
        p.setGuiSlotsSpawnEnabled(false);
        clear();
    }
}
