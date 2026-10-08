package it.holoscreen;

import it.holoscreen.net.ScreenRemovePayload;
import it.holoscreen.net.ScreenStatePayload;
import it.holoscreen.server.HoloscreenServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HoloscreenMod implements ModInitializer {
	public static final String MOD_ID = "holoscreen";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Both payloads travel in both directions (publish/update and unpublish/remove).
		PayloadTypeRegistry.playC2S().register(ScreenStatePayload.TYPE, ScreenStatePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ScreenStatePayload.TYPE, ScreenStatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ScreenRemovePayload.TYPE, ScreenRemovePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ScreenRemovePayload.TYPE, ScreenRemovePayload.CODEC);
		HoloscreenServer.init();
		LOGGER.info("Holoscreen initialized");
	}
}
