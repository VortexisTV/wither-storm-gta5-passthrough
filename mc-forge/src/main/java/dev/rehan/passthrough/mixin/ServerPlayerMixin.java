package dev.rehan.passthrough.mixin;

import dev.rehan.passthrough.Passthrough;
import java.util.Locale;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft moving the player (ender pearls, /tp) moves the host's player too; otherwise the host would pull it back. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "teleportTo(DDD)V", at = @At("HEAD"))
	private void passthrough$teleported(final double x, final double y, final double z, final CallbackInfo ci) {
		passthrough$tell(x, y, z);
	}

	@Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("HEAD"))
	private void passthrough$teleportedTo(final ServerLevel level, final double x, final double y, final double z, final Set<RelativeMovement> relative,
		final float yaw, final float pitch, final CallbackInfoReturnable<Boolean> cir) {
		if (relative.isEmpty() && level == ((ServerPlayer) (Object) this).level()) {
			passthrough$tell(x, y, z);
		}
	}

	private static void passthrough$tell(final double x, final double y, final double z) {
		if (Passthrough.active) {
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"pteleport\",\"pos\":[%.3f,%.3f,%.3f]}", x, y, z));
		}
	}
}
