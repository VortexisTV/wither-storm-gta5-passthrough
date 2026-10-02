package dev.rehan.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * The host's ground, where it can be torn up: solid and undrawn like a barrier, but (unlike a barrier, which is
 * wither-immune) something the Wither Storm may rip out and carry off. Explosions leave it: the host's ground
 * doesn't crater either.
 */
public class GroundBlock extends Block {
	public GroundBlock() {
		super(BlockBehaviour.Properties.of().mapColor(MapColor.NONE).strength(-1.0F, 3600000.8F).noLootTable().noOcclusion()
			.isValidSpawn((state, level, pos, type) -> true).noParticlesOnBreak().pushReaction(PushReaction.BLOCK));
	}

	@Override
	public boolean propagatesSkylightDown(final BlockState state, final BlockGetter level, final BlockPos pos) {
		return true;
	}

	@Override
	public RenderShape getRenderShape(final BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	public float getShadeBrightness(final BlockState state, final BlockGetter level, final BlockPos pos) {
		return 1.0F;
	}
}
