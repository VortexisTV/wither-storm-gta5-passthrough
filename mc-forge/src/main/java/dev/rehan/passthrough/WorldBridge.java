package dev.rehan.passthrough;

import dev.rehan.passthrough.mixin.ProjectileInvoker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Server-side half: the host's collision as undrawn ground blocks, commands, and events back to the host. */
public final class WorldBridge {
	private static volatile MinecraftServer server;
	/** Ground we placed (so a reset only removes ours, never the player's builds). */
	private static final Set<BlockPos> ground = ConcurrentHashMap.newKeySet();
	/** Block changes this tick (server thread only): true = now solid, false = gone. Sent at the end of the tick. */
	private static final Map<BlockPos, Boolean> changes = new LinkedHashMap<>();
	/** Ground of ours that something tore out this tick (server thread only): filled in again, as untearable barrier. */
	private static final List<BlockPos> torn = new ArrayList<>();
	/** While placing or removing the host's own ground: those changes aren't news to the host (server thread only). */
	private static boolean placingGround;
	private static final int NO_FLOOR = Integer.MIN_VALUE;
	/** The host's ground level by the player (see {@link #terrain}). */
	private static volatile int floor = NO_FLOOR;

	private WorldBridge() {
	}

	static void attach(final MinecraftServer s) {
		server = s;
	}

	static void detach() {
		server = null;
		floor = NO_FLOOR;
		ground.clear();
		changes.clear();
		torn.clear();
	}

	public static boolean ready() {
		return server != null;
	}

	public static MinecraftServer server() {
		return server;
	}

	/** The first player (the host's), or null. Server thread. */
	public static ServerPlayer player(final MinecraftServer s) {
		return s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
	}

	/** The host's ground: the tearable kind, or the barrier it becomes once torn (and under it). */
	public static boolean isHostGround(final BlockState state) {
		return state.is(Blocks.BARRIER) || state.is(Passthrough.GROUND.get());
	}

