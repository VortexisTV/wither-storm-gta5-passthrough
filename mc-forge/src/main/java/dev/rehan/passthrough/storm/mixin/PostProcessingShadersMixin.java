package dev.rehan.passthrough.storm.mixin;

import dev.rehan.passthrough.Passthrough;
import nonamecrackers2.witherstormmod.client.shader.PostProcessingShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The formidibomb's chromatic aberration is a full-screen pass over the finished frame. By then the frame holds only
 * the overlay layer (hand, HUD) for the host, and the pass would turn it opaque: the host's picture would go black.
 */
@Mixin(value = PostProcessingShaders.class, remap = false)
abstract class PostProcessingShadersMixin {
	@Inject(method = "shouldRenderChromaticAberration()Z", at = @At("HEAD"), cancellable = true, remap = false)
	private void passthrough$noFullScreenPass(final CallbackInfoReturnable<Boolean> cir) {
		if (Passthrough.active) {
			cir.setReturnValue(false);
		}
	}
}
