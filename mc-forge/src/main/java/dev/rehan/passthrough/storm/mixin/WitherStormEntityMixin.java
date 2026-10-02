package dev.rehan.passthrough.storm.mixin;

import dev.rehan.passthrough.WorldBridge;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import nonamecrackers2.witherstormmod.common.entity.WitherStormEntity;
import nonamecrackers2.witherstormmod.common.util.WorldUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The storm flies a set height above the terrain under it. Minecraft's world here is empty but for the host's ground
 * near the player: anywhere else the terrain under the storm is the bottom of the world, and it would sink to it,
 * far under the host's streets. Where there is nothing under it, the terrain is the host's ground level instead.
 */
@Mixin(value = WitherStormEntity.class, remap = false)
abstract class WitherStormEntityMixin {
	@Redirect(
		method = "getHeightToAscendTo",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", remap = true),
		remap = false
	)
	private int passthrough$terrain(final Level level, final Heightmap.Types type, final int x, final int z) {
		return WorldBridge.terrain(level, level.getHeight(type, x, z));
	}

	@Redirect(
		method = "getHeightToAscendTo",
		at = @At(value = "INVOKE", target = "Lnonamecrackers2/witherstormmod/common/util/WorldUtil;getHeightStartingAt(Lnet/minecraft/world/level/Level;III)I", remap = false),
		remap = false
	)
	private int passthrough$terrainBelow(final Level level, final int height, final int x, final int z) {
		return WorldBridge.terrain(level, WorldUtil.getHeightStartingAt(level, height, x, z));
	}
}
