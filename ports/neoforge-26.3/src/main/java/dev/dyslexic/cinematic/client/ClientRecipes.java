package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.net.RecipeSyncPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.crafting.*;

public final class ClientRecipes {
    private static final List<RecipeHolder<CraftingRecipe>> CRAFTING = new ArrayList<>();
    static int revision;
    static List<RecipeHolder<CraftingRecipe>> crafting() { return CRAFTING; }
    public static java.util.Optional<RecipeHolder<CraftingRecipe>> match(CraftingInput input, net.minecraft.world.level.Level level) {
        return CRAFTING.stream().filter(h -> h.value().matches(input, level)).findFirst();
    }
    static void clear() { CRAFTING.clear(); revision++; }
    public static void accept(RecipeSyncPayload payload) {
        if (payload.reset()) clear();
        for (var holder : payload.recipes()) if (holder.value() instanceof CraftingRecipe recipe)
            CRAFTING.add(new RecipeHolder<>(holder.id(), recipe));
        revision++;
    }
}
