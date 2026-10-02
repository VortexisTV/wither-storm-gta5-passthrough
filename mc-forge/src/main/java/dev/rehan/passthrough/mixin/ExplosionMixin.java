package dev.rehan.passthrough.mixin;

import dev.rehan.passthrough.WorldBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every server explosion (TNT, creepers, the Wither Storm's flaming skulls, ...) is reported to the host so it can set off its own. */
@Mixin(Explosion.class)
abstract class ExplosionMixin {
	@Shadow @Final private Level level;
	@Shadow @Final private double x;
	@Shadow @Final private double y;
	@Shadow @Final private double z;
	@Shadow @Final private float radius;
	@Shadow @Final private Entity source;

	@Inject(method = "explode()V", at = @At("HEAD"))
	private void passthrough$report(final CallbackInfo ci) {
		if (!this.level.isClientSide()) {
			WorldBridge.onExplosion(new Vec3(this.x, this.y, this.z), this.radius,
				this.source == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(this.source.getType()).getPath());
		}
	}
}
