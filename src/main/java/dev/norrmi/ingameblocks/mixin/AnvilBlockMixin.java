package dev.norrmi.ingameblocks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.state.BlockState;

import dev.norrmi.ingameblocks.block.AnvilDesignBlock;

/**
 * Vanilla turns an anvil into "chipped" / "damaged" / nothing when it is used or falls. A custom anvil would become a
 * plain vanilla anvil that way, so custom anvils simply never get damaged.
 */
@Mixin(AnvilBlock.class)
public class AnvilBlockMixin {
	@Inject(method = "damage", at = @At("HEAD"), cancellable = true)
	private static void ingameblocks$keepCustomAnvil(BlockState state, CallbackInfoReturnable<BlockState> cir) {
		if (state.getBlock() instanceof AnvilDesignBlock) {
			cir.setReturnValue(state);
		}
	}
}
