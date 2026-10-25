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
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * A block that faces the player in all 6 directions, like a piston, observer or dispenser.
 * The "north" texture is the front, "south" the back. (Decorative: it does not push blocks.)
 */
public class PistonDesignBlock extends DirectionalBlock implements DesignBlock {
	public static final BlockKind KIND = BlockKind.PISTON;

	public PistonDesignBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.defaultBlockState().setValue(DesignProperties.SEE_THROUGH, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, DesignProperties.D128);
		builder.add(DesignProperties.SEE_THROUGH);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return Designs.withSeeThrough(this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite()), context, KIND);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
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
