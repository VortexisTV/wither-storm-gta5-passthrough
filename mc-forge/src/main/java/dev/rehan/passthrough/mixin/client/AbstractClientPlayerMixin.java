package dev.rehan.passthrough.mixin.client;

import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a host is attached the player is classic Steve (the wide-armed default skin), whatever the account's skin. */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkinTextureLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("HEAD"), cancellable = true)
	private void passthrough$steve(final CallbackInfoReturnable<ResourceLocation> cir) {
		if (Passthrough.active && !Boolean.getBoolean("passthrough.ownSkin")) {
			cir.setReturnValue(DefaultPlayerSkin.getDefaultSkin());
		}
	}

	@Inject(method = "getModelName()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private void passthrough$wideArms(final CallbackInfoReturnable<String> cir) {
		if (Passthrough.active && !Boolean.getBoolean("passthrough.ownSkin")) {
			cir.setReturnValue("default");
		}
	}
}
