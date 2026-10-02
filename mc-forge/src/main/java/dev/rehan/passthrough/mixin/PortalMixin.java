package dev.rehan.passthrough.mixin;

import dev.rehan.passthrough.Passthrough;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Only this world is the host's: while a host is attached nothing goes through nether portals (the Nether comes out
 * of them instead). On the client the usual portal overlay and sound still play.
 */
@Mixin(Entity.class)
abstract class PortalMixin {
	@Inject(method = "handleInsidePortal(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
	private void passthrough$noTravel(final BlockPos pos, final CallbackInfo ci) {
		if (Passthrough.active && !((Entity) (Object) this).level().isClientSide()) {
			ci.cancel();
		}
	}
}
