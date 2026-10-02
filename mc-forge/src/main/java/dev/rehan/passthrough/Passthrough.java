package dev.rehan.passthrough;

import dev.rehan.passthrough.client.PassthroughClient;
import dev.rehan.passthrough.storm.StormBridge;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Passthrough.ID)
public class Passthrough {
	public static final String ID = "passthrough";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/** True while a host game is driving the camera. The integrated server shares this JVM, so both sides read it. */
	public static volatile boolean active;
	/** Where events for the host go (JSON lines); the client's HostLink sets it. */
	public static volatile Consumer<String> events = message -> {};

	private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ID);
	private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
	/** The host's ground where it can be torn up (see {@link GroundBlock}). */
	public static final RegistryObject<Block> GROUND = BLOCKS.register("ground", GroundBlock::new);
	/** The host's people and vehicles (see {@link ProxyEntity}). */
	public static final RegistryObject<EntityType<ProxyEntity>> PROXY = ENTITIES.register("proxy",
		() -> EntityType.Builder.<ProxyEntity>of(ProxyEntity::new, MobCategory.MISC).sized(0.6F, 1.8F).noSave().noSummon().fireImmune()
			.clientTrackingRange(10).updateInterval(2).build(ID + ":proxy"));

	public Passthrough(final FMLJavaModLoadingContext context) {
		IEventBus mod = context.getModEventBus();
		BLOCKS.register(mod);
		ENTITIES.register(mod);
		mod.addListener((EntityAttributeCreationEvent e) -> e.put(PROXY.get(), ProxyEntity.createAttributes().build()));

		IEventBus forge = MinecraftForge.EVENT_BUS;
		forge.addListener((ServerStartedEvent e) -> {
			WorldBridge.attach(e.getServer());
			// the storm's own dimension makes every world "experimental": no backup question each time it is opened
			if (e.getServer().getWorldData() instanceof PrimaryLevelData data) {
				data.withConfirmedWarning(true);
			}
		});
		forge.addListener((ServerStoppingEvent e) -> {
			// before the world is saved: the Nether goes back and the fight's mobs go, so none of it is kept
			Nether.detach(e.getServer());
			MobWar.detach(e.getServer());
			WorldBridge.removeGround(e.getServer());
			WorldBridge.detach();
		});
		forge.addListener((EntityJoinLevelEvent e) -> {
			if (e.getLevel() instanceof ServerLevel level && !MobWar.onEntityLoad(e.getEntity(), level)) {
				e.setCanceled(true);
			}
		});
		forge.addListener((TickEvent.ServerTickEvent e) -> {
			if (e.phase == TickEvent.Phase.END) {
				WorldBridge.tick(e.getServer());
			}
		});

		if (FMLEnvironment.dist == Dist.CLIENT) {
			PassthroughClient.init(mod, forge);
		}

		if (ModList.get().isLoaded("witherstormmod")) {
			StormBridge.init(mod, forge);
			LOG.info("passthrough loaded, with the Wither Storm bridge");
		} else {
			LOG.info("passthrough loaded");
		}
	}
}
