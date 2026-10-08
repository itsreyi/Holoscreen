package it.holoscreen.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import it.holoscreen.HoloscreenMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public final class PanelSettings {
	public static final double MAX_CORNER_RADIUS = 0.25;
	public static final int MAX_ANIMATION_MILLIS = 1000;
	public static final int MIN_AUDIO_RANGE = 4;
	public static final int MAX_AUDIO_RANGE = 64;

	public enum AudioMode {
		SPATIAL, STEREO;

		public AudioMode next() {
			return values()[(ordinal() + 1) % values().length];
		}

		public Component label() {
			return Component.translatable("options.holoscreen.audio." + name().toLowerCase(Locale.ROOT));
		}
	}

	public enum Visibility {
		OVER_BLOCKS, ALWAYS_ON_TOP, REALISTIC;

		public Visibility next() {
			return values()[(ordinal() + 1) % values().length];
		}

		public Component label() {
			return Component.translatable("options.holoscreen.visibility." + name().toLowerCase(Locale.ROOT));
		}
	}

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("holoscreen.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static PanelSettings current = new PanelSettings();

	public Visibility visibility = Visibility.OVER_BLOCKS;
	/** Corner radius as a fraction of the panel's shorter side. */
	public double cornerRadius = 0.06;
	/** Open/close animation length; 0 disables the animation. */
	public int animationMillis = 250;
	public AudioMode audio = AudioMode.SPATIAL;
	/** Spatial mode: distance in blocks at which the panel becomes silent. */
	public int audioRange = 24;
	/** Spatial mode: pause the video beyond the audio range and resume it when the player comes back. */
	public boolean pauseWhenFar = true;
	/** Soft dark halo around the panel. */
	public boolean shadow = true;
	/** Subtle UI sounds when opening/closing the panel and switching modes. */
	public boolean uiSounds = true;

	public static PanelSettings get() {
		return current;
	}

	public static void load() {
		if (!Files.exists(PATH)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(PATH)) {
			PanelSettings loaded = GSON.fromJson(reader, PanelSettings.class);
			if (loaded != null) {
				loaded.sanitize();
				current = loaded;
			}
		} catch (IOException | JsonParseException exception) {
			HoloscreenMod.LOGGER.warn("Could not read {}, using defaults", PATH, exception);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(current, writer);
			}
		} catch (IOException exception) {
			HoloscreenMod.LOGGER.warn("Could not write {}", PATH, exception);
		}
	}

	public static Visibility cycleVisibility() {
		current.visibility = current.visibility.next();
		save();
		return current.visibility;
	}

	public static AudioMode cycleAudio() {
		current.audio = current.audio.next();
		save();
		return current.audio;
	}

	private void sanitize() {
		if (visibility == null) {
			visibility = Visibility.OVER_BLOCKS;
		}
		if (audio == null) {
			audio = AudioMode.SPATIAL;
		}
		audioRange = Math.max(MIN_AUDIO_RANGE, Math.min(MAX_AUDIO_RANGE, audioRange));
		cornerRadius = Math.max(0, Math.min(MAX_CORNER_RADIUS, cornerRadius));
		animationMillis = Math.max(0, Math.min(MAX_ANIMATION_MILLIS, animationMillis));
	}
}
