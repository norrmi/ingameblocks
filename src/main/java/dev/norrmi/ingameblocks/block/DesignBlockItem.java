package dev.norrmi.ingameblocks.block;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Item of an InGameBlocks block. Its name is the name of the design stored in the item. */
public class DesignBlockItem extends BlockItem {
	private final BlockKind kind;

	public DesignBlockItem(Block block, BlockKind kind, Properties properties) {
		super(block, properties);
		this.kind = kind;
	}

	public BlockKind kind() {
		return this.kind;
	}

	@Override
	public Component getName(ItemStack stack) {
		int design = Designs.designOf(stack, this.kind);
		if (design < 0) {
			return super.getName(stack);
		}
		return Designs.name(this.kind, design);
	}
}
