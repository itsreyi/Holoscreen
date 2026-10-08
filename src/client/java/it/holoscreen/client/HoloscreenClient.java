package it.holoscreen.client;

import com.mojang.blaze3d.platform.InputConstants;
import it.holoscreen.HoloscreenMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class HoloscreenClient implements ClientModInitializer {
	private static final String CATEGORY = "key.categories.holoscreen";

	private final KeyMapping openBrowser = key("open_browser", GLFW.GLFW_KEY_H);
	private final KeyMapping placeWorldPanel = key("place_world_panel", GLFW.GLFW_KEY_G);
	private final KeyMapping removeWorldPanel = key("remove_world_panel", GLFW.GLFW_KEY_X);
	private final KeyMapping interactWorldPanel = key("interact_world_panel", GLFW.GLFW_KEY_I);
	// Not E: that would shadow the vanilla inventory key.
	private final KeyMapping editWorldPanel = key("edit_world_panel", GLFW.GLFW_KEY_J);
	private final KeyMapping openSettings = key("open_settings", GLFW.GLFW_KEY_K);
	private final KeyMapping cycleVisibility = key("cycle_visibility", GLFW.GLFW_KEY_V);

	private static KeyMapping key(String name, int defaultKey) {
		return KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.holoscreen." + name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY));
	}

	@Override
	public void onInitializeClient() {
		HoloscreenMod.LOGGER.info("Client rendering entrypoint initialized");
		PanelSettings.load();
		if (!FabricLoader.getInstance().isModLoaded("mcef")) {
			// Without MCEF none of the browser classes may load; only explain what is missing.
			HoloscreenMod.LOGGER.warn("MCEF is not installed; Holoscreen screens are disabled");
			ClientTickEvents.END_CLIENT_TICK.register(this::tickWithoutMcef);
			return;
		}
		WorldRenderEvents.BEFORE_ENTITIES.register(context -> WorldVideoPanel.render(context, WorldVideoPanel.Stage.BEFORE_ENTITIES));
		WorldRenderEvents.LAST.register(context -> WorldVideoPanel.render(context, WorldVideoPanel.Stage.LAST));
		ScreenSync.registerReceivers();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			ScreenSync.reset();
			RemoteScreens.clear();
			WorldVideoPanel.onJoin(client);
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			WorldVideoPanel.onDisconnect();
			RemoteScreens.clear();
			ScreenSync.reset();
		}));
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tickWithoutMcef(Minecraft client) {
		boolean pressed = false;
		for (KeyMapping key : new KeyMapping[]{placeWorldPanel, openBrowser, interactWorldPanel, editWorldPanel}) {
			while (key.consumeClick()) {
				pressed = true;
			}
		}
		if (pressed && client.player != null) {
			client.player.displayClientMessage(Component.translatable("message.holoscreen.mcef_required"), false);
		}
	}

	private void tick(Minecraft client) {
		WorldVideoPanel.tick(client);
		ScreenSync.tick(client);
		RemoteScreens.tick(client);

		while (openSettings.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new PanelSettingsScreen(null));
			}
		}

		while (cycleVisibility.consumeClick()) {
			if (client.screen == null && client.player != null) {
				PanelSettings.Visibility visibility = PanelSettings.cycleVisibility();
				client.player.displayClientMessage(Component.translatable("message.holoscreen.visibility", visibility.label()), true);
			}
		}

		if (client.screen == null && client.player != null && placeWorldPanel.consumeClick()) {
			if (!FabricLoader.getInstance().isModLoaded("mcef")) {
				client.player.displayClientMessage(Component.translatable("message.holoscreen.mcef_required"), false);
			} else if (WorldVideoPanel.isReady()) {
				client.player.displayClientMessage(Component.translatable("message.holoscreen.panel_already_placed"), false);
			} else if (WorldVideoPanel.isPlaced()) {
				// The panel is in another dimension: bring it here as it is.
				WorldVideoPanel.place(client);
				if (WorldVideoPanel.isReady()) {
					client.player.displayClientMessage(Component.translatable("message.holoscreen.panel_moved_here"), false);
				}
			} else {
				WorldVideoPanel.place(client);
				if (WorldVideoPanel.isReady()) {
					// Free the mouse right away so the start menu on the panel can be clicked after the intro.
					client.setScreen(new PanelInteractionScreen(false));
				}
			}
		}

		if (client.screen == null && client.player != null && removeWorldPanel.consumeClick()) {
			if (WorldVideoPanel.remove()) {
				client.player.displayClientMessage(Component.translatable("message.holoscreen.panel_removed"), false);
			}
		}

		if (client.screen == null && client.player != null && interactWorldPanel.consumeClick() && checkReady(client)) {
			client.setScreen(new PanelInteractionScreen(false));
		}

		if (client.screen == null && client.player != null && editWorldPanel.consumeClick() && checkReady(client)) {
			client.setScreen(new PanelInteractionScreen(true));
		}

		while (openBrowser.consumeClick()) {
			if (client.screen != null) {
				continue;
			}
			if (!FabricLoader.getInstance().isModLoaded("mcef")) {
				if (client.player != null) {
					client.player.displayClientMessage(Component.translatable("message.holoscreen.mcef_required"), false);
				}
				continue;
			}
			try {
				client.setScreen(new BrowserTestScreen());
			} catch (RuntimeException | LinkageError exception) {
				HoloscreenMod.LOGGER.error("Could not start the MCEF browser", exception);
				if (client.player != null) {
					client.player.displayClientMessage(Component.translatable("message.holoscreen.browser_failed"), false);
				}
			}
		}
	}

	/** True when the panel can be interacted with; otherwise explains why in chat. */
	private static boolean checkReady(Minecraft client) {
		if (WorldVideoPanel.isReady()) {
			return true;
		}
		client.player.displayClientMessage(Component.translatable(WorldVideoPanel.isPlaced()
			? "message.holoscreen.panel_other_dimension"
			: "message.holoscreen.panel_not_placed"), false);
		return false;
	}
}
