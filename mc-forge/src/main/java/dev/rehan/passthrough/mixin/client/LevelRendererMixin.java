package dev.rehan.passthrough.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No sky while a host is attached: the host's sky shows through wherever Minecraft drew nothing. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
	private void passthrough$noSky(final PoseStack poseStack, final Matrix4f projection, final float partialTick, final Camera camera, final boolean foggy,
		final Runnable setupFog, final CallbackInfo ci) {
		if (Passthrough.active) {
			ci.cancel();
		}
	}
}
