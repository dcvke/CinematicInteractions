package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.ClientBridge;
import dev.dyslexic.cinematic.client.compat.FirstPersonBodyCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

@Mod(value = Cinematic.MODID, dist = Dist.CLIENT)
public final class CinematicClient {
    public CinematicClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(CinematicClient::setup);
        modBus.addListener(CinematicClient::keys);
        modBus.addListener(CinematicClient::models);
        modBus.addListener(CinematicClient::renderers);
        modBus.addListener(CinematicClient::pictures);
        modBus.addListener(CinematicClient::layers);
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new SettingsScreen(parent));
        NeoForge.EVENT_BUS.addListener(CinematicClient::tick);
        NeoForge.EVENT_BUS.addListener(CinematicClient::chunkUnload);
        NeoForge.EVENT_BUS.addListener(CinematicClient::entityUnload);
        NeoForge.EVENT_BUS.addListener(CinematicClient::login);
        NeoForge.EVENT_BUS.addListener(CinematicClient::logout);
        NeoForge.EVENT_BUS.addListener(CinematicClient::screenInit);
        NeoForge.EVENT_BUS.addListener(CinematicClient::hideHud);
    }

    private static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            CinematicPipelines.initialize();
            ClientBridge.useStation = Scene::begin;
            ClientBridge.vanillaUse = ClientOnly::onVanillaUse;
            Settings.SPEC.load();
            FirstPersonBodyCompat.register();
        });
    }

    private static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CinematicKeys.CATEGORY);
        event.register(CampfireHint.SIT);
        event.register(FreeLook.LOOK);
    }

    private static void models(ModelEvent.ModifyBakingResult event) {
        StationModels.reset();
        event.getBakingResult().blockStateModels().replaceAll((state, model) -> StationModels.wrap(model, state));
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BlockEntityTypes.BREWING_STAND, BrewingBottles::new);
        event.registerBlockEntityRenderer(BlockEntityTypes.CHISELED_BOOKSHELF, ShelfLayoutBooks::new);
    }

    private static void pictures(RegisterPictureInPictureRenderersEvent event) {
        event.register(LoomFlags.State.class, LoomFlags.Renderer::new);
    }

    private static void layers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Cinematic.id("overlay"), (g, ticks) -> Overlay.render(g));
    }

    private static final java.util.Set<net.minecraft.resources.Identifier> KEEP_HUD = java.util.Set.of(
            net.neoforged.neoforge.client.gui.VanillaGuiLayers.CHAT,
            net.neoforged.neoforge.client.gui.VanillaGuiLayers.TAB_LIST,
            net.neoforged.neoforge.client.gui.VanillaGuiLayers.SUBTITLE_OVERLAY,
            net.minecraft.resources.Identifier.withDefaultNamespace("debug_overlay"),
            net.minecraft.resources.Identifier.withDefaultNamespace("saving_indicator"),
            Cinematic.id("overlay"));

    private static void hideHud(RenderGuiLayerEvent.Pre event) {
        if (Scene.active() && !KEEP_HUD.contains(event.getName())) event.setCanceled(true);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Scene.tick();
        ClientPayloads.faceStations(mc.level);
        CampfireHint.tick();
        if (mc.level != null && mc.level.getGameTime() % 40 == 0) ClientFurnaces.prune(mc.level);
        Showcase.tick();
        ClientOnlySmoke.tick();
    }

    private static void chunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        var pos = event.getChunk().getPos();
        ClientTables.unloadChunk(pos);
        ClientFurnaces.unloadChunk(pos);
        ClientBrewing.unloadChunk(pos);
        ClientEnchants.unloadChunk(pos);
        ClientMenus.unloadChunk(pos);
        ShelfBooks.unloadChunk(pos);
        ShelfLayoutBooks.unloadChunk(pos);
    }

    private static void entityUnload(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof Player player) {
            ClientPayloads.AT_STATION.remove(player.getId());
            ClientMenus.forgetEntity(player.getId());
            ShelfBooks.forgetEntity(player.getId());
        }
    }

    private static void login(ClientPlayerNetworkEvent.LoggingIn event) { ClientOnly.onLogin(); }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    private static void screenInit(ScreenEvent.Init.Post event) {
        ServerListHint.onScreenInit(Minecraft.getInstance(), event.getScreen());
    }
    public static boolean screenOpening(Screen next) {
        if (!Scene.active() && ClientOnly.onScreenOpening(next)) return true;
        if (!Scene.active() || next instanceof SceneScreen) return false;
        var player = Minecraft.getInstance().player;
        if (next instanceof MenuAccess<?> window && Scene.station.kind().usesMenu() && player != null && window.getMenu() == player.containerMenu) return true;
        if (next instanceof InventoryScreen) return true;
        if (Scene.phase == Scene.Phase.ACTIVE && next != null && !next.isPauseScreen()) Scene.drop();
        return false;
    }

    public static void clear() {
        Scene.drop();
        ClientOnly.clear();
        ClientRecipes.clear();
        ClientTables.clear();
        ClientFurnaces.clear();
        ClientBrewing.clear();
        ClientEnchants.clear();
        ClientMenus.clear();
        ShelfBooks.clear();
        ShelfLayoutBooks.clear();
        Fx.clearSparks();
        ClientPayloads.clear();
    }
}
