package dev.rehan.passthrough.storm;

import com.google.gson.JsonObject;
import dev.rehan.passthrough.MobWar;
import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.ProxyEntity;
import dev.rehan.passthrough.WorldBridge;
import dev.rehan.passthrough.client.HostLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLEnvironment;
import nonamecrackers2.witherstormmod.api.common.event.WitherStormChangePhaseEvent;
import nonamecrackers2.witherstormmod.api.common.event.WitherStormConsumeEvent;
import nonamecrackers2.witherstormmod.common.entity.BlockClusterEntity;
import nonamecrackers2.witherstormmod.common.entity.WitherStormEntity;
import nonamecrackers2.witherstormmod.common.init.WitherStormModEntityTypes;

/**
 * Cracker's Wither Storm, loose in the host's world. Nothing of the storm is simulated here: the mod runs as it
 * always does, and this only gives it the host's world to do it to, and tells the host what it did.
 *
 * <ul>
 * <li>The host's people and vehicles are {@link ProxyEntity} here. The storm picks them as it picks any mob, its
 * tractor beams pull them (the proxy flies where it is pulled and the host makes the real one follow: "grab"), and
 * what reaches a mouth or the body is consumed: the storm grows by it, and the real one is gone ("eaten").</li>
 * <li>The host's ground is a block the storm may tear up. Its own cluster logic rips pieces out and carries them
 * off; they are given a look (rubble: the block itself is never drawn), and the host is told where the ground went
 * ("rip") so what stood there is thrown about.</li>
 * <li>Its flaming skulls are traced through the host's world like every other projectile (WorldBridge), and blow
 * up there as every Minecraft explosion does.</li>
 * <li>Where the storms are, how big, and where their beams point goes to the host every other tick ("storms"): its
 * sky darkens under them and its camera shakes when they roar ("stormshake", see the mixin).</li>
 * </ul>
 *
 * Host to Minecraft: {"t":"storm","op":"summon","dist":blocks ahead,"up":blocks up,"phase":0-7},
 * {"op":"evolve"}, {"op":"phase","n":0-7}, {"op":"kill"} (its death sequence), {"op":"remove"}.
 */
