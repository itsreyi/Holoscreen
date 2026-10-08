package it.holoscreen.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class PanelSettingsScreen extends Screen {
	private static final int WIDGET_WIDTH = 180;
	private static final int WIDGET_HEIGHT = 20;
	private static final int COLUMN_GAP = 10;
	private static final int ROW = 24;
	private static final int ROWS = 4;
	private final Screen parent;

	public PanelSettingsScreen(Screen parent) {
		super(Component.translatable("screen.holoscreen.settings"));
		this.parent = parent;
	}

	private int top() {
		return height / 2 - ROW * ROWS / 2 - 8;
	}

	@Override
	protected void init() {
		PanelSettings settings = PanelSettings.get();
		int left = width / 2 - WIDGET_WIDTH - COLUMN_GAP / 2;
		int rightColumn = width / 2 + COLUMN_GAP / 2;
		int top = top();

		// Display column.
		addRenderableWidget(CycleButton.builder(PanelSettings.Visibility::label)
			.withValues(PanelSettings.Visibility.values())
			.withInitialValue(settings.visibility)
			.create(left, top, WIDGET_WIDTH, WIDGET_HEIGHT,
				Component.translatable("options.holoscreen.visibility"),
				(button, value) -> settings.visibility = value));
		addRenderableWidget(new CornerRadiusSlider(left, top + ROW, WIDGET_WIDTH, WIDGET_HEIGHT));
		addRenderableWidget(new AnimationSlider(left, top + ROW * 2, WIDGET_WIDTH, WIDGET_HEIGHT));
		addRenderableWidget(CycleButton.onOffBuilder(settings.shadow)
			.create(left, top + ROW * 3, WIDGET_WIDTH, WIDGET_HEIGHT,
				Component.translatable("options.holoscreen.shadow"),
				(button, value) -> settings.shadow = value));

		// Audio and feedback column.
		addRenderableWidget(CycleButton.builder(PanelSettings.AudioMode::label)
			.withValues(PanelSettings.AudioMode.values())
			.withInitialValue(settings.audio)
			.create(rightColumn, top, WIDGET_WIDTH, WIDGET_HEIGHT,
				Component.translatable("options.holoscreen.audio"),
				(button, value) -> settings.audio = value));
		addRenderableWidget(new AudioRangeSlider(rightColumn, top + ROW, WIDGET_WIDTH, WIDGET_HEIGHT));
		addRenderableWidget(CycleButton.onOffBuilder(settings.pauseWhenFar)
			.create(rightColumn, top + ROW * 2, WIDGET_WIDTH, WIDGET_HEIGHT,
				Component.translatable("options.holoscreen.pause_when_far"),
				(button, value) -> settings.pauseWhenFar = value));
		addRenderableWidget(CycleButton.onOffBuilder(settings.uiSounds)
			.create(rightColumn, top + ROW * 3, WIDGET_WIDTH, WIDGET_HEIGHT,
				Component.translatable("options.holoscreen.ui_sounds"),
				(button, value) -> settings.uiSounds = value));

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(width / 2 - 100, top + ROW * ROWS + 10, 200, WIDGET_HEIGHT)
			.build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, top() - 22, 0xFFFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		PanelSettings.save();
		minecraft.setScreen(parent);
	}

	private static final class CornerRadiusSlider extends AbstractSliderButton {
		CornerRadiusSlider(int x, int y, int width, int height) {
			super(x, y, width, height, CommonComponents.EMPTY, PanelSettings.get().cornerRadius / PanelSettings.MAX_CORNER_RADIUS);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			long percent = Math.round(value * PanelSettings.MAX_CORNER_RADIUS * 100);
			setMessage(Component.translatable("options.holoscreen.corner_radius", percent));
		}

		@Override
		protected void applyValue() {
			PanelSettings.get().cornerRadius = value * PanelSettings.MAX_CORNER_RADIUS;
		}
	}

	private static final class AudioRangeSlider extends AbstractSliderButton {
		private static final int SPAN = PanelSettings.MAX_AUDIO_RANGE - PanelSettings.MIN_AUDIO_RANGE;

		AudioRangeSlider(int x, int y, int width, int height) {
			super(x, y, width, height, CommonComponents.EMPTY,
				(double) (PanelSettings.get().audioRange - PanelSettings.MIN_AUDIO_RANGE) / SPAN);
			updateMessage();
		}

		private int blocks() {
			return PanelSettings.MIN_AUDIO_RANGE + (int) Math.round(value * SPAN);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.holoscreen.audio_range", blocks()));
		}

		@Override
		protected void applyValue() {
			PanelSettings.get().audioRange = blocks();
		}
	}

	private static final class AnimationSlider extends AbstractSliderButton {
		private static final int STEP = 50;

		AnimationSlider(int x, int y, int width, int height) {
			super(x, y, width, height, CommonComponents.EMPTY, (double) PanelSettings.get().animationMillis / PanelSettings.MAX_ANIMATION_MILLIS);
			updateMessage();
		}

		private int millis() {
			return (int) Math.round(value * PanelSettings.MAX_ANIMATION_MILLIS / STEP) * STEP;
		}

		@Override
		protected void updateMessage() {
			int millis = millis();
			setMessage(millis == 0
				? Component.translatable("options.holoscreen.animation.off")
				: Component.translatable("options.holoscreen.animation", millis));
		}

		@Override
		protected void applyValue() {
			PanelSettings.get().animationMillis = millis();
		}
	}
}
