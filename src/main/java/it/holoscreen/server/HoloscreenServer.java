package it.holoscreen.server;

import it.holoscreen.net.ScreenRemovePayload;
import it.holoscreen.net.ScreenState;
import it.holoscreen.net.ScreenStatePayload;
import it.holoscreen.net.ScreenValidation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Relays public screens between players. Only screens of online players are kept: when an owner leaves, their
 * screen disappears for everyone, and the owner's client republishes it (from its own saved placement) on rejoin.
 * Video never passes through the server, only placement, URL and playback state.
 */
public final class HoloscreenServer {
	private static final int MAX_MESSAGES_PER_SECOND = 30;
	private static final Map<UUID, ScreenState> SCREENS = new HashMap<>();
	private static final Map<UUID, Deque<Long>> RECENT_MESSAGES = new HashMap<>();

	private HoloscreenServer() {
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(ScreenStatePayload.TYPE,
			(payload, context) -> publish(context.player(), payload.state()));
		ServerPlayNetworking.registerGlobalReceiver(ScreenRemovePayload.TYPE,
			(payload, context) -> unpublish(context.server(), context.player().getUUID()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (ScreenState state : SCREENS.values()) {
				if (!state.owner().equals(handler.player.getUUID())) {
					sender.sendPacket(new ScreenStatePayload(state));
				}
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID owner = handler.player.getUUID();
			RECENT_MESSAGES.remove(owner);
			unpublish(server, owner);
		});
		// The integrated server is restarted for every singleplayer world; don't leak screens between them.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			SCREENS.clear();
			RECENT_MESSAGES.clear();
		});
	}

	private static void publish(ServerPlayer player, ScreenState received) {
		if (!withinRateLimit(player.getUUID())) {
			return;
		}
		MinecraftServer server = player.server;
		ResourceKey<Level> dimension = parseDimension(received.dimension());
		if (dimension == null || server.getLevel(dimension) == null) {
			return;
		}
		ScreenState previous = SCREENS.get(player.getUUID());
		Vec3 ownerEyes = player.level().dimension().equals(dimension) ? player.getEyePosition() : null;
		ScreenState state = ScreenValidation.sanitize(
			received.withOwner(player.getUUID(), player.getGameProfile().getName()),
			previous == null ? null : previous.center(),
			ownerEyes);
		if (state == null) {
			return;
		}
		SCREENS.put(player.getUUID(), state);
		ScreenStatePayload payload = new ScreenStatePayload(state);
		for (ServerPlayer other : PlayerLookup.all(server)) {
			if (other != player) {
				ServerPlayNetworking.send(other, payload);
			}
		}
	}

	private static void unpublish(MinecraftServer server, UUID owner) {
		if (SCREENS.remove(owner) == null) {
			return;
		}
		ScreenRemovePayload payload = new ScreenRemovePayload(owner);
		for (ServerPlayer other : PlayerLookup.all(server)) {
			if (!other.getUUID().equals(owner)) {
				ServerPlayNetworking.send(other, payload);
			}
		}
	}

	private static boolean withinRateLimit(UUID player) {
		long now = System.currentTimeMillis();
		Deque<Long> recent = RECENT_MESSAGES.computeIfAbsent(player, key -> new ArrayDeque<>());
		while (!recent.isEmpty() && now - recent.peekFirst() > 1000) {
			recent.pollFirst();
		}
		if (recent.size() >= MAX_MESSAGES_PER_SECOND) {
			return false;
		}
		recent.addLast(now);
		return true;
	}

	private static ResourceKey<Level> parseDimension(String id) {
		ResourceLocation location = ResourceLocation.tryParse(id);
		return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
	}
}
