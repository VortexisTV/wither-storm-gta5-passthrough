package dev.rehan.passthrough.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the host tell a fireball or skull that it hit something (of the host's, that Minecraft can't see). */
@Mixin(Projectile.class)
public interface ProjectileInvoker {
	@Invoker("onHit")
	void passthrough$onHit(HitResult hit);
}
