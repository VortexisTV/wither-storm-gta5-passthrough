package dev.rehan.passthrough.client;

import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.WorldBridge;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

public final class PassthroughClient {
	/** The world the host plays in: an empty (void) creative world, so only what the player builds is Minecraft. */
	private static final String WORLD = "passthrough";
	private static final float NO_FOG = 1.0E7F;
	/** Run on the server once the player has joined. */
	private static final List<String> SETUP = List.of(
		"gamerule doDaylightCycle false",
		"gamerule doWeatherCycle false",
		"gamerule doMobSpawning false",
		"gamerule doPatrolSpawning false",
		"gamerule doInsomnia false",
		"gamerule doTraderSpawning false",
		"gamerule sendCommandFeedback false",
		"gamerule logAdminCommands false",
		"gamerule keepInventory true",
		"gamerule announceAdvancements false",
		"difficulty normal",
		"time set noon",
		"weather clear",
		"clear @a",
		"item replace entity @a hotbar.0 with minecraft:ender_pearl 16",
		"item replace entity @a hotbar.1 with minecraft:diamond_sword",
		"item replace entity @a hotbar.2 with minecraft:crossbow{Enchantments:[{id:\"minecraft:multishot\",lvl:1s},{id:\"minecraft:quick_charge\",lvl:3s}]}",
		"item replace entity @a hotbar.3 with minecraft:bow{Enchantments:[{id:\"minecraft:power\",lvl:5s},{id:\"minecraft:infinity\",lvl:1s}]}",
		"item replace entity @a hotbar.4 with minecraft:tnt 64",
		"item replace entity @a hotbar.5 with minecraft:flint_and_steel",
		"item replace entity @a hotbar.6 with minecraft:creeper_spawn_egg 64",
		"item replace entity @a hotbar.7 with minecraft:grass_block 64",
		"item replace entity @a hotbar.8 with minecraft:firework_rocket 64",
		"give @a minecraft:arrow 64",
		"item replace entity @a weapon.offhand with minecraft:firework_rocket{Fireworks:{Flight:3b,Explosions:[{Type:1b,Colors:[I;16733525,16755200],Trail:1b}]}} 64"
	);
	private static boolean configured;
	private static boolean worldRequested;
	/** Server ticks until the setup commands run (the player isn't in the player list yet when the join fires). */
	private static volatile int setupIn = -1;
	private static int respawnIn;

	private PassthroughClient() {
	}

	public static void init(final IEventBus mod, final IEventBus forge) {
		HostLink.launch();
		mod.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(Passthrough.PROXY.get(), NoopRenderer::new));
		forge.addListener((TickEvent.ClientTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) {
				tick(Minecraft.getInstance());
			}
		});
		forge.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player) {
				// never left gliding from a previous session: with no host yet it would glide down into the void
				player.stopFallFlying();
				player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				setupIn = 10;
			}
		});
		forge.addListener((TickEvent.ServerTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END && setupIn > 0 && --setupIn == 0 && WORLD.equals(e.getServer().getWorldData().getLevelName())) {
				SETUP.forEach(WorldBridge::command);
			}
		});

		// The picture goes to the host: nothing of Minecraft's sky may be in it. No distance fog (the host has its
		// own), and a black fog colour: the level pass clears to (fog colour, alpha 0), which leaves empty pixels at
		// (0, 0, 0, 0), so the world layer comes out premultiplied. Last, so that nothing else's fog comes after.
		forge.addListener(EventPriority.LOWEST, true, (ViewportEvent.RenderFog e) -> {
			if (Passthrough.active) {
				e.setNearPlaneDistance(NO_FOG);
				e.setFarPlaneDistance(NO_FOG * 2.0F);
				e.setCanceled(true);
			}
		});
		forge.addListener(EventPriority.LOWEST, (ViewportEvent.ComputeFogColor e) -> {
			if (Passthrough.active) {
				e.setRed(0.0F);
				e.setGreen(0.0F);
				e.setBlue(0.0F);
			}
		});
		// the host camera's roll (vehicles, crashes). Minecraft's view is Rz(roll) Rx(pitch) Ry(yaw + 180); the host
		// builds the camera the other way round (see the compositor's camera_rotation), so the sign flips
		forge.addListener(EventPriority.LOWEST, (ViewportEvent.ComputeCameraAngles e) -> {
			HostState.Pose p = HostState.frame();
			if (p != null) {
				e.setYaw(p.yaw());
				e.setPitch(p.pitch());
				e.setRoll(-p.roll());
			}
		});
		// the vignette darkens the whole screen with an opaque overlay: over the host's picture that is a black frame
		forge.addListener((RenderGuiOverlayEvent.Pre e) -> {
			if (Passthrough.active && e.getOverlay() == VanillaGuiOverlay.VIGNETTE.type()) {
				e.setCanceled(true);
			}
		});
	}

	private static void tick(final Minecraft minecraft) {
		// a death (the void) would leave the death screen over the host's picture: respawn straight away
		if (minecraft.player != null && minecraft.screen instanceof DeathScreen && --respawnIn <= 0) {
			respawnIn = 40;
			minecraft.player.respawn();
		}

		if (!configured) {
			configured = true;
			configure(minecraft.options);
		}

		if (!worldRequested && minecraft.level == null && minecraft.screen instanceof TitleScreen && !Boolean.getBoolean("passthrough.noAutoWorld")) {
			worldRequested = true;
			openWorld(minecraft);
		}
	}

	/** Settings for sitting behind another game: keep running unfocused, and no sky/cloud/bobbing effects in the picture. */
	private static void configure(final Options options) {
		options.pauseOnLostFocus = false;
		options.onboardAccessibility = false;
		options.tutorialStep = TutorialSteps.NONE;
		options.cloudStatus().set(CloudStatus.OFF);
		// the camera is the host's: no bobbing, hurt tilt or nausea warp on top of it (the Wither Storm's own shake
		// rides on the view bobbing too; it shakes the host's camera instead)
		options.bobView().set(false);
		options.fovEffectScale().set(0.0);
		options.damageTiltStrength().set(0.0);
		options.screenEffectScale().set(0.0);
		// "fabulous" draws translucents, particles and weather into targets of their own: one picture, one depth here
		if (options.graphicsMode().get() == GraphicsStatus.FABULOUS) {
			options.graphicsMode().set(GraphicsStatus.FANCY);
		}

		options.enableVsync().set(false);
		// the host shows ~60-120 fps: rendering faster only competes with it for the GPU
		options.framerateLimit().set(120);
		options.save();
	}

	private static void openWorld(final Minecraft minecraft) {
		if (minecraft.getLevelSource().levelExists(WORLD)) {
			Passthrough.LOG.info("opening world {}", WORLD);
			minecraft.createWorldOpenFlows().loadLevel(new TitleScreen(), WORLD);
		} else {
			Passthrough.LOG.info("creating world {}", WORLD);
			LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false), PassthroughClient::voidWorld);
		}
	}

	/** A flat world with a single layer of air: nothing but what gets built (the host's ground arrives as undrawn blocks). */
	private static WorldDimensions voidWorld(final RegistryAccess registries) {
		FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(
			Optional.of(HolderSet.direct()), registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS), List.of()
		);
		flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
		flat.updateLayers();
		return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
	}
}
