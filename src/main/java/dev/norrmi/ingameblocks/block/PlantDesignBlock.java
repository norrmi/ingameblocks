package dev.norrmi.ingameblocks.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Flowers, grass and other plants: the picture is shown as a cross, like a flower. */
public class PlantDesignBlock extends VegetationBlock implements DesignBlock {
	public static final BlockKind KIND = BlockKind.PLANT;
	private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 13.0, 14.0);

	public PlantDesignBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(DesignProperties.D256);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE.move(state.getOffset(pos));
	}

	/** Plants of this mod grow on soil like flowers, and also on any block with a solid top. */
	@Override
	protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
		return super.mayPlaceOn(state, level, pos) || state.isFaceSturdy(level, pos, Direction.UP);
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
