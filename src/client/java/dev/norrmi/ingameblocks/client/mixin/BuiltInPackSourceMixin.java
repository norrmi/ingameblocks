package dev.norrmi.ingameblocks.client.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;

import dev.norrmi.ingameblocks.client.pack.DynamicPackSource;

/** Adds the generated resource pack to the client's pack list (same hook that Fabric API uses for mod packs). */
@Mixin(BuiltInPackSource.class)
public class BuiltInPackSourceMixin {
	@Inject(method = "loadPacks", at = @At("RETURN"))
	private void ingameblocks$addDynamicPack(Consumer<Pack> consumer, CallbackInfo ci) {
		if ((Object) this instanceof ClientPackSource) {
			DynamicPackSource.load(consumer);
		}
	}
}
