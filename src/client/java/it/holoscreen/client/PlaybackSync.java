package it.holoscreen.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

/**
 * Reads and applies video playback state through the page. {@code executeJavaScript} cannot return values, so the
 * probe script reports through {@code console.log} with a prefix, which a display handler on MCEF's client catches.
 */
final class PlaybackSync {
	/** Latest playback reported by a page; {@code hasVideo} is false when the page has no video element. */
	record Playback(boolean hasVideo, double time, boolean playing, long receivedAt) {
	}

	private static final String PREFIX = "__holoscreen_playback:";
	// Picks the largest <video> on the page (the main player) and reports time and play state. A video paused by
	// pause-when-far still counts as playing, so the owner's distance never pauses it for viewers.
	private static final String PROBE_SCRIPT = """
		(function() {
		  var best = null, area = -1;
		  document.querySelectorAll('video').forEach(function(v) {
		    var a = v.offsetWidth * v.offsetHeight;
		    if (a > area) { area = a; best = v; }
		  });
		  var report = best
		    ? {v: 1, t: best.currentTime || 0, p: !best.paused || !!best.__svsFarPaused}
		    : {v: 0};
		  console.log('%s' + JSON.stringify(report));
		})();
		""".formatted(PREFIX);

	// Aligns the main video with the owner: seeks when drifting more than 2 seconds (not on live streams) and mirrors
	// play/pause, unless pause-when-far is holding the video for this viewer. Numbers are inserted with Locale.ROOT.
	private static final String APPLY_SCRIPT = """
		(function(t, playing) {
		  var best = null, area = -1;
		  document.querySelectorAll('video').forEach(function(v) {
		    var a = v.offsetWidth * v.offsetHeight;
		    if (a > area) { area = a; best = v; }
		  });
		  if (!best) return;
		  if (isFinite(best.duration) && Math.abs(best.currentTime - t) > 2.0) best.currentTime = t;
		  if (best.__svsFarPaused) return;
		  if (playing && best.paused) best.play();
		  else if (!playing && !best.paused) best.pause();
		})(%s, %s);
		""";

	private static final Map<CefBrowser, Playback> LATEST = new WeakHashMap<>();
	private static boolean handlerInstalled;

	private PlaybackSync() {
	}

	/** Asks the page to report its playback; the answer arrives asynchronously in {@link #latest}. */
	static void probe(MCEFBrowser browser) {
		installHandler();
		try {
			browser.executeJavaScript(PROBE_SCRIPT, "", 0);
		} catch (RuntimeException exception) {
			// Best effort: the last known state stays in use.
		}
	}

	static Playback latest(MCEFBrowser browser) {
		return LATEST.get(browser);
	}

	/**
	 * Aligns a viewer's page with the owner's playback.
	 *
	 * @param ageSeconds time since the owner's state was received, added to the media time while playing
	 */
	static void apply(MCEFBrowser browser, double time, boolean playing, double ageSeconds) {
		double target = time + (playing ? ageSeconds : 0);
		try {
			browser.executeJavaScript(String.format(Locale.ROOT, APPLY_SCRIPT,
				String.format(Locale.ROOT, "%.3f", target), playing), "", 0);
		} catch (RuntimeException exception) {
			// Best effort.
		}
	}

	private static void installHandler() {
		if (handlerInstalled || !MCEF.isInitialized()) {
			return;
		}
		handlerInstalled = true;
		MCEF.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
			@Override
			public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
				if (message == null || !message.startsWith(PREFIX)) {
					return false;
				}
				Playback playback = parse(message.substring(PREFIX.length()));
				if (playback != null) {
					Minecraft.getInstance().execute(() -> LATEST.put(browser, playback));
				}
				return true;
			}
		});
	}

	private static Playback parse(String json) {
		try {
			JsonObject object = JsonParser.parseString(json).getAsJsonObject();
			boolean hasVideo = object.has("v") && object.get("v").getAsInt() == 1;
			double time = hasVideo && object.has("t") ? object.get("t").getAsDouble() : 0;
			boolean playing = hasVideo && object.has("p") && object.get("p").getAsBoolean();
			return new Playback(hasVideo, Double.isFinite(time) ? time : 0, playing, System.nanoTime());
		} catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException exception) {
			return null;
		}
	}
}
