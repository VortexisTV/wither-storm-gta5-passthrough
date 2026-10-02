package dev.rehan.passthrough.mixin.client;

import dev.rehan.passthrough.client.HostState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The host's camera replaces the player's: position, rotation and first/third person. (The roll, and the field of
 * view, go in through Forge's viewport event and GameRenderer.getFov.)
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow private boolean detached;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V", at = @At("TAIL"))
	private void passthrough$hostCamera(final BlockGetter level, final Entity entity, final boolean thirdPerson, final boolean mirrored, final float partialTick,
		final CallbackInfo ci) {
		HostState.Pose p = HostState.frame();
		if (p == null) {
			return;
		}

		this.setRotation(p.yaw(), p.pitch());
		double x = p.x(), y = p.y(), z = p.z();
		Entity player = Minecraft.getInstance().player;
		if (p.drive() && player != null) {
			// flight chase cam: the host framed Steve at p.p*; keep that framing exactly, wherever he is drawn now
			Vec3 at = player.getPosition(partialTick);
			x += at.x - p.px();
			y += at.y - p.py();
			z += at.z - p.pz();
		}

		this.setPosition(x, y, z);
		// GTA pulls its camera in to the head against walls: then Minecraft's camera would be inside Steve's head
		boolean inside = player != null && player.getEyePosition(partialTick).distanceToSqr(x, y, z) < 0.8 * 0.8;
		this.detached = !p.firstPerson() && !inside;
	}
}
