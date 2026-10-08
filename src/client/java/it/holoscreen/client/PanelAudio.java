package it.holoscreen.client;

import com.cinemamod.mcef.MCEFBrowser;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Chromium plays audio on its own output, outside Minecraft's OpenAL engine, so spatial audio is done inside the page:
 * every media element is routed through a Web Audio gain + stereo panner whose values are pushed from the client tick.
 */
final class PanelAudio {
	/** Distance within which the panel plays at full volume in spatial mode. */
	private static final double FULL_VOLUME_DISTANCE = 2.0;

	/** Hysteresis for pause-when-far: pause beyond range + this margin, resume back inside the range. */
	private static final double FAR_MARGIN = 2.0;

	// Idempotent: installs the audio graph once per page, pauses/resumes videos for distance, wires any new
	// <video>/<audio>, then applies gain and pan. Media is only wired once the AudioContext runs (after a click or key
	// press in the page); wiring into a suspended context would silence the video. Only videos this script paused are
	// resumed, so a video the user paused stays paused.
	private static final String SCRIPT = """
		(function(g, p, far) {
		  var s = window.__svs;
		  if (!s) {
		    var C = window.AudioContext || window.webkitAudioContext;
		    if (!C) return;
		    s = window.__svs = {ctx: new C()};
		    s.gain = s.ctx.createGain();
		    s.pan = s.ctx.createStereoPanner();
		    s.gain.connect(s.pan);
		    s.pan.connect(s.ctx.destination);
		    var resume = function() { s.ctx.resume(); };
		    document.addEventListener('pointerdown', resume, true);
		    document.addEventListener('keydown', resume, true);
		  }
		  document.querySelectorAll('video').forEach(function(v) {
		    if (far) { if (!v.paused) { v.pause(); v.__svsFarPaused = true; } }
		    else if (v.__svsFarPaused) { v.__svsFarPaused = false; v.play(); }
		  });
		  if (s.ctx.state !== 'running') { s.ctx.resume(); return; }
		  document.querySelectorAll('video, audio').forEach(function(m) {
		    if (m.__svs) return;
		    try { s.ctx.createMediaElementSource(m).connect(s.gain); m.__svs = true; } catch (e) {}
		  });
		  var t = s.ctx.currentTime;
		  s.gain.gain.setTargetAtTime(g, t, 0.05);
		  s.pan.pan.setTargetAtTime(p, t, 0.05);
		})(%s, %s, %s);
		""";

	/** Per-screen pause-when-far state (each screen has its own browser). */
	static final class State {
		private boolean far;
	}

	private PanelAudio() {
	}

	/**
	 * @param fade 0..1 multiplier, used to fade audio with the open/close animation
	 * @param allowFarPause false for an owner's shared screen: pausing it would pause it for every viewer
	 */
	static void update(MCEFBrowser browser, Vec3 center, double fade, State state, boolean allowFarPause) {
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		if (player == null) {
			return;
		}
		PanelSettings settings = PanelSettings.get();
		double gain = client.options.getSoundSourceVolume(SoundSource.MASTER)
			* client.options.getSoundSourceVolume(SoundSource.RECORDS)
			* fade;
		double pan = 0;
		boolean spatial = settings.audio == PanelSettings.AudioMode.SPATIAL;
		Vec3 toPanel = center.subtract(player.getEyePosition());
		double distance = toPanel.length();
		double range = Math.max(FULL_VOLUME_DISTANCE + 1, settings.audioRange);
		if (!spatial || !settings.pauseWhenFar || !allowFarPause) {
			state.far = false;
		} else if (distance > range + FAR_MARGIN) {
			state.far = true;
		} else if (distance < range) {
			state.far = false;
		}

		if (spatial) {
			gain *= clamp(1 - (distance - FULL_VOLUME_DISTANCE) / (range - FULL_VOLUME_DISTANCE), 0, 1);
			if (distance > 1.0E-3) {
				// Listener's right-hand direction on the horizontal plane (yaw 0 faces +Z, right is -X).
				double yaw = Math.toRadians(player.getYRot());
				Vec3 listenerRight = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
				pan = clamp(toPanel.normalize().dot(listenerRight), -1, 1);
			}
		}

		try {
			browser.executeJavaScript(String.format(Locale.ROOT, SCRIPT, number(gain), number(pan), state.far), "", 0);
		} catch (RuntimeException exception) {
			// Audio control is best effort; the video keeps playing with Chromium's default output.
		}
	}

	private static String number(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
