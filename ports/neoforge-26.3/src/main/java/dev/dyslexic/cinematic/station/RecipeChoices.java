package dev.dyslexic.cinematic.station;

import com.illusivesoulworks.polymorph.api.PolymorphApi;
import com.illusivesoulworks.polymorph.api.common.capability.IBlockEntityRecipeData;
import dev.dyslexic.cinematic.net.PacketDistributor;
import dev.dyslexic.cinematic.net.RecipeChoicePayload;
import dev.dyslexic.cinematic.station.Sessions.Session;
import dev.dyslexic.cinematic.table.TableGrid;
import dev.dyslexic.cinematic.table.TableStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import javax.annotation.Nullable;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Recipes that compete for the same input (Polymorph). Polymorph keeps the choice, per player for the crafting table and
 * the smithing table, per block for a furnace, and its own windows and the vanilla blocks follow it; the scenes show the
 * same choice and change it here. Without Polymorph nothing of this runs: the first recipe wins, as in vanilla.
 * Both sides number the competing recipes the same way ({@link #matching}), so a choice travels as an index.
 */
public final class RecipeChoices {
    private RecipeChoices() {}

    //? if >=26.3 {
    private static final boolean POLYMORPH = ModList.get().isLoaded("polymorph_plus");
    //?} else if <26.3 {
    /*private static final boolean POLYMORPH = ModList.get().isLoaded("polymorph");
    *///?}
    private static final Map<UUID, Long> LAST_INPUT = new HashMap<>();

    public static void forget(ServerPlayer player) { LAST_INPUT.remove(player.getUUID()); }
    public static void clear() { LAST_INPUT.clear(); }

    private static long fingerprint(ServerPlayer player, Session session) {
        ServerLevel level = player.level();
        long value = 31L * session.pos().hashCode() + session.kind().ordinal();
        if (session.kind() == StationKind.TABLE) {
            TableStore.Table table = TableStore.get(level).get(session.pos());
            if (table != null) for (ItemStack stack : table.items) value = value * 31 + 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount();
            value = value * 31 + java.util.Objects.hashCode(chosen(player));
        } else if (session.kind() == StationKind.SMITHING && player.containerMenu instanceof SmithingMenu menu) {
            for (int slot = 0; slot < 3; slot++) {
                ItemStack stack = menu.getSlot(slot).getItem();
                value = value * 31 + 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount();
            }
            value = value * 31 + java.util.Objects.hashCode(chosen(player));
        } else if (session.kind().isFurnace() && level.getBlockEntity(session.pos()) instanceof AbstractFurnaceBlockEntity furnace) {
            ItemStack stack = furnace.getItem(0);
            value = value * 31 + 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount();
            value = value * 31 + java.util.Objects.hashCode(Polymorph.chosen(furnace));
        }
        return value;
    }

    public static boolean available() {
        return POLYMORPH;
    }

    /** Every recipe of {@code type} the input makes something with, ordered by id (the order both sides count in). */
    public static <I extends RecipeInput, T extends Recipe<I>> List<RecipeHolder<T>> matching(Level level, RecipeType<T> type, I input) {
        List<RecipeHolder<T>> found = new ArrayList<>();
        if (!(level instanceof ServerLevel server)) return found;
        for (RecipeHolder<?> holder : server.getServer().getRecipeManager().getRecipes()) {
            if (holder.value().getType() != type) continue;
            @SuppressWarnings("unchecked") RecipeHolder<T> typed = (RecipeHolder<T>) holder;
            if (typed.value().matches(input, level)) found.add(typed);
        }
        //? if >=26.1 {
        found.removeIf(holder -> holder.value().assemble(input).isEmpty());
        //?} else if <26.1 {
        /*found.removeIf(holder -> holder.value().assemble(input, level.registryAccess()).isEmpty());
        *///?}
        found.sort(Comparator.comparing(holder -> holder.id().toString()));
        return found;
    }

    /** The recipe the player chose last (Polymorph's own record), if any. */
    @Nullable
    public static Identifier chosen(Player player) {
        return POLYMORPH ? Polymorph.chosen(player) : null;
    }

    /** The recipe a furnace was told to use for this input, or "" (nothing chosen, or no choice to make). */
    public static String furnaceChoice(AbstractFurnaceBlockEntity furnace, StationKind kind) {
        if (!POLYMORPH || furnace.getLevel() == null || furnace.getItem(0).isEmpty()) return "";
        var list = matching(furnace.getLevel(), cookingType(kind), new SingleRecipeInput(furnace.getItem(0)));
        if (list.size() < 2) return "";
        Identifier id = Polymorph.chosen(furnace);
        return id != null && list.stream().anyMatch(holder -> holder.id().identifier().equals(id)) ? id.toString() : "";
    }

    public static RecipeType<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> cookingType(StationKind kind) {
        return switch (kind) {
            case SMOKER -> RecipeType.SMOKING;
            case BLAST_FURNACE -> RecipeType.BLASTING;
            default -> RecipeType.SMELTING;
        };
    }

    /** The 1.21.11 client no longer receives all furnace/smithing recipes, so send the authoritative alternatives. */
    public static void sync(ServerPlayer player, Session session) {
        if (!POLYMORPH) return;
        long fingerprint = fingerprint(player, session);
        Long previous = LAST_INPUT.put(player.getUUID(), fingerprint);
        if (previous != null && previous.longValue() == fingerprint) return;
        ServerLevel level = player.level();
        List<? extends RecipeHolder<?>> holders = List.of();
        List<ItemStack> outputs = new ArrayList<>();
        Identifier selected = null;
        switch (session.kind()) {
            case TABLE -> {
                TableStore.Table table = TableStore.get(level).get(session.pos());
                if (table != null) {
                    CraftingInput input = CraftingInput.ofPositioned(3, 3, TableGrid.oriented(table.items, session.side())).input();
                    if (!input.isEmpty()) {
                        var recipes = matching(level, RecipeType.CRAFTING, input);
                        holders = recipes;
                        //? if >=26.1 {
                        for (var holder : recipes) outputs.add(holder.value().assemble(input));
                        //?} else if <26.1 {
                        /*for (var holder : recipes) outputs.add(holder.value().assemble(input, player.registryAccess()));
                        *///?}
                        selected = chosen(player);
                    }
                }
            }
            case SMITHING -> {
                if (player.containerMenu instanceof SmithingMenu menu) {
                    SmithingRecipeInput input = new SmithingRecipeInput(menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), menu.getSlot(2).getItem());
                    var recipes = matching(level, RecipeType.SMITHING, input);
                    holders = recipes;
                    //? if >=26.1 {
                    for (var holder : recipes) outputs.add(holder.value().assemble(input));
                    //?} else if <26.1 {
                    /*for (var holder : recipes) outputs.add(holder.value().assemble(input, player.registryAccess()));
                    *///?}
                    selected = chosen(player);
                }
            }
            case FURNACE, SMOKER, BLAST_FURNACE -> {
                if (level.getBlockEntity(session.pos()) instanceof AbstractFurnaceBlockEntity furnace && !furnace.getItem(0).isEmpty()) {
                    SingleRecipeInput input = new SingleRecipeInput(furnace.getItem(0));
                    var recipes = matching(level, cookingType(session.kind()), input);
                    holders = recipes;
                    //? if >=26.1 {
                    for (var holder : recipes) outputs.add(holder.value().assemble(input));
                    //?} else if <26.1 {
                    /*for (var holder : recipes) outputs.add(holder.value().assemble(input, player.registryAccess()));
                    *///?}
                    selected = Polymorph.chosen(furnace);
                }
            }
            default -> { return; }
        }
        if (outputs.size() < 2) outputs.clear();
        int inUse = -1;
        if (!outputs.isEmpty()) {
            for (int i = 0; i < holders.size(); i++) {
                if (holders.get(i).id().identifier().equals(selected)) { inUse = i; break; }
            }
            if (inUse < 0) {
                // Keep the scene's first preview and Polymorph's actual output in agreement.
                inUse = 0;
                RecipeHolder<?> first = holders.get(0);
                if (session.kind().isFurnace()) {
                    if (level.getBlockEntity(session.pos()) instanceof AbstractFurnaceBlockEntity furnace) {
                        Polymorph.choose(furnace, first);
                        furnace.setChanged();
                    }
                } else {
                    Polymorph.choose(player, first);
                    if (session.kind() == StationKind.SMITHING && player.containerMenu instanceof SmithingMenu menu) {
                        menu.createResult();
                        menu.broadcastChanges();
                    }
                }
            }
        }
        PacketDistributor.sendToPlayer(player, new RecipeChoicePayload(session.pos(), session.kind(), outputs, inUse));
    }

    /** CHOOSE: the {@code index}-th of the recipes competing for what lies in the station right now. */
    static void choose(ServerPlayer player, ServerLevel level, Session session, int index) {
        if (!POLYMORPH || index < 0) {
            return;
        }
        switch (session.kind()) {
            case TABLE -> {
                TableStore.Table table = TableStore.get(level).get(session.pos());
                if (table == null) return;
                CraftingInput input = CraftingInput.ofPositioned(3, 3, TableGrid.oriented(table.items, session.side())).input();
                var list = matching(level, RecipeType.CRAFTING, input);
                if (index < list.size()) Polymorph.choose(player, list.get(index));
            }
            case SMITHING -> {
                if (!(player.containerMenu instanceof SmithingMenu menu)) return;
                var input = new SmithingRecipeInput(menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), menu.getSlot(2).getItem());
                var list = matching(level, RecipeType.SMITHING, input);
                if (index >= list.size()) return;
                Polymorph.choose(player, list.get(index));
                // the result slot follows at once, as in Polymorph's own window
                menu.createResult();
                menu.broadcastChanges();
            }
            case FURNACE, SMOKER, BLAST_FURNACE -> {
                if (!(level.getBlockEntity(session.pos()) instanceof AbstractFurnaceBlockEntity furnace) || furnace.getItem(0).isEmpty()) return;
                var list = matching(level, cookingType(session.kind()), new SingleRecipeInput(furnace.getItem(0)));
                if (index < list.size()) Polymorph.choose(furnace, list.get(index));
                furnace.setChanged();
                dev.dyslexic.cinematic.furnace.FurnaceActions.sendState(player, session);
            }
            default -> {}
        }
    }

    /** Polymorph's API, loaded only with Polymorph present. */
    private static final class Polymorph {
        @Nullable
        static Identifier chosen(Player player) {
            RecipeHolder<?> holder = PolymorphApi.getInstance().getPlayerRecipeData(player).getSelectedRecipe();
            return holder == null ? null : holder.id().identifier();
        }

        @Nullable
        static Identifier chosen(BlockEntity blockEntity) {
            IBlockEntityRecipeData data = PolymorphApi.getInstance().getBlockEntityRecipeData(blockEntity);
            RecipeHolder<?> holder = data == null ? null : data.getSelectedRecipe();
            return holder == null ? null : holder.id().identifier();
        }

        static void choose(Player player, RecipeHolder<?> holder) {
            PolymorphApi.getInstance().getPlayerRecipeData(player).selectRecipe(holder);
        }

        static void choose(BlockEntity blockEntity, RecipeHolder<?> holder) {
            IBlockEntityRecipeData data = PolymorphApi.getInstance().getBlockEntityRecipeData(blockEntity);
            if (data != null) data.selectRecipe(holder);
        }
    }
}
