package dev.rehan.passthrough.mixin.client;

import dev.rehan.passthrough.client.HostState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Holding one of the host's guns (drawn by the host): Steve aims with both arms forward. */
@Mixin(PlayerRenderer.class)
abstract class PlayerRendererMixin {
	@Inject(
		method = "getArmPose(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void passthrough$gunPose(final AbstractClientPlayer player, final InteractionHand hand, final CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
		HostState.Pose p = HostState.frame();
		if (p != null && p.gun() && player == Minecraft.getInstance().player) {
			cir.setReturnValue(hand == InteractionHand.MAIN_HAND ? HumanoidModel.ArmPose.CROSSBOW_HOLD : HumanoidModel.ArmPose.EMPTY);
		}
	}
}
