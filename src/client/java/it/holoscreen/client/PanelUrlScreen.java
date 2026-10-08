package it.holoscreen.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Minimal address bar: a link opens directly, anything else is searched on the current platform. */
public final class PanelUrlScreen extends Screen {
	private static final int BAR_WIDTH = 320;
	private static final int BAR_HEIGHT = 22;
	private static final int CONTAINER_COLOR = 0xCC000000;
	private final Screen parent;
	private EditBox input;

	public PanelUrlScreen(Screen parent) {
		super(Component.translatable("screen.holoscreen.url"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = (width - BAR_WIDTH) / 2;
		int top = height / 2 - BAR_HEIGHT / 2;
		input = new EditBox(font, left + 12, top + (BAR_HEIGHT - 8) / 2, BAR_WIDTH - 24, 12, title);
		input.setBordered(false);
		input.setMaxLength(2048);
		input.setTextColor(0xFFFFFFFF);
		input.setHint(Component.translatable("screen.holoscreen.url.hint"));
		input.setValue(WorldVideoPanel.currentUrl());
		input.moveCursorToEnd(false);
		input.setHighlightPos(0);
		addRenderableWidget(input);
		setInitialFocus(input);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int left = (width - BAR_WIDTH) / 2;
		int top = height / 2 - BAR_HEIGHT / 2;
		GuiShapes.fillRounded(graphics, left, top, left + BAR_WIDTH, top + BAR_HEIGHT, BAR_HEIGHT / 2, CONTAINER_COLOR);
		super.render(graphics, mouseX, mouseY, partialTick);
		Component hint = Component.translatable("screen.holoscreen.url.keys");
		graphics.drawString(font, hint, (width - font.width(hint)) / 2, top + BAR_HEIGHT + 8, 0xB3FFFFFF, true);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			String target = UrlInput.toUrl(input.getValue(), WorldVideoPanel.platform().searchPrefix());
			if (target != null) {
				WorldVideoPanel.loadUrl(target);
			}
			onClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
