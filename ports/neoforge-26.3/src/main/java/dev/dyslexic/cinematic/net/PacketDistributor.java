package dev.dyslexic.cinematic.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class PacketDistributor {
    private PacketDistributor() {}

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (NetworkRegistry.hasChannel(player.connection, payload.type().id()))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload) {
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk, false)) sendToPlayer(player, payload);
    }

    public static void sendToPlayersTrackingEntityAndSelf(ServerPlayer entity, CustomPacketPayload payload) {
        sendToPlayer(entity, payload);
        for (ServerPlayer watcher : entity.level().getChunkSource().chunkMap.getPlayers(entity.chunkPosition(), false))
            if (watcher != entity) sendToPlayer(watcher, payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (connection != null && NetworkRegistry.hasChannel(connection, payload.type().id()))
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload);
    }
}
