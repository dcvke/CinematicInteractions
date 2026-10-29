package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.*;

/** Vanilla 1.21.2 syncs displays, while the table reference also needs the full ingredient pattern. */
public record RecipeSyncPayload(boolean reset, List<RecipeHolder<?>> recipes) implements CustomPacketPayload {
    public static final Type<RecipeSyncPayload> TYPE = new Type<>(Cinematic.id("crafting_reference"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeSyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, RecipeSyncPayload::reset,
            RecipeHolder.STREAM_CODEC.apply(ByteBufCodecs.list()), RecipeSyncPayload::recipes, RecipeSyncPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static boolean vanillaSerializer(Recipe<?> recipe) {
        var key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return key != null && "minecraft".equals(key.getNamespace());
    }

    /**
     * The table's preview and reference book read these on the client. Vanilla serializers encode any loaded
     * recipe; another mod's dynamic recipe may accept only its registered singleton and disconnect the player
     * when given a loaded copy (Simple Locks, issue #5), so mod serializers stay server-side.
     */
    public static List<RecipeHolder<?>> reference(ServerPlayer player) {
        return player.level().recipeAccess().getRecipes().stream()
                .filter(h -> h.value() instanceof CraftingRecipe recipe && vanillaSerializer(recipe)).toList();
    }

    public static void send(ServerPlayer player) {
        if (!net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(player.connection, TYPE.id())) return;
        var recipes = reference(player);
        PacketDistributor.sendToPlayer(player, new RecipeSyncPayload(true, List.of()));
        // Bound each packet even for large modpacks; a fresh batch replaces the cache after /reload.
        for (int first = 0; first < recipes.size(); first += 32)
            PacketDistributor.sendToPlayer(player, new RecipeSyncPayload(false, recipes.subList(first, Math.min(first + 32, recipes.size()))));
    }
}
