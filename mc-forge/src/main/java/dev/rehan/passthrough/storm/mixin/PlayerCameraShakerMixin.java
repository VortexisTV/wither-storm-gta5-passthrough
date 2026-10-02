package dev.rehan.passthrough.storm.mixin;

import dev.rehan.passthrough.Passthrough;
import java.util.Locale;
import nonamecrackers2.witherstormmod.client.capability.PlayerCameraShaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The storm shakes the screen (its roars, its falls). Minecraft's camera is the host's here, so the host's camera
 * shakes instead, and Minecraft's picture follows it like any other camera move.
 */
@Mixin(value = PlayerCameraShaker.class, remap = false)
abstract class PlayerCameraShakerMixin {
	@Inject(method = "shake(FF)V", at = @At("HEAD"), remap = false)
	private void passthrough$shakeHost(final float duration, final float power, final CallbackInfo ci) {
		if (Passthrough.active) {
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"stormshake\",\"ticks\":%.0f,\"p\":%.2f}", duration, power));
		}
	}
}
