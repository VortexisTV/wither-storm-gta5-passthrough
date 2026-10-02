package dev.rehan.passthrough.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.client.HostState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holding one of the host's guns: Steve's own items aren't drawn (the host puts the gun in his hands). */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void passthrough$noItemWithGun(final LivingEntity entity, final ItemStack stack, final ItemDisplayContext context, final HumanoidArm arm,
		final PoseStack poseStack, final MultiBufferSource buffers, final int light, final CallbackInfo ci) {
		HostState.Pose p = HostState.frame();
		if (p != null && p.gun() && entity == Minecraft.getInstance().player) {
			ci.cancel();
		}
	}
}
