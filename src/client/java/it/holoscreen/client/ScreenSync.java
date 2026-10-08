package it.holoscreen.client;

import it.holoscreen.net.ScreenRemovePayload;
import it.holoscreen.net.ScreenState;
import it.holoscreen.net.ScreenStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Publishes the own panel while it is public and routes other players' screens to {@link RemoteScreens}.
 * Placement changes are sent at most ~10 times a second; a heartbeat every second carries playback.
 */
final class ScreenSync {
	private static final long MIN_INTERVAL_NANOS = 100_000_000L;
	private static final long HEARTBEAT_NANOS = 1_000_000_000L;
	private static final int PROBE_TICKS = 20;
	private static final double POSITION_EPSILON = 0.005;
	private static final double DIRECTION_EPSILON = 0.001;

	private static ScreenState lastSent;
	private static long lastSentAt;
	private static boolean published;
	private static int ticks;

	private ScreenSync() {
	}

	static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(ScreenStatePayload.TYPE,
			(payload, context) -> RemoteScreens.update(payload.state()));
		ClientPlayNetworking.registerGlobalReceiver(ScreenRemovePayload.TYPE,
			(payload, context) -> RemoteScreens.remove(payload.owner()));
	}

	/** True when the connected server runs Holoscreen, so screens can be made public. */
	static boolean serverSupported() {
		return ClientPlayNetworking.canSend(ScreenStatePayload.TYPE);
	}

	static void reset() {
		lastSent = null;
		lastSentAt = 0;
		published = false;
		ticks = 0;
	}

	static void tick(Minecraft client) {
		if (client.player == null || !serverSupported()) {
			return;
		}
		ticks++;
		if (WorldVideoPanel.isShared() && WorldVideoPanel.browser() != null && ticks % PROBE_TICKS == 0) {
			PlaybackSync.probe(WorldVideoPanel.browser());
		}
		ScreenState state = WorldVideoPanel.publicState(client);
		long now = System.nanoTime();
		if (state == null) {
			if (published) {
				ClientPlayNetworking.send(new ScreenRemovePayload(client.player.getUUID()));
				reset();
			}
			return;
		}
		boolean changed = lastSent == null || differs(state, lastSent);
		if ((changed && now - lastSentAt >= MIN_INTERVAL_NANOS) || now - lastSentAt >= HEARTBEAT_NANOS) {
			ClientPlayNetworking.send(new ScreenStatePayload(state));
			lastSent = state;
			lastSentAt = now;
			published = true;
		}
	}

	private static boolean differs(ScreenState a, ScreenState b) {
		return a.center().distanceTo(b.center()) > POSITION_EPSILON
			|| a.look().distanceTo(b.look()) > DIRECTION_EPSILON
			|| a.right().distanceTo(b.right()) > DIRECTION_EPSILON
			|| Math.abs(a.width() - b.width()) > POSITION_EPSILON
			|| Math.abs(a.height() - b.height()) > POSITION_EPSILON
			|| a.playing() != b.playing()
			|| !a.url().equals(b.url())
			|| !a.dimension().equals(b.dimension())
			|| !a.platform().equals(b.platform());
	}
}
