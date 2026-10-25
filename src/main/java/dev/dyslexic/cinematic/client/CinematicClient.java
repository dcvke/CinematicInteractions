package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.ClientBridge;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@Mod(value = Cinematic.MODID, dist = Dist.CLIENT)
public final class CinematicClient {
    public CinematicClient(IEventBus modBus, ModContainer container) {
        ClientBridge.useStation = Scene::begin;
        ClientBridge.vanillaUse = ClientOnly::onVanillaUse;
        modBus.addListener((ModelEvent.RegisterAdditional event) -> event.register(HammerModel.LOCATION));
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(CampfireHint.SIT);
            event.register(FreeLook.LOOK);
        });
        modBus.addListener(BrewingBottles::hideVanillaBottles);
        modBus.addListener(ShelfBooks::emptySlots);
        modBus.addListener(PropLayer::addTo);
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(BlockEntityType.BREWING_STAND,
                BrewingBottles::new));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerBlockEntityRenderer(BlockEntityType.CHISELED_BOOKSHELF,
                ShelfLayoutBooks::new));
        container.registerConfig(ModConfig.Type.CLIENT, Settings.SPEC);
        // the settings screen: Cloth Config's when it is installed, NeoForge's own otherwise
        container.registerExtensionPoint(IConfigScreenFactory.class, ModList.get().isLoaded("cloth_config")
                ? (IConfigScreenFactory) (mod, parent) -> ClothScreen.create(parent)
                : DeveloperConfiguration::create);
        NeoForge.EVENT_BUS.register(Events.class);
    }

    public static final class Events {
        private Events() {}

        /** How far a sitting player is lowered: hips just above the ground. */
        private static final double SITTING_DROP = 0.62;
        private static final Set<Integer> SEATED = new HashSet<>();

        @SubscribeEvent
        public static void onFrame(RenderFrameEvent.Pre event) {
            Scene.frame();
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Scene.tick();
            ClientOnlySmoke.tick();
            ServerSmoke.tick();
            CampfireHint.tick();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getGameTime() % 40 == 0) {
                ClientFurnaces.prune(mc.level);
            }
            Showcase.tick();
        }

        @SubscribeEvent
        public static void onPlayerTick(PlayerTickEvent.Post event) {
            if (event.getEntity() instanceof LocalPlayer player && player == Minecraft.getInstance().player) {
                Scene.steer(player);
            }
        }

        /** Someone sitting by a campfire is drawn lower, on the ground (their legs are posed by HumanoidModelMixin). */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
            if (!event.isCanceled() && ClientPayloads.atStation(event.getEntity().getId()) == StationKind.CAMPFIRE) {
                event.getPoseStack().pushPose();
                event.getPoseStack().translate(0, -SITTING_DROP, 0);
                SEATED.add(event.getEntity().getId());
            }
        }

        @SubscribeEvent
        public static void onRenderPlayerDone(RenderPlayerEvent.Post event) {
            if (SEATED.remove(event.getEntity().getId())) {
                event.getPoseStack().popPose();
            }
        }

        @SubscribeEvent
        public static void onMovementInput(MovementInputUpdateEvent event) {
            Scene.movementInput(event.getInput());
        }

        @SubscribeEvent
        public static void onTurn(CalculatePlayerTurnEvent event) {
            if (Scene.active()) {
                // zero mouse look while the scene drives the view
                event.setMouseSensitivity(-1.0 / 3.0);
                event.setCinematicCameraEnabled(false);
            }
        }

        @SubscribeEvent
        public static void onInteractKey(InputEvent.InteractionKeyMappingTriggered event) {
            if (Scene.active()) {
                event.setCanceled(true);
                event.setSwingHand(false);
            } else if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND
                    && Minecraft.getInstance().hitResult instanceof BlockHitResult hit && ClientOnly.suppressUseSwing(hit)) {
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void onScroll(InputEvent.MouseScrollingEvent event) {
            if (Scene.active() && Minecraft.getInstance().screen == null) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
                Picking.capture(event);
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                SceneRenderer.render(event);
            }
        }

        @SubscribeEvent
        public static void onFov(ViewportEvent.ComputeFov event) {
            if (Scene.active() && event.usedConfiguredFov()) {
                event.setFOV(CameraRig.fov(event.getFOV()));
            }
        }

        @SubscribeEvent
        public static void onRenderHand(RenderHandEvent event) {
            if (Scene.active()) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onHighlight(RenderHighlightEvent.Block event) {
            if (Scene.active()) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
            if (Overlay.hides(event.getName())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onGui(RenderGuiEvent.Post event) {
            Overlay.render(event.getGuiGraphics());
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onScreenOpening(ScreenEvent.Opening event) {
            Screen next = event.getNewScreen();
            if (!Scene.active() && ClientOnly.onScreenOpening(next)) {
                event.setCanceled(true);
                return;
            }
            if (!Scene.active() || next instanceof SceneScreen) {
                return;
            }
            LocalPlayer player = Minecraft.getInstance().player;
            // (the lectern's window is a book screen, not a container screen, but it holds the menu all the same; while
            // the server has not opened the block's menu yet, the player's own inventory is no menu of the block's)
            if (next instanceof MenuAccess<?> window && Scene.station.kind().usesMenu() && player != null
                    && player.containerMenu != player.inventoryMenu && window.getMenu() == player.containerMenu) {
                var addon = Scene.station.kind() == dev.dyslexic.cinematic.station.StationKind.ADDON
                        ? dev.dyslexic.cinematic.api.AddonStations.forBlock(player.level().getBlockState(Scene.station.pos)) : null;
                boolean expected = addon != null ? addon.accepts(window.getMenu())
                        : dev.dyslexic.cinematic.station.MenuActions.isOpen(Scene.station.kind(), window.getMenu());
                if (expected) {
                    // the server opened the block's menu for us: keep the menu, the scene stands in for its window
                    event.setCanceled(true);
                } else {
                    // the block opened a menu the scene does not know (a mod replaced it, as Penchant does the
                    // enchanting table's): its own window must open, or the window built for it is left half-made
                    // and the mod's menu calls into it on the next slot change
                    Scene.yieldToWindow();
                }
                return;
            }
            if (next instanceof InventoryScreen) {
                event.setCanceled(true);
            } else if (Scene.phase == Scene.Phase.ACTIVE && next != null && !next.isPauseScreen() && !Scene.consulting) {
                // something else took over the screen (a server menu, another mod): drop the scene at once
                Scene.drop();
            }
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            Showcase.onScreen(event.getScreen());
            ClientOnlySmoke.onScreen(event.getScreen());
        }

        @SubscribeEvent
        public static void onChunkUnload(ChunkEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                ClientTables.unloadChunk(event.getChunk().getPos());
                ClientFurnaces.unloadChunk(event.getChunk().getPos());
                ClientBrewing.unloadChunk(event.getChunk().getPos());
                ClientEnchants.unloadChunk(event.getChunk().getPos());
                ClientMenus.unloadChunk(event.getChunk().getPos());
                ShelfBooks.unloadChunk(event.getChunk().getPos());
            }
        }

        /**
         * A player out of sight is forgotten by the client, along with whatever they were doing: if they stepped away
         * from a station meanwhile, nobody tells us, and they would come back still posed at it (sitting in the ground
         * by a campfire that is not there). Coming back into sight, the server says again if they are at one.
         */
        @SubscribeEvent
        public static void onEntityLeave(EntityLeaveLevelEvent event) {
            if (event.getLevel().isClientSide() && event.getEntity() instanceof Player) {
                ClientPayloads.AT_STATION.remove(event.getEntity().getId());
                ClientMenus.forgetEntity(event.getEntity().getId());
                ShelfBooks.forgetEntity(event.getEntity().getId());
            }
        }

        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                Scene.drop();
                ClientTables.clear();
                ClientFurnaces.clear();
                ClientBrewing.clear();
                ClientEnchants.clear();
                ClientMenus.clear();
                ShelfBooks.clear();
                Fx.clearSparks();
                ClientPayloads.clear();
            }
        }

        @SubscribeEvent
        public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            ClientOnly.onLogin();
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            Scene.drop();
            Scene.forgetOwnWindows();
            ClientOnly.clear();
            ClientTables.clear();
            ClientFurnaces.clear();
            ClientBrewing.clear();
            ClientEnchants.clear();
            ClientMenus.clear();
            ShelfBooks.clear();
            ClientPayloads.clear();
        }
    }
}
