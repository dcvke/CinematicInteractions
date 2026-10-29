package dev.dyslexic.cinematic.station;

import dev.dyslexic.cinematic.ClientBridge;
import dev.dyslexic.cinematic.campfire.CampfireActions;
import dev.dyslexic.cinematic.furnace.FurnaceWatch;
import dev.dyslexic.cinematic.net.PacketDistributor;
import dev.dyslexic.cinematic.net.RecipeSyncPayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.table.TableActions;
import dev.dyslexic.cinematic.table.TableGrid;
import dev.dyslexic.cinematic.table.TableStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class StationEvents {
    private StationEvents() {}

    @SubscribeEvent
    public static void use(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        var level = event.getLevel();
        var state = level.getBlockState(event.getPos());
        var kind = StationKind.of(state);
        if (kind == null || !kind.startsOnUse() || event.getHand() != InteractionHand.MAIN_HAND
                || player.isSecondaryUseActive() || player.isSpectator()
                || !kind.usable(level, event.getPos(), state) || !kind.usedOn(state, event.getHitVec().getDirection())) return;
        if (level.isClientSide()) {
            if (ClientBridge.vanillaUse.test(event.getPos(), kind)) return;
            ClientBridge.useAt = event.getHitVec().getLocation();
            ClientBridge.useStation.accept(event.getPos().immutable(), kind);
            ClientBridge.swallowSwing = true;
        } else if (player instanceof ServerPlayer sp
                && !NetworkRegistry.hasChannel(sp.connection, StationActionPayload.TYPE.id())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) Sessions.tick(player);
    }

    @SubscribeEvent
    public static void levelPre(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (ServerPlayer player : level.players()) {
            var session = Sessions.get(player);
            if (session != null && session.kind() == StationKind.CAMPFIRE && session.dimension().equals(level.dimension()))
                CampfireActions.tick(level, session);
        }
    }

    @SubscribeEvent
    public static void levelPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 40 != 0) return;
        for (BlockPos pos : TableStore.get(level).positions())
            if (level.isLoaded(pos) && !TableGrid.isTable(level.getBlockState(pos))) TableActions.spill(level, pos);
    }

    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer watcher)
            Sessions.sendAtStation(target, watcher);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) Sessions.end(player, false);
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) Sessions.end(player, true);
    }

    @SubscribeEvent
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) Sessions.end(player, true);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { Sessions.clear(); }

    @SubscribeEvent
    public static void recipes(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) RecipeSyncPayload.send(event.getPlayer());
        else event.getPlayerList().getPlayers().forEach(RecipeSyncPayload::send);
    }

    public static void neighbourNotify(ServerLevel level, BlockPos pos) {
        if (!TableGrid.isTable(level.getBlockState(pos)) && TableStore.get(level).get(pos) != null) TableActions.spill(level, pos);
    }

    public static void chunkSent(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        TableStore store = TableStore.get(level);
        for (BlockPos pos : store.inChunk(chunk.getPos()))
            PacketDistributor.sendToPlayer(player, TableActions.contents(pos, store.get(pos)));
        FurnaceWatch.chunkSent(player, chunk);
        dev.dyslexic.cinematic.brewing.BrewingWatch.chunkSent(player, chunk);
    }
}
