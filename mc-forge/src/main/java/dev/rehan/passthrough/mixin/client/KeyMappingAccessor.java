package dev.rehan.passthrough.mixin.client;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int passthrough$getClickCount();

	@Accessor("clickCount")
	void passthrough$setClickCount(int clickCount);
}
