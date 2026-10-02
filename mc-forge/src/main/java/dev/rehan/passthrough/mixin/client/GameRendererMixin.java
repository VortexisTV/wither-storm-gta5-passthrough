package dev.rehan.passthrough.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.client.FrameExporter;
import dev.rehan.passthrough.client.HostState;
import dev.rehan.passthrough.client.PlayerSync;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The frame is split into a world layer and an overlay layer (hand, HUD) for the host, rendered with the host's
 * field of view, and with one far plane for everything.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	/**
	 * Far plane while a host is attached, in multiples of the render distance. It is what Cracker's Wither Storm
	 * Mod draws far-away storms with (its "distant renderer" swaps the projection for this one): with the level
	 * drawn the same way, the whole depth buffer is in one scale and the host can place every pixel. The near plane
	 * sets the depth precision, so the level loses nothing by it.
	 */
	private static final float FAR_SCALE = 180.0F;
	@Shadow @Final Minecraft minecraft;
	@Shadow private float renderDistance;

	@Inject(method = "render(FJZ)V", at = @At("HEAD"))
	private void passthrough$beginFrame(final float partialTick, final long nanos, final boolean renderLevel, final CallbackInfo ci) {
		HostState.beginFrame();
	}

	@Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"))
	private void passthrough$followHost(final float partialTick, final long nanos, final PoseStack poseStack, final CallbackInfo ci) {
		PlayerSync.frame(partialTick);
	}

	@Inject(
		method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
		at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=hand")
	)
	private void passthrough$captureWorld(final float partialTick, final long nanos, final PoseStack poseStack, final CallbackInfo ci) {
		FrameExporter.captureWorld(this.minecraft.getMainRenderTarget());
	}

	@Inject(method = "render(FJZ)V", at = @At("RETURN"))
	private void passthrough$captureOverlay(final float partialTick, final long nanos, final boolean renderLevel, final CallbackInfo ci) {
		FrameExporter.captureOverlay(this.minecraft.getMainRenderTarget());
	}

	@Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)D", at = @At("HEAD"), cancellable = true)
	private void passthrough$hostFov(final Camera camera, final float partialTick, final boolean useFovSetting, final CallbackInfoReturnable<Double> cir) {
		HostState.Pose p = HostState.frame();
		if (p != null && useFovSetting) {
			cir.setReturnValue((double) p.fov());
		}
	}

	@Inject(method = "getDepthFar()F", at = @At("RETURN"), cancellable = true)
	private void passthrough$oneFarPlane(final CallbackInfoReturnable<Float> cir) {
		if (Passthrough.active) {
			cir.setReturnValue(Math.max(cir.getReturnValueF(), this.renderDistance * FAR_SCALE));
		}
	}
}
