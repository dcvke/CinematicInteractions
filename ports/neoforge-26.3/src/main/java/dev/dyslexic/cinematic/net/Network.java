package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.station.Sessions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class Network {
    private Network() {}

    public static <E extends Enum<E>> StreamCodec<RegistryFriendlyByteBuf, E> enumCodec(Class<E> type) {
        return StreamCodec.of((buf, value) -> buf.writeEnum(value), buf -> buf.readEnum(type));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // This port's protocol is independent of the older NeoForge 1.21.1 build.
        var registrar = event.registrar("26.3-port-1").optional();
        registrar.playBidirectional(ProtocolPayload.TYPE, ProtocolPayload.CODEC, (p, c) -> {}, (p, c) -> {});
        registrar.playToServer(StationActionPayload.TYPE, StationActionPayload.CODEC,
                (p, c) -> { if (c.player() instanceof ServerPlayer player) Sessions.handle(player, p); });
        registrar.playToClient(RecipeSyncPayload.TYPE, RecipeSyncPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientRecipes.accept(p));
        registrar.playToClient(RecipeChoicePayload.TYPE, RecipeChoicePayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.recipeChoices(p));
        registrar.playToClient(BrewingContentsPayload.TYPE, BrewingContentsPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientBrewing.apply(p));
        registrar.playToClient(TableContentsPayload.TYPE, TableContentsPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.tableContents(p));
        registrar.playToClient(FurnaceContentsPayload.TYPE, FurnaceContentsPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.furnaceContents(p));
        registrar.playToClient(FurnaceStatePayload.TYPE, FurnaceStatePayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.furnaceState(p));
        registrar.playToClient(CampfireProgressPayload.TYPE, CampfireProgressPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.campfireProgress(p));
        registrar.playToClient(EnchantDisplayPayload.TYPE, EnchantDisplayPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.enchantDisplay(p));
        registrar.playToClient(EnchantFxPayload.TYPE, EnchantFxPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.enchantFx(p));
        registrar.playToClient(MenuDisplayPayload.TYPE, MenuDisplayPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.menuDisplay(p));
        registrar.playToClient(MenuFxPayload.TYPE, MenuFxPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.menuFx(p));
        registrar.playToClient(SessionClosePayload.TYPE, SessionClosePayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.close(p));
        registrar.playToClient(AtStationPayload.TYPE, AtStationPayload.CODEC,
                (p, c) -> dev.dyslexic.cinematic.client.ClientPayloads.atStation(p));
    }
}
