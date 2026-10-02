package dev.rehan.passthrough;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

/** Hunt the nearest host person's proxy: often behind host walls Minecraft can't see, so line of sight doesn't count. */
final class ProxyTargetGoal extends NearestAttackableTargetGoal<ProxyEntity> {
	ProxyTargetGoal(final Mob mob) {
		super(mob, ProxyEntity.class, 10, false, false, target -> target instanceof ProxyEntity proxy && proxy.kind() == ProxyEntity.PED);
		this.targetConditions.ignoreInvisibilityTesting().ignoreLineOfSight();
	}
}
