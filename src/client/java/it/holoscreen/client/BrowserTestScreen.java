package it.holoscreen.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import it.holoscreen.HoloscreenMod;

public final class BrowserTestScreen extends Screen {
	private MCEFBrowser browser;
	private Component browserError;

	public BrowserTestScreen() {
		super(Component.literal("YouTube browser test"));
	}

	@Override
	protected void init() {
		try {
			browser = MCEF.createBrowser("https://www.youtube.com", false);
			if (browser == null) {
				browserError = Component.translatable("message.holoscreen.browser_failed");
				return;
			}
			BrowserCursor.install(browser, () -> Minecraft.getInstance().screen instanceof BrowserTestScreen);
			resizeBrowser();
		} catch (RuntimeException | LinkageError exception) {
			browserError = Component.translatable("message.holoscreen.browser_failed");
			HoloscreenMod.LOGGER.error("Could not create the MCEF browser", exception);
		}
	}

	@Override
	public void resize(Minecraft minecraft, int width, int height) {
		super.resize(minecraft, width, height);
		resizeBrowser();
	}

	private void resizeBrowser() {
		if (browser != null && width > 0 && height > 0) {
			double scale = Minecraft.getInstance().getWindow().getGuiScale();
			browser.resize((int) (width * scale), (int) (height * scale));
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		if (browserError != null) {
			graphics.drawCenteredString(font, browserError, width / 2, height / 2, 0xFFFFFFFF);
			return;
		}
		if (browser == null || browser.getRenderer() == null) {
			return;
		}

		RenderSystem.disableDepthTest();
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.setShaderTexture(0, browser.getRenderer().getTextureID());
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		buffer.addVertex(0, height, 0).setUv(0, 1).setColor(255, 255, 255, 255);
		buffer.addVertex(width, height, 0).setUv(1, 1).setColor(255, 255, 255, 255);
		buffer.addVertex(width, 0, 0).setUv(1, 0).setColor(255, 255, 255, 255);
		buffer.addVertex(0, 0, 0).setUv(0, 0).setColor(255, 255, 255, 255);
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.setShaderTexture(0, 0);
		RenderSystem.enableDepthTest();
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (browser != null) {
			browser.sendMousePress(toBrowserX(mouseX), toBrowserY(mouseY), button);
			browser.setFocus(true);
		}
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (browser != null) {
			browser.sendMouseRelease(toBrowserX(mouseX), toBrowserY(mouseY), button);
		}
		return true;
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (browser != null) {
			browser.sendMouseMove(toBrowserX(mouseX), toBrowserY(mouseY));
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (browser != null) {
			browser.sendMouseWheel(toBrowserX(mouseX), toBrowserY(mouseY), scrollY, 0);
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 256) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (browser != null) {
			browser.sendKeyPress(keyCode, scanCode, modifiers);
			browser.setFocus(true);
		}
		return true;
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		if (browser != null) {
			browser.sendKeyRelease(keyCode, scanCode, modifiers);
		}
		return true;
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (browser != null && codePoint != 0) {
			browser.sendKeyTyped(codePoint, modifiers);
		}
		return true;
	}

	@Override
	public void removed() {
		if (browser != null) {
			browser.close();
			browser = null;
		}
		BrowserCursor.reset();
	}

	private int toBrowserX(double x) {
		return (int) (x * Minecraft.getInstance().getWindow().getGuiScale());
	}

	private int toBrowserY(double y) {
		return (int) (y * Minecraft.getInstance().getWindow().getGuiScale());
	}
}