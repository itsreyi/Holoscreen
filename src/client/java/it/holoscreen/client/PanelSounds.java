package it.holoscreen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Quiet UI feedback sounds, disabled through {@link PanelSettings#uiSounds}. */
final class PanelSounds {
	private PanelSounds() {
	}

	static void open() {
		play(SoundEvents.UI_TOAST_IN, 1.0F, 0.6F);
	}

	static void close() {
		play(SoundEvents.UI_TOAST_OUT, 1.0F, 0.6F);
	}

	static void click() {
		play(SoundEvents.UI_BUTTON_CLICK.value(), 1.6F, 0.18F);
	}

	private static void play(SoundEvent sound, float pitch, float volume) {
		if (PanelSettings.get().uiSounds) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
		}
	}
}
