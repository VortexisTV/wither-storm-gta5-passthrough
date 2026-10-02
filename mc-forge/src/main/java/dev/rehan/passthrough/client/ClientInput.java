package dev.rehan.passthrough.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import dev.rehan.passthrough.Passthrough;
import dev.rehan.passthrough.mixin.client.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** Host input, applied on the client thread: the host window has the focus, so Minecraft never sees these itself. */
final class ClientInput {
	private ClientInput() {
	}

	static void handle(final Minecraft minecraft, final JsonObject m) {
		LocalPlayer player = minecraft.player;
		switch (m.get("t").getAsString()) {
			case "key" -> {
				String k = m.get("k").getAsString();
				boolean down = !m.has("down") || m.get("down").getAsBoolean();
				if (k.equals("escape")) {
					if (down && minecraft.screen != null) {
						minecraft.screen.onClose();
					}

					return;
				}

				KeyMapping key = switch (k) {
					case "use" -> minecraft.options.keyUse;
					case "attack" -> minecraft.options.keyAttack;
					case "pick" -> minecraft.options.keyPickItem;
					case "inventory" -> minecraft.options.keyInventory;
					case "drop" -> minecraft.options.keyDrop;
					case "swap" -> minecraft.options.keySwapOffhand;
					default -> null;
				};
				if (k.equals("attack") && down && player != null
					&& BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath().endsWith("_sword")) {
					// a sword swing: the host hits what's in front of Steve in its own world
					Passthrough.events.accept("{\"t\":\"melee\"}");
				}

				if (key != null) {
					if (down && !key.isDown()) {
						KeyMappingAccessor access = (KeyMappingAccessor) key;
						access.passthrough$setClickCount(access.passthrough$getClickCount() + 1);
					}

					key.setDown(down);
				}
			}
			case "slot" -> {
				if (player != null) {
					player.getInventory().selected = Mth.clamp(m.get("n").getAsInt(), 0, Inventory.getSelectionSize() - 1);
				}
			}
			case "scroll" -> {
				if (player != null) {
					Inventory inventory = player.getInventory();
					inventory.selected = Math.floorMod(inventory.selected - m.get("d").getAsInt(), Inventory.getSelectionSize());
				}
			}
			case "hud" -> minecraft.options.hideGui = m.get("hidden").getAsBoolean();
			case "view" -> {
				// match the host's picture exactly: out of fullscreen and un-minimized/un-maximized first (resizing a
				// maximized window is ignored)
				int w = m.get("w").getAsInt(), h = m.get("h").getAsInt();
				Window window = minecraft.getWindow();
				if (window.isFullscreen()) {
					window.toggleFullScreen();
				}

				long handle = window.getWindow();
				GLFW.glfwRestoreWindow(handle);
				GLFW.glfwSetWindowSize(handle, w, h);
			}
			default -> {
			}
		}
	}
}
