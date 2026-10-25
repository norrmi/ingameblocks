package dev.norrmi.ingameblocks.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.CarpetBlock;

/** A carpet (1 pixel high). The texture of the "up" face is used. */
public class CarpetDesignBlock extends CarpetBlock implements DesignBlock {
	public static final BlockKind KIND = BlockKind.CARPET;

	public CarpetDesignBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.defaultBlockState().setValue(DesignProperties.SEE_THROUGH, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(DesignProperties.D256);
		builder.add(DesignProperties.SEE_THROUGH);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return Designs.withSeeThrough(super.getStateForPlacement(context), context, KIND);
	}
	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
			BlockPos neighborPos, BlockState neighborState, RandomSource random) {
		return Designs.fixSeeThrough(super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random), KIND);
	}

	/** A see-through design must not hide the faces of the blocks next to it. */
	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return state.getValue(DesignProperties.SEE_THROUGH) ? Shapes.empty() : super.getOcclusionShape(state);
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return state.getValue(DesignProperties.SEE_THROUGH) || super.propagatesSkylightDown(state);
	}

	@Override
	public BlockKind kind() {
		return KIND;
	}

	@Override
	protected SoundType getSoundType(BlockState state) {
		return Designs.sound(KIND, this.design(state), super.getSoundType(state));
	}

	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		return Designs.cloneStack(this, state);
	}
}
