package dev.dyslexic.cinematic.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;

/** Select the correct bottle/book geometry through NeoForge's position-aware model API. */
public final class StationModels extends DelegateBlockStateModel {
    private static final Map<BlockState, BlockStateModel> MODELS = new ConcurrentHashMap<>();
    private final BlockState state;

    private StationModels(BlockStateModel model, BlockState state) { super(model); this.state = state; }
    public static void reset() { MODELS.clear(); }

    public static BlockStateModel wrap(BlockStateModel model, BlockState state) {
        if (!state.is(Blocks.BREWING_STAND) && !state.is(Blocks.CHISELED_BOOKSHELF)) return model;
        MODELS.put(state, model);
        return new StationModels(model, state);
    }

    private BlockState displayed(BlockState state, BlockPos pos) {
        if (!ClientOnly.serverHasMod()) return state;
        if (state.is(Blocks.BREWING_STAND)) {
            for (var property : BrewingStandBlock.HAS_BOTTLE) state = state.setValue(property, false);
        } else {
            int mask = ShelfBooks.emptyMask(pos);
            for (int i = 0; i < 6; i++) if ((mask & 1 << i) != 0)
                state = state.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i), false);
        }
        return state;
    }

    @Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        MODELS.getOrDefault(displayed(state, BlockPos.ZERO), delegate).collectParts(random, parts);
    }

    @Override public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state,
            RandomSource random, List<BlockStateModelPart> parts) {
        BlockState visible = displayed(state, pos);
        MODELS.getOrDefault(visible, delegate).collectParts(level, pos, visible, random, parts);
    }

    @Override public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return null;
    }
}
