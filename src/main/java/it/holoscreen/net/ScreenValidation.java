package it.holoscreen.net;

import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/** Server-side checks for screens published by clients. Pure, so it is unit tested. */
public final class ScreenValidation {
	public static final double MIN_WIDTH = 0.6;
	public static final double MAX_WIDTH = 8.0;
	/** A screen may only be moved while its new position is within this distance of the owner. */
	public static final double MAX_MOVE_DISTANCE = 64.0;
	private static final double MOVE_EPSILON = 1.0E-3;

	private ScreenValidation() {
	}

	/**
	 * Returns a sanitized copy of the state, or null to reject it.
	 *
	 * @param previousCenter center of the screen the server already knows for this owner, or null
	 * @param ownerEyes owner's eye position when the screen is in the owner's dimension, otherwise null
	 */
	public static ScreenState sanitize(ScreenState state, Vec3 previousCenter, Vec3 ownerEyes) {
		if (!finite(state.center()) || !finite(state.look()) || !finite(state.right()) || !finite(state.up())
			|| !Double.isFinite(state.width()) || !Double.isFinite(state.height()) || !Double.isFinite(state.mediaTime())) {
			return null;
		}
		if (!isAllowedUrl(state.url())) {
			return null;
		}
		// Only moves are distance-checked: a first publish (e.g. on rejoin) may be far from a screen left fixed in place.
		boolean moved = previousCenter != null && previousCenter.distanceTo(state.center()) > MOVE_EPSILON;
		if (moved && (ownerEyes == null || ownerEyes.distanceTo(state.center()) > MAX_MOVE_DISTANCE)) {
			return null;
		}
		Vec3 look = unit(state.look());
		Vec3 right = unit(state.right());
		Vec3 up = unit(state.up());
		if (look == null || right == null || up == null) {
			return null;
		}
		double width = clamp(state.width(), MIN_WIDTH, MAX_WIDTH);
		double height = clamp(state.height(), MIN_WIDTH * 9 / 16, MAX_WIDTH * 9 / 16);
		return new ScreenState(state.owner(), state.ownerName(), state.dimension(), state.center(), look, right, up,
			width, height, state.url(), state.platform(), state.playing(), Math.max(0, state.mediaTime()));
	}

	/** Empty (start menu) or an http(s) URL within the length limit. */
	public static boolean isAllowedUrl(String url) {
		if (url.isEmpty()) {
			return true;
		}
		String lower = url.toLowerCase(Locale.ROOT);
		return url.length() <= ScreenState.MAX_URL_LENGTH && (lower.startsWith("https://") || lower.startsWith("http://"));
	}

	private static boolean finite(Vec3 vector) {
		return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}

	private static Vec3 unit(Vec3 vector) {
		double length = vector.length();
		return length < 1.0E-6 ? null : vector.scale(1 / length);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
