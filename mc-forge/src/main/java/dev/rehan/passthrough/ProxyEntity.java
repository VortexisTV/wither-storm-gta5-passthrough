package dev.rehan.passthrough;

import java.util.List;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One of the host's people or vehicles, as Minecraft sees it: an undrawn stand-in that follows the real thing, so
 * that Minecraft's mobs have something to hunt and hit. Hits never hurt it; they go to the host, which hurts the
 * real one.
 *
 * <p>Anything that sets its velocity has taken hold of it (the Wither Storm's tractor beam does exactly that, every
 * tick, to whatever it pulls). While held it flies where it is pulled, and the host makes the real thing follow.
 */
public class ProxyEntity extends LivingEntity {
	public static final int PED = 0, VEHICLE = 1;
	private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(ProxyEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDimensions PED_SIZE = EntityDimensions.fixed(0.6F, 1.8F);
	private static final EntityDimensions VEHICLE_SIZE = EntityDimensions.fixed(2.4F, 1.6F);
	/** Ticks without a pull before it counts as let go. */
	private static final int HOLD_TICKS = 4;
	/** The host's handle for the real thing. */
	private int handle;
	private int pulledAt = Integer.MIN_VALUE / 2;
	private boolean eaten;

	public ProxyEntity(final EntityType<? extends ProxyEntity> type, final Level level) {
		super(type, level);
		this.setNoGravity(true);
		this.setSilent(true);
		this.noPhysics = true;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return LivingEntity.createLivingAttributes();
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		this.entityData.define(KIND, (byte) PED);
	}

	public int kind() {
		return this.entityData.get(KIND);
	}

	void bind(final int handle, final int kind) {
		this.handle = handle;
		this.entityData.set(KIND, (byte) kind);
		this.refreshDimensions();
	}

	public int handle() {
		return this.handle;
	}

	/** Whether something is pulling it this tick (or was a moment ago). */
	public boolean held() {
		return this.tickCount - this.pulledAt <= HOLD_TICKS;
	}

	public boolean eaten() {
		return this.eaten;
	}

	/** Swallowed: it is gone from the host's world too; the stand-in goes at the end of the tick. */
	public void markEaten() {
		this.eaten = true;
	}

	@Override
	public void setDeltaMovement(final Vec3 motion) {
		super.setDeltaMovement(motion);
		if (!this.level().isClientSide && motion.lengthSqr() > 1.0E-6) {
			this.pulledAt = this.tickCount;
		}
	}

	/** No life of its own: it only goes where it is pulled (and otherwise where the host puts it). */
	@Override
	public void tick() {
		if (this.level().isClientSide) {
			return;
		}

		if (this.held()) {
			Vec3 v = this.getDeltaMovement();
			this.setPos(this.getX() + v.x, this.getY() + v.y, this.getZ() + v.z);
		} else if (this.getDeltaMovement() != Vec3.ZERO) {
			super.setDeltaMovement(Vec3.ZERO);
		}
	}

	@Override
	public void lerpTo(final double x, final double y, final double z, final float yRot, final float xRot, final int steps, final boolean teleport) {
		this.setPos(x, y, z);
	}

	@Override
	public EntityDimensions getDimensions(final Pose pose) {
		return this.kind() == VEHICLE ? VEHICLE_SIZE : PED_SIZE;
	}

	@Override
	public void onSyncedDataUpdated(final EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (KIND.equals(key)) {
			this.refreshDimensions();
		}
	}

	@Override
	public boolean hurt(final DamageSource source, final float amount) {
		if (!this.level().isClientSide && !this.eaten) {
			MobWar.onProxyHit(this, source, amount);
		}

		return false;
	}

	@Override
	public void kill() {
		this.discard();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void pushEntities() {
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	/** Server only: there it is what lets mobs' arrows hit; the player's crosshair never lands on one. */
	@Override
	public boolean isPickable() {
		return !this.level().isClientSide;
	}

	@Override
	public boolean ignoreExplosion() {
		return true; // every Minecraft explosion is one in the host's world already
	}

	@Override
	public boolean canBeAffected(final MobEffectInstance effect) {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean canChangeDimensions() {
		return false;
	}

	@Override
	protected boolean shouldDropLoot() {
		return false;
	}

	@Override
	public boolean shouldShowName() {
		return false;
	}

	@Override
	public Iterable<ItemStack> getArmorSlots() {
		return List.of();
	}

	@Override
	public ItemStack getItemBySlot(final EquipmentSlot slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItemSlot(final EquipmentSlot slot, final ItemStack stack) {
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}
}