public final class StormBridge {
	/** What a person or a vehicle of the host's counts for when the storm eats it (a Minecraft mob counts 1, a block of a cluster 1). */
	private static final int PED_MASS = Integer.getInteger("passthrough.pedMass", 40);
	private static final int VEHICLE_MASS = Integer.getInteger("passthrough.vehicleMass", 250);
	private static final double BEAM_REACH = 200.0;
	/** What the torn-up ground looks like: mostly road, some of what is under it. */
	private static final BlockState[] RUBBLE = {
		Blocks.GRAY_CONCRETE.defaultBlockState(), Blocks.GRAY_CONCRETE.defaultBlockState(), Blocks.GRAY_CONCRETE.defaultBlockState(),
		Blocks.GRAY_CONCRETE_POWDER.defaultBlockState(), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), Blocks.STONE.defaultBlockState(),
		Blocks.ANDESITE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(),
		Blocks.DIRT.defaultBlockState()
	};
	private static int ticks;
	private static boolean reported;

	private StormBridge() {
	}

	public static void init(final IEventBus mod, final IEventBus forge) {
		forge.addListener(StormBridge::onConsume);
		forge.addListener(EventPriority.LOWEST, StormBridge::onJoin);
		forge.addListener(StormBridge::onPhase);
		forge.addListener((TickEvent.ServerTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) {
				tick(e.getServer());
			}
		});
		// the storm's insides are another dimension; the host's player stays in the host's world
		forge.addListener((EntityTravelToDimensionEvent e) -> {
			if (Passthrough.active && (e.getEntity() instanceof Player || e.getEntity() instanceof ProxyEntity)) {
				e.setCanceled(true);
			}
		});
		if (FMLEnvironment.dist == Dist.CLIENT) {
			HostLink.storm = StormBridge::message;
		}
	}

	/** The storm swallowed something: if it was one of the host's, the host loses it, and the storm gains its weight. */
	private static void onConsume(final WitherStormConsumeEvent e) {
		if (!(e.getConsumedEntity() instanceof ProxyEntity proxy)) {
			return;
		}

		if (proxy.eaten()) {
			e.setConsumedAmount(0); // a mouth and the body both had it this tick
			return;
		}

		MobWar.eaten(proxy);
		e.setConsumedAmount(proxy.kind() == ProxyEntity.VEHICLE ? VEHICLE_MASS : PED_MASS);
	}

	/** A cluster torn out of the host's ground: give its (undrawn) ground blocks a look, and tell the host. */
	private static void onJoin(final EntityJoinLevelEvent e) {
		if (e.isCanceled() || !(e.getLevel() instanceof ServerLevel) || !(e.getEntity() instanceof BlockClusterEntity cluster)) {
			return;
		}

		Map<BlockPos, BlockState> blocks = cluster.getBlocks();
		if (blocks.values().stream().noneMatch(WorldBridge::isHostGround)) {
			return;
		}

		BlockPos start = cluster.getStartPos();
		Map<BlockPos, BlockState> dressed = new HashMap<>(blocks.size() * 2);
		blocks.forEach((at, state) -> dressed.put(at, WorldBridge.isHostGround(state) ? rubble(start.getX() + at.getX(), start.getY() + at.getY(), start.getZ() + at.getZ()) : state));
		cluster.setBlocks(dressed);
		if (Passthrough.active) {
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"rip\",\"pos\":[%.2f,%.2f,%.2f],\"n\":%d}",
				start.getX() + 0.5, start.getY() + 1.0, start.getZ() + 0.5, dressed.size()));
		}
	}

	/** Patches of one material a few blocks across, so a cluster reads as a slab of road with earth under it. */
	private static BlockState rubble(final int x, final int y, final int z) {
		long h = (x >> 1) * 0x9E3779B97F4A7C15L ^ (z >> 1) * 0xC2B2AE3D27D4EB4FL ^ y * 0x165667B19E3779F9L;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return RUBBLE[(int) Long.remainderUnsigned(h, RUBBLE.length)];
	}

	private static void onPhase(final WitherStormChangePhaseEvent e) {
		// (not a storm that isn't in the world yet: the one being summoned says so itself)
		if (Passthrough.active && !e.getEntity().level().isClientSide && e.getEntity().isAddedToWorld()) {
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"stormphase\",\"id\":%d,\"phase\":%d}", e.getEntity().getId(), e.getToPhase()));
		}
	}

	private static List<WitherStormEntity> storms(final ServerLevel level) {
		List<WitherStormEntity> out = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (e instanceof WitherStormEntity storm && storm.isAlive()) {
				out.add(storm);
			}
		}

		return out;
	}

	/**
	 * Every other tick while there are storms: {"t":"storms","s":[[id,phase,x,y,z,width,height,consumed],...],
	 * "b":[[x,y,z,dx,dy,dz,length],...]}: each storm (and segment) at the bottom centre of its box, and each active
	 * tractor beam from its head along its (unit) direction. One empty list when the last storm is gone.
	 */
	private static void tick(final MinecraftServer server) {
		if (!Passthrough.active || (++ticks & 1) != 0) {
			return;
		}

		List<WitherStormEntity> storms = storms(server.overworld());
		if (storms.isEmpty()) {
			if (reported) {
				reported = false;
				Passthrough.events.accept("{\"t\":\"storms\",\"s\":[],\"b\":[]}");
			}

			return;
		}

		StringBuilder s = new StringBuilder("{\"t\":\"storms\",\"s\":[");
		StringBuilder b = new StringBuilder();
		boolean first = true;
		for (WitherStormEntity storm : storms) {
			s.append(first ? "" : ",").append(String.format(Locale.ROOT, "[%d,%d,%.2f,%.2f,%.2f,%.1f,%.1f,%d]", storm.getId(), storm.getPhase(),
				storm.getX(), storm.getY(), storm.getZ(), storm.getUnmodifiedWidth(), storm.getUnmodifiedHeight(), storm.getConsumedEntities()));
			first = false;
			if (storm.isDeadOrPlayingDead()) {
				continue;
			}

			for (int head = 0; head < storm.getTotalHeads(); head++) {
				if (!storm.tractorBeamActive(head)) {
					continue;
				}

				Vec3 from = storm.getHeadPos(head);
				Vec3 dir = storm.getViewVector(storm.getHeadXRot(head), storm.getHeadYRot(head), 1.0F);
				double cutoff = storm.getTractorBeamCutoffDistance(head);
				b.append(b.isEmpty() ? "" : ",").append(String.format(Locale.ROOT, "[%.2f,%.2f,%.2f,%.3f,%.3f,%.3f,%.1f]",
					from.x, from.y, from.z, dir.x, dir.y, dir.z, cutoff < 0.0 ? BEAM_REACH : Math.min(cutoff, BEAM_REACH)));
			}
		}

		reported = true;
		Passthrough.events.accept(s.append("],\"b\":[").append(b).append("]}").toString());
	}

	/** {"t":"storm","op":...} from the host (link thread). */
	private static void message(final JsonObject m) {
		MinecraftServer server = WorldBridge.server();
		if (server == null) {
			return;
		}

		String op = m.get("op").getAsString();
		server.execute(() -> {
			ServerLevel level = server.overworld();
			switch (op) {
				case "summon" -> summon(server, level, m.has("dist") ? m.get("dist").getAsDouble() : 70.0, m.has("up") ? m.get("up").getAsDouble() : 25.0,
					m.has("phase") ? m.get("phase").getAsInt() : 0);
				case "evolve" -> storms(level).forEach(storm -> {
					if (!(storm instanceof nonamecrackers2.witherstormmod.common.entity.WitherStormSegmentEntity)) {
						storm.evolve(true);
					}
				});
				case "phase" -> storms(level).forEach(storm -> {
					if (!(storm instanceof nonamecrackers2.witherstormmod.common.entity.WitherStormSegmentEntity)) {
						storm.setPhase(Math.max(0, Math.min(7, m.get("n").getAsInt())));
					}
				});
				case "kill" -> WorldBridge.command("execute as @e[type=witherstormmod:wither_storm] run witherstormmod kill @s");
				case "remove" -> remove(level);
				default -> Passthrough.LOG.warn("storm: unknown op {}", op);
			}
		});
	}

	/** A new storm `dist` blocks ahead of the player (where they look, level) and `up` above them. */
	private static void summon(final MinecraftServer server, final ServerLevel level, final double dist, final double up, final int phase) {
		ServerPlayer player = WorldBridge.player(server);
		if (player == null) {
			return;
		}

		double yaw = Math.toRadians(player.getYRot());
		double x = player.getX() - Math.sin(yaw) * dist, y = player.getY() + up, z = player.getZ() + Math.cos(yaw) * dist;
		WitherStormEntity storm = WitherStormModEntityTypes.WITHER_STORM.get().create(level);
		if (storm == null) {
			return;
		}

		// as the mod itself brings one into the world (WitherStormPatternChecker): facing the player, and, starting
		// from nothing, charging up like a wither does, to go off with a blast when it is done
		float facing = (float) Math.toDegrees(yaw) + 180.0F;
		storm.moveTo(x, y, z, facing, 0.0F);
		storm.yBodyRot = facing;
		if (phase > 0) {
			storm.setPhase(Math.min(7, phase));
		} else {
			storm.makeInvulnerable();
		}

		if (!level.addFreshEntity(storm)) {
			Passthrough.LOG.warn("storm: couldn't add the storm at {} {} {}", x, y, z);
			return;
		}

		Passthrough.LOG.info("storm: summoned at {} {} {} (phase {})", x, y, z, storm.getPhase());
		Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"stormphase\",\"id\":%d,\"phase\":%d,\"new\":true}", storm.getId(), storm.getPhase()));
	}

	/** Every storm gone at once, with what it was carrying and what it had spawned. */
	private static void remove(final ServerLevel level) {
		List<Entity> all = new ArrayList<>();
		level.getAllEntities().forEach(all::add);
		int n = 0;
		for (Entity e : all) {
			if (e instanceof WitherStormEntity || e instanceof BlockClusterEntity || MobWar.stormKin(e)) {
				e.discard();
				n++;
			}
		}

		Passthrough.LOG.info("storm: removed {} entities", n);
	}
}