	/**
	 * Columns of solid ground from the host: {x, z, yBottom, yTop, ...} in block coordinates (inclusive). Only air is
	 * replaced. The surface layer is the tearable ground block, what is under it barrier.
	 */
	public static void solid(final int[] columns) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			BlockState barrier = Blocks.BARRIER.defaultBlockState();
			BlockState surface = Passthrough.GROUND.get().defaultBlockState();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			placingGround = true;
			try {
				for (int i = 0; i + 3 < columns.length; i += 4) {
					for (int y = columns[i + 2]; y <= columns[i + 3]; y++) {
						pos.set(columns[i], y, columns[i + 1]);
						if (!level.isInWorldBounds(pos)) {
							continue;
						}

						BlockState there = level.getBlockState(pos);
						if (there.isAir()) {
							level.setBlock(pos, y == columns[i + 3] ? surface : barrier, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
							ground.add(pos.immutable());
						} else if (isHostGround(there)) {
							// ground a session that didn't end cleanly left in the save: it is ours again
							ground.add(pos.immutable());
						}
					}
				}
			} finally {
				placingGround = false;
			}
		});
	}

	/** Remove all the ground we placed (e.g. when the host teleports somewhere else). */
	public static void clearSolid() {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			placingGround = true;
			try {
				for (BlockPos pos : ground) {
					if (isHostGround(level.getBlockState(pos))) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
					}
				}
			} finally {
				placingGround = false;
			}

			ground.clear();
			torn.clear();
		});
	}

	/** Run a command as the server (op). Results go to the log, not to chat (sendCommandFeedback is off). */
	public static void command(final String command) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			Passthrough.LOG.info("command: {}", command);
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), command);
		});
	}

	private static boolean solidForHost(final ServerLevel level, final BlockPos pos, final BlockState state) {
		// the Nether's ground is the host's own ground turned: nothing to collide with that isn't there already
		return !state.isAir() && !isHostGround(state) && !state.getCollisionShape(level, pos).isEmpty() && !Nether.isGround(pos);
	}

	/** Arrows the host already hit something with (they stay where they hit and aren't reported again). */
	private static final String HIT_TAG = "passthrough_hit";

	/**
	 * The terrain height at a column for something that flies a set height above the ground: `height` as Minecraft
	 * found it, or, where Minecraft has nothing there at all (the host's ground only reaches so far from the player),
	 * the host's ground level by the player.
	 */
	public static int terrain(final Level level, final int height) {
		int f = floor;
		return level.isClientSide() || f == NO_FLOOR || height > level.getMinBuildHeight() + 1 ? height : f;
	}

	/**
	 * The server is stopping, and the world is saved next: the host's ground comes out of it (what is loaded of it),
	 * so the next session doesn't start on top of ground from wherever, and at whatever height, the last one ended.
	 */
	static void removeGround(final MinecraftServer s) {
		ServerLevel level = s.overworld();
		placingGround = true;
		try {
			for (BlockPos pos : ground) {
				if (level.isLoaded(pos) && isHostGround(level.getBlockState(pos))) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		} finally {
			placingGround = false;
		}
	}

	/** Where the host's ground is under the player: the block above its top ground block there. */
	private static void findFloor(final MinecraftServer s) {
		ServerPlayer player = player(s);
		if (player == null || player.level() != s.overworld()) {
			return;
		}

		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = 1; dy >= -24; dy--) {
			p.set(player.getBlockX(), player.getBlockY() + dy, player.getBlockZ());
			if (isHostGround(player.level().getBlockState(p))) {
				floor = p.getY() + 1;
				return;
			}
		}

		if (floor == NO_FLOOR) {
			floor = player.getBlockY();
		}
	}

	/** Every server tick: block changes, and projectiles in flight for the host to trace through its own world. */
	static void tick(final MinecraftServer s) {
		mend(s.overworld());
		flush(s);
		if (Passthrough.active && (s.getTickCount() & 7) == 0) {
			findFloor(s);
		}

		if (Passthrough.active) {
			reportProjectiles(s.overworld());
		}

		MobWar.tick(s);
		Nether.tick(s);
	}

	/** Ground that was torn out is solid again (the host's ground is still there), but can't be torn twice. */
	private static void mend(final ServerLevel level) {
		if (torn.isEmpty()) {
			return;
		}

		placingGround = true;
		try {
			for (BlockPos pos : torn) {
				if (level.getBlockState(pos).isAir()) {
					level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		} finally {
			placingGround = false;
			torn.clear();
		}
	}

	/**
	 * What flies, for the host to trace through its own world: {"t":"proj","p":[[id,kind,x,y,z],...]}. Steve's arrows
	 * (bow, crossbow) and crossbow fireworks; and every fireball and skull ("skull": ghasts', the Wither Storm's),
	 * which otherwise would sail through the host's buildings. Mobs' arrows aren't traced: they hit its people's
	 * proxies here.
	 */
	private static void reportProjectiles(final ServerLevel level) {
		StringBuilder b = null;
		for (Entity e : level.getAllEntities()) {
			String kind = null;
			if (!(e instanceof Projectile projectile)) {
				continue;
			}

			if (e instanceof AbstractHurtingProjectile) {
				kind = "skull";
			} else if (!(projectile.getOwner() instanceof Player)) {
				continue;
			} else if (e instanceof AbstractArrow arrow && !arrow.getTags().contains(HIT_TAG) && arrow.getDeltaMovement().lengthSqr() > 1.0E-4) {
				kind = "arrow";
			} else if (e instanceof FireworkRocketEntity rocket && rocket.isShotAtAngle()) {
				kind = "firework"; // not the ones boosting an elytra flight
			}

			if (kind != null) {
				b = b == null ? new StringBuilder("{\"t\":\"proj\",\"p\":[") : b.append(',');
				b.append(String.format(Locale.ROOT, "[%d,\"%s\",%.3f,%.3f,%.3f]", e.getId(), kind, e.getX(), e.getY(), e.getZ()));
			}
		}

		if (b != null) {
			Passthrough.events.accept(b.append("]}").toString());
		}
	}

	/**
	 * The host traced a projectile into something of its own: a firework bursts there; an arrow goes into a person
	 * or car (gone) or sticks where it hit a wall; a fireball or skull goes off there, as if it had hit a block.
	 */
	public static void projectileHit(final int id, final double x, final double y, final double z, final boolean stick) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			Entity e = level.getEntity(id);
			if (e instanceof FireworkRocketEntity) {
				e.setPos(x, y, z);
				level.broadcastEntityEvent(e, (byte) 17);
				e.discard();
			} else if (e instanceof AbstractArrow) {
				if (stick) {
					e.setPos(x, y, z);
					e.setDeltaMovement(Vec3.ZERO);
					e.setNoGravity(true);
					e.addTag(HIT_TAG);
				} else {
					e.discard();
				}
			} else if (e instanceof AbstractHurtingProjectile && e.isAlive()) {
				e.setPos(x, y, z);
				Vec3 at = new Vec3(x, y, z);
				((ProjectileInvoker) e).passthrough$onHit(new BlockHitResult(at, Direction.UP, BlockPos.containing(at), false));
				if (e.isAlive()) {
					e.discard();
				}
			}
		});
	}

	/** Server thread, from Level.setBlock: remember the change; flushed once per tick. */
	public static void onBlockChanged(final ServerLevel level, final BlockPos pos, final BlockState state) {
		if (level != level.getServer().overworld()) {
			return;
		}

		if (!placingGround && state.isAir() && ground.contains(pos)) {
			torn.add(pos.immutable());
			return;
		}

		if (!Passthrough.active) {
			return;
		}

		Nether.onBlockChanged(pos, state);
		if (!placingGround) {
			changes.put(pos.immutable(), solidForHost(level, pos, state));
		}
	}

	/** While on, block changes are the host's own ground being edited (not reported as blocks to collide with). */
	static void quietGround(final boolean on) {
		placingGround = on;
	}

	/** End of each server tick: {"t":"blocks","set":[x,y,z,...],"clear":[x,y,z,...]}. */
	static void flush(final MinecraftServer s) {
		if (changes.isEmpty()) {
			return;
		}

		StringBuilder set = new StringBuilder();
		StringBuilder clear = new StringBuilder();
		for (Map.Entry<BlockPos, Boolean> e : changes.entrySet()) {
			StringBuilder b = e.getValue() ? set : clear;
			BlockPos p = e.getKey();
			b.append(b.isEmpty() ? "" : ",").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
		}

		changes.clear();
		Passthrough.events.accept("{\"t\":\"blocks\",\"set\":[" + set + "],\"clear\":[" + clear + "]}");
	}

	/** Every solid block within `radius` of the player, as one "blocks" message (the host's props start from this). */
	public static void sync(final int radius) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerLevel level = s.overworld();
			ServerPlayer player = player(s);
			if (player == null) {
				return;
			}

			BlockPos c = player.blockPosition();
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			int found = 0;
			for (int x = -radius; x <= radius && found < 3000; x++) {
				for (int z = -radius; z <= radius && found < 3000; z++) {
					for (int y = -24; y <= 40; y++) {
						p.set(c.getX() + x, c.getY() + y, c.getZ() + z);
						if (!level.isInWorldBounds(p) || !level.isLoaded(p)) {
							continue;
						}

						BlockState state = level.getBlockState(p);
						if (solidForHost(level, p, state)) {
							changes.put(p.immutable(), true);
							found++;
						}
					}
				}
			}

			flush(s);
		});
	}

	/** Elytra on and gliding (creative flight off), launched forward along the look; or back to creative flight. */
	public static void glide(final boolean start, final double speed) {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}

		s.execute(() -> {
			ServerPlayer player = player(s);
			if (player == null) {
				return;
			}

			if (start) {
				player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
				player.getAbilities().flying = false;
				player.onUpdateAbilities();
				player.setOnGround(false);
				player.startFallFlying();
				player.setDeltaMovement(player.getLookAngle().scale(speed));
				player.hurtMarked = true;
			} else {
				player.stopFallFlying();
				player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}
		});
	}

	/** `source`: what exploded or was blown up, e.g. "tnt", "creeper", "fireball" (a ghast's), "flaming_wither_skull". */
	public static void onExplosion(final Vec3 center, final float radius, final String source) {
		if (Passthrough.active) {
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"explosion\",\"pos\":[%.3f,%.3f,%.3f],\"r\":%.2f,\"src\":\"%s\"}",
				center.x, center.y, center.z, radius, source));
		}
	}
}
