package dev.norrmi.ingameblocks.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SlabBlock;

/** A half block. Two halves only merge into a double slab if they are the same design. */
public class SlabDesignBlock extends SlabBlock implements DesignBlock {
	public static final BlockKind KIND = BlockKind.SLAB;

	public SlabDesignBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(DesignProperties.D256);
	}

	@Override
	protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
		if (!super.canBeReplaced(state, context)) {
			return false;
		}
		int held = Designs.designOf(context.getItemInHand(), KIND);
		return held < 0 || held == this.design(state);
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
