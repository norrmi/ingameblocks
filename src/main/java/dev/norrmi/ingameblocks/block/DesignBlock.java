package dev.norrmi.ingameblocks.block;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Implemented by every InGameBlocks block. */
public interface DesignBlock {
	BlockKind kind();

	default IntegerProperty designProperty() {
		return DesignProperties.forKind(this.kind());
	}

	default int design(BlockState state) {
		return state.getValue(this.designProperty());
	}
}
