package dev.rehan.passthrough.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rehan.passthrough.MobWar;
import dev.rehan.passthrough.Nether;
import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.WorldBridge;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * The host's connection: a WebSocket server on 127.0.0.1 (port 25599, or -Dpassthrough.port).
 *
 * <p>Host to Minecraft (JSON, Minecraft coordinates):
 * <ul>
 * <li>{"t":"cam","f":frame,"p":[x,y,z],"r":[yaw,pitch,roll],"fov":vertical degrees,"fp":first person,"pl":[feet x,y,z],"h":body yaw}</li>
 * <li>{"t":"ground","c":[x,z,yBottom,yTop, ...]}: solid columns</li>
 * <li>{"t":"clear"}: remove the ground placed so far</li>
 * <li>{"t":"cmd","c":"time set noon"}: a server command</li>
 * <li>{"t":"key","k":"use|attack|pick|inventory|drop|swap|escape","down":bool}</li>
 * <li>{"t":"slot","n":0-8}, {"t":"scroll","d":+-1}, {"t":"hud","hidden":bool}, {"t":"view","w":px,"h":px}</li>
 * <li>{"t":"peds","p":[[handle,x,y,z],...]}, {"t":"vehs","v":[[handle,x,y,z],...]}: the host's people and vehicles</li>
 * <li>{"t":"storm","op":"summon|evolve|phase|kill",...}: the Wither Storm (when Cracker's Wither Storm Mod is installed)</li>
 * </ul>
 * Minecraft to host: {"t":"hello",...} on connect, {"t":"explosion","pos":[x,y,z],"r":radius}, and the rest of what
 * the bridges report ("blocks", "mobs", "grab", "eaten", "storms", ...).
 * Relayed unchanged to the other clients: {"t":"gta",...} (director commands for the host plugin), {"t":"gtastate",...}.
 */
public final class HostLink extends WsServer {
	private static HostLink instance;
	/** Messages of type "storm", when the Wither Storm bridge is there to take them. */
	public static volatile Consumer<JsonObject> storm = message -> Passthrough.LOG.warn("storm message, but Cracker's Wither Storm Mod isn't installed");

	private HostLink(final int port) {
		super(port);
	}

	static void launch() {
		int port = Integer.getInteger("passthrough.port", 25599);
		instance = new HostLink(port);
		instance.start();
		Passthrough.events = message -> instance.broadcast(message);
	}

	@Override
	void onStart(final int port) {
		Passthrough.LOG.info("host link listening on 127.0.0.1:{}", port);
	}

	@Override
	void onOpen(final Conn conn) {
		Passthrough.LOG.info("host connected from {}", conn.remote());
		conn.send(String.format(Locale.ROOT, "{\"t\":\"hello\",\"v\":1,\"shm\":\"%s\",\"pid\":%d,\"mc\":\"1.20.1\",\"storm\":%b}",
			FrameExporter.NAME.replace("\\", "\\\\"), ProcessHandle.current().pid(), net.minecraftforge.fml.ModList.get().isLoaded("witherstormmod")));
	}

	@Override
	void onClose(final Conn conn) {
		Passthrough.LOG.info("host disconnected ({})", conn.remote());
	}

	@Override
	void onMessage(final Conn conn, final String message) {
		try {
			JsonObject m = JsonParser.parseString(message).getAsJsonObject();
			switch (m.get("t").getAsString()) {
				case "cam" -> HostState.update(m);
				case "ground" -> WorldBridge.solid(ints(m.getAsJsonArray("c")));
				case "clear" -> WorldBridge.clearSolid();
				case "cmd" -> WorldBridge.command(m.get("c").getAsString());
				case "gta", "gtastate", "gtainfo", "director" -> this.relay(conn, message);
				case "blocksync" -> WorldBridge.sync(m.has("r") ? m.get("r").getAsInt() : 48);
				case "projhit" -> {
					JsonArray at = m.getAsJsonArray("pos");
					WorldBridge.projectileHit(m.get("id").getAsInt(), at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(),
						m.has("stick") && m.get("stick").getAsBoolean());
				}
				case "peds" -> MobWar.peds(quads(m.getAsJsonArray("p")));
				case "vehs" -> MobWar.vehicles(quads(m.getAsJsonArray("v")));
				case "mobdmg" -> MobWar.damage(m.get("id").getAsInt(), m.get("d").getAsDouble());
				case "spawnmobs" -> MobWar.spawn(m.get("k").getAsString(), m.has("n") ? m.get("n").getAsInt() : 5,
					m.has("rmin") ? m.get("rmin").getAsDouble() : 8.0, m.has("rmax") ? m.get("rmax").getAsDouble() : 16.0,
					m.has("arc") ? m.get("arc").getAsDouble() : 40.0, m.has("yaw") ? m.get("yaw").getAsDouble() : 0.0,
					m.has("at") ? new double[] {m.getAsJsonArray("at").get(0).getAsDouble(), m.getAsJsonArray("at").get(1).getAsDouble(),
						m.getAsJsonArray("at").get(2).getAsDouble()} : null);
				case "mobsclear" -> MobWar.clearMobs();
				case "portal" -> {
					JsonArray at = m.getAsJsonArray("at");
					Nether.buildPortal(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(), m.get("yaw").getAsFloat());
				}
				case "netheroff" -> Nether.stop();
				case "nethersync" -> Nether.resync();
				case "glide" -> WorldBridge.glide(!m.has("on") || m.get("on").getAsBoolean(), m.has("speed") ? m.get("speed").getAsDouble() : 1.2);
				case "storm" -> storm.accept(m);
				default -> {
					Minecraft minecraft = Minecraft.getInstance();
					minecraft.execute(() -> ClientInput.handle(minecraft, m));
				}
			}
		} catch (RuntimeException e) {
			Passthrough.LOG.warn("bad host message {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
		}
	}

	/** Messages between the host plugin and a director script pass through Minecraft's link to every other client. */
	private void relay(final Conn from, final String message) {
		for (Conn c : this.connections()) {
			if (c != from && c.isOpen()) {
				c.send(message);
			}
		}
	}

	/** [[handle, x, y, z], ...] flattened. */
	private static double[] quads(final JsonArray list) {
		double[] flat = new double[list.size() * 4];
		for (int i = 0; i < list.size(); i++) {
			JsonArray e = list.get(i).getAsJsonArray();
			for (int k = 0; k < 4; k++) {
				flat[i * 4 + k] = e.get(k).getAsDouble();
			}
		}

		return flat;
	}

	private static int[] ints(final JsonArray a) {
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}

		return out;
	}
}
