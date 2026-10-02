package dev.rehan.passthrough;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's mobs vs the host's people. The host lists its people ("peds") and vehicles ("vehs"); each gets an
 * undrawn {@link ProxyEntity} here that follows it and that hostile mobs hunt. A mob's hit on a proxy goes back to
 * the host ("mobhit"), which hurts the real person. The other way round, the host keeps a stand-in for every mob
 * ("mobs") that its police shoot at, and sends the damage back ("mobdmg").
 *
 * <p>A proxy that something takes hold of (the Wither Storm's tractor beam) is carried off by Minecraft, and the
 * host makes the real thing follow ("grab"); one that is swallowed is gone from the host's world too ("eaten").
 */
public final class MobWar {
	/** Mobs hunt proxies this far away (blocks). */
	private static final double FOLLOW_RANGE = 48.0;
	/** Host people further than this from the player aren't mirrored, nor mobs reported (blocks). */
	private static final double REPORT_RANGE = 96.0;
	/** The Wither Storm and its parts: they choose their own prey (the mod's AI), and shrug off the host's bullets. */
	private static final Set<String> STORM_KIN = Set.of("wither_storm", "wither_storm_segment", "wither_storm_head", "tentacle", "withered_symbiont", "command_block");
	private static final String STORM_NAMESPACE = "witherstormmod";

	/** The newest lists from the host, flattened [handle, x, y, z, ...] (link thread -> server tick). */
	private static final AtomicReference<double[]> peds = new AtomicReference<>();
	private static final AtomicReference<double[]> vehicles = new AtomicReference<>();
	private static volatile long pedsNanos, vehiclesNanos;
	/** Host damage to mobs, [entity id, amount] (link thread -> server tick). */
	private static final ConcurrentLinkedQueue<double[]> damage = new ConcurrentLinkedQueue<>();

	// server thread only
	private static final Map<Integer, ProxyEntity> proxies = new HashMap<>();
	private static final Map<Integer, Integer> missingTicks = new HashMap<>();
	/** Handles of things that were swallowed, and the tick it happened: no new proxy for those (the host removes them). */
	private static final Map<Integer, Integer> gone = new HashMap<>();
	private static boolean reportedMobs;
	private static boolean reportedHeld;
	private static int ticks;

	private MobWar() {
	}

	/** Brain-driven hostiles: they pick targets from brain memories, not goals, so they'd never join the fight. */
	private static final Set<String> BRAIN_MOBS = Set.of("piglin", "piglin_brute", "hoglin", "zoglin", "warden");

	public static boolean isProxy(final Entity e) {
		return e instanceof ProxyEntity;
	}

	public static boolean stormKin(final Entity e) {
		ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
		return STORM_NAMESPACE.equals(key.getNamespace()) && STORM_KIN.contains(key.getPath());
	}

	/** Mobs that join the fight: hostile, goal-driven ones. */
	private static boolean fighter(final Entity e) {
		return e instanceof Mob && e instanceof Enemy && !isProxy(e) && !BRAIN_MOBS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
	}

	public static void peds(final double[] flat) {
		peds.set(flat);
		pedsNanos = System.nanoTime();
	}

	public static void vehicles(final double[] flat) {
		vehicles.set(flat);
		vehiclesNanos = System.nanoTime();
	}

	public static void damage(final int id, final double amount) {
		damage.add(new double[] {id, amount});
	}

	/** New mobs (and mobs loaded again) hunt proxies. Returns false for an entity that shouldn't join the world. */
	static boolean onEntityLoad(final Entity e, final ServerLevel level) {
		if (e instanceof ProxyEntity proxy) {
			return proxies.get(proxy.handle()) == proxy; // a stale one from an earlier session
		}

		if (!fighter(e) || stormKin(e)) {
			return true;
		}

		Mob mob = (Mob) e;
		GoalSelector targets = mob.targetSelector;
		if (targets.getAvailableGoals().stream().noneMatch(w -> w.getGoal() instanceof ProxyTargetGoal)) {
			targets.addGoal(2, new ProxyTargetGoal(mob));
		}

		AttributeInstance range = mob.getAttribute(Attributes.FOLLOW_RANGE);
		if (range != null && range.getBaseValue() < FOLLOW_RANGE) {
			range.setBaseValue(FOLLOW_RANGE);
		}

		return true;
	}

	/** A proxy was hurt (server thread; the damage itself never lands). */
	static void onProxyHit(final ProxyEntity proxy, final DamageSource source, final float amount) {
		Entity attacker = source.getEntity();
		if (source.getDirectEntity() instanceof Projectile projectile && !(attacker instanceof Player)) {
			projectile.discard(); // a skeleton's arrow ends in the person it hit
		}

		// explosions are the host's own already (every Minecraft explosion is mirrored); Steve's hits are handled there too
		if (attacker == null || attacker instanceof Player || source.is(DamageTypeTags.IS_EXPLOSION) || amount > 1.0E6F) {
			return;
		}

		String kind = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath();
		Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"mobhit\",\"h\":%d,\"d\":%.2f,\"from\":[%.3f,%.3f,%.3f],\"k\":\"%s\"}",
			proxy.handle(), amount, attacker.getX(), attacker.getY(), attacker.getZ(), kind));
	}

	/** Every server tick (server thread). */
	static void tick(final MinecraftServer s) {
		ServerLevel level = s.overworld();
		ticks++;
		syncProxies(level);
		applyDamage(level);
		if (Passthrough.active) {
			reportHeld();
			reportMobs(s, level);
		}
	}

	private static void syncProxies(final ServerLevel level) {
		long now = System.nanoTime();
		if (!proxies.isEmpty() && now - pedsNanos > 1_500_000_000L && now - vehiclesNanos > 1_500_000_000L) {
			clearProxies(); // the host stopped sending: nobody to hunt
			return;
		}

		gone.values().removeIf(at -> ticks - at > 200);
		Set<Integer> seen = new HashSet<>();
		boolean any = sync(level, peds.getAndSet(null), ProxyEntity.PED, seen);
		any |= sync(level, vehicles.getAndSet(null), ProxyEntity.VEHICLE, seen);

		// the swallowed go now (not while the storm is still in the middle of eating them)
		for (Iterator<Map.Entry<Integer, ProxyEntity>> it = proxies.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Integer, ProxyEntity> e = it.next();
			if (e.getValue().eaten() || e.getValue().isRemoved()) {
				e.getValue().discard();
				missingTicks.remove(e.getKey());
				it.remove();
			}
		}

		if (!any) {
			return;
		}

		// hysteresis: a person missing from a few lists in a row (out of range, dead, despawned) loses their proxy
		for (Iterator<Map.Entry<Integer, ProxyEntity>> it = proxies.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Integer, ProxyEntity> e = it.next();
			if (seen.contains(e.getKey())) {
				continue;
			}

			int missing = missingTicks.merge(e.getKey(), 1, Integer::sum);
			if (missing > 12) {
				e.getValue().discard();
				missingTicks.remove(e.getKey());
				it.remove();
			}
		}
	}

	private static boolean sync(final ServerLevel level, final double[] p, final int kind, final Set<Integer> seen) {
		if (p == null) {
			// no new list of this kind this tick: its proxies stay as they are
			proxies.forEach((handle, proxy) -> {
				if (proxy.kind() == kind) {
					seen.add(handle);
				}
			});
			return false;
		}

		for (int i = 0; i + 3 < p.length; i += 4) {
			int handle = (int) p[i];
			double x = p[i + 1], y = p[i + 2], z = p[i + 3];
			if (gone.containsKey(handle)) {
				continue;
			}

			seen.add(handle);
			ProxyEntity proxy = proxies.get(handle);
			if (proxy == null || proxy.isRemoved()) {
				proxy = Passthrough.PROXY.get().create(level);
				if (proxy == null) {
					continue;
				}

				proxy.bind(handle, kind);
				proxy.moveTo(x, y, z, 0.0F, 0.0F);
				proxies.put(handle, proxy); // before adding: the load event must not take it for a stale one
				if (!level.addFreshEntity(proxy)) {
					proxies.remove(handle);
					continue;
				}
			} else if (!proxy.held()) {
				proxy.setPos(x, y, z); // held: Minecraft carries it, and the host's follows
			}

			missingTicks.remove(handle);
		}

		return true;
	}

	/**
	 * What is being carried off, every tick while anything is: {"t":"grab","g":[[handle,kind,x,y,z,vx,vy,vz],...]}
	 * (feet position, velocity in blocks per second). An empty list once, when the last one is let go.
	 */
	private static void reportHeld() {
		StringBuilder b = null;
		for (ProxyEntity proxy : proxies.values()) {
			if (!proxy.held() || proxy.eaten() || proxy.isRemoved()) {
				continue;
			}

			Vec3 v = proxy.getDeltaMovement();
			b = b == null ? new StringBuilder("{\"t\":\"grab\",\"g\":[") : b.append(',');
			b.append(String.format(Locale.ROOT, "[%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f]", proxy.handle(), proxy.kind(), proxy.getX(), proxy.getY(), proxy.getZ(),
				v.x * 20.0, v.y * 20.0, v.z * 20.0));
		}

		if (b != null) {
			Passthrough.events.accept(b.append("]}").toString());
		} else if (reportedHeld) {
			Passthrough.events.accept("{\"t\":\"grab\",\"g\":[]}");
		}

		reportedHeld = b != null;
	}

	/** Something swallowed a proxy (the Wither Storm): the real thing goes with it. */
	public static void eaten(final ProxyEntity proxy) {
		if (proxy.eaten()) {
			return;
		}

		proxy.markEaten();
		gone.put(proxy.handle(), ticks);
		Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"eaten\",\"h\":%d,\"k\":%d,\"pos\":[%.3f,%.3f,%.3f]}",
			proxy.handle(), proxy.kind(), proxy.getX(), proxy.getY(), proxy.getZ()));
	}

	private static void applyDamage(final ServerLevel level) {
		for (double[] d; (d = damage.poll()) != null;) {
			if (!(level.getEntity((int) d[0]) instanceof Mob mob) || !mob.isAlive()) {
				continue;
			}

			// from the nearest person (a cop, most likely), so the mob turns on them
			LivingEntity from = nearestProxy(mob);
			DamageSource source = from != null ? level.damageSources().mobAttack(from) : level.damageSources().generic();
			mob.invulnerableTime = 0; // automatic fire lands several hits inside the usual 10-tick cooldown
			// the storm feels the host's bullets (and turns on whoever fired), but they don't bring it down
			mob.hurt(source, stormKin(mob) ? (float) Math.min(d[1], 0.02) : (float) d[1]);
		}
	}

	private static LivingEntity nearestProxy(final Mob mob) {
		LivingEntity best = null;
		double bestD = 48.0 * 48.0;
		for (ProxyEntity v : proxies.values()) {
			double dd = v.distanceToSqr(mob);
			if (!v.isRemoved() && v.kind() == ProxyEntity.PED && dd < bestD) {
				best = v;
				bestD = dd;
			}
		}

		return best;
	}

	/** {"t":"mobs","m":[[id,"zombie",x,y,z],...]}: every fighting mob near the player, every tick while there are any. */
	private static void reportMobs(final MinecraftServer s, final ServerLevel level) {
		ServerPlayer player = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
		if (player == null) {
			return;
		}

		List<Entity> near = level.getEntities((Entity) null, player.getBoundingBox().inflate(REPORT_RANGE), MobWar::fighter);
		if (near.isEmpty() && !reportedMobs) {
			return;
		}

		StringBuilder b = new StringBuilder("{\"t\":\"mobs\",\"m\":[");
		int n = 0;
		for (Entity e : near) {
			if (!e.isAlive()) {
				continue;
			}

			b.append(n++ == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%d,\"%s\",%.3f,%.3f,%.3f]",
				e.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e.getX(), e.getY(), e.getZ()));
		}

		Passthrough.events.accept(b.append("]}").toString());
		reportedMobs = n > 0;
	}

	/**
	 * Spawn `count` mobs of `kind` (e.g. "zombie") on the ground `minR`..`maxR` blocks from the player, within `arc`
	 * degrees either side of where the player faces (or of `yawOffset` from it); or, given `at` ({x, y, z}: a fixed
	 * spot, y near its ground), all round that spot instead, wherever the player is.
	 */
	public static void spawn(final String kind, final int count, final double minR, final double maxR, final double arc, final double yawOffset,
		final double[] at) {
		MinecraftServer s = WorldBridge.server();
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			ServerPlayer player = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
			ResourceLocation id = ResourceLocation.tryParse(kind);
			Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
			if (player == null || type.isEmpty()) {
				Passthrough.LOG.warn("spawnmobs: no player or unknown mob {}", kind);
				return;
			}

			ThreadLocalRandom random = ThreadLocalRandom.current();
			double cx = at != null ? at[0] : player.getX(), cy = at != null ? at[1] : player.getY(), cz = at != null ? at[2] : player.getZ();
			double baseYaw = at != null ? 0.0 : player.getYRot() + yawOffset, spread = at != null ? 180.0 : arc;
			int spawned = 0;
			for (int i = 0; i < count * 4 && spawned < count; i++) {
				double yaw = Math.toRadians(baseYaw + (random.nextDouble() * 2.0 - 1.0) * spread);
				double r = minR + random.nextDouble() * (maxR - minR);
				int x = (int) Math.floor(cx - Math.sin(yaw) * r), z = (int) Math.floor(cz + Math.cos(yaw) * r);
				BlockPos ground = groundAt(level, x, (int) Math.floor(cy), z);
				if (ground == null) {
					continue; // no host ground there (yet)
				}

				Entity e = type.get().spawn(level, ground, MobSpawnType.COMMAND);
				if (e instanceof Mob mob) {
					mob.setPersistenceRequired();
					spawned++;
				}
			}

			Passthrough.LOG.info("spawnmobs: {} x {}", spawned, kind);
		});
	}

	/** The free block above the host's ground near height y in column (x, z), or null. */
	static BlockPos groundAt(final ServerLevel level, final int x, final int y, final int z) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = 6; dy >= -12; dy--) {
			p.set(x, y + dy, z);
			if (!level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.above(2)).isAir()) {
				return p.above().immutable();
			}
		}

		return null;
	}

	/** Remove every fighting mob (not the player's other things). */
	public static void clearMobs() {
		MinecraftServer s = WorldBridge.server();
		if (s != null) {
			s.execute(() -> discardFighters(s.overworld()));
		}
	}

	/** Server thread. */
	static void discardFighters(final ServerLevel level) {
		List<Entity> all = new ArrayList<>();
		level.getAllEntities().forEach(all::add);
		all.stream().filter(e -> (fighter(e) && !stormKin(e)) || isProxy(e)).forEach(Entity::discard);
		proxies.clear();
		missingTicks.clear();
	}

	private static void clearProxies() {
		proxies.values().forEach(Entity::discard);
		proxies.clear();
		missingTicks.clear();
	}

	static void detach(final MinecraftServer s) {
		// the world is saved next: none of the fight is kept in it
		discardFighters(s.overworld());
		gone.clear();
		peds.set(null);
		vehicles.set(null);
		damage.clear();
		reportedMobs = false;
		reportedHeld = false;
	}
}
