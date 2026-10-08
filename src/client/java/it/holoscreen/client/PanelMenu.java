package it.holoscreen.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Start menu drawn on the panel itself: a row of platform cards (icon + name) under a small wordmark.
 * Layout values are fractions of the panel height; x is in viewer space (growing to the viewer's right), and the
 * menu is only drawn on the front, where panel-local x = -viewer x.
 */
final class PanelMenu {
	private static final double CARD_WIDTH = 0.34;
	private static final double CARD_HEIGHT = 0.44;
	private static final double CARD_GAP = 0.06;
	private static final double CARDS_Y = -0.05;
	private static final double CARD_RADIUS = 0.05;
	private static final double STAGGER_MILLIS = 70;
	private static final int BACKGROUND = 0x0B0B0D;
	private static final int CARD_COLOR = 0x17171A;
	private static final int CARD_HOVER_COLOR = 0x222226;

	private PanelMenu() {
	}

	/** Viewer-space x of a card's center. */
	static double cardCenterX(int index, double panelHeight) {
		int count = Platform.values().length;
		double total = count * CARD_WIDTH + (count - 1) * CARD_GAP;
		return (-total / 2 + CARD_WIDTH / 2 + index * (CARD_WIDTH + CARD_GAP)) * panelHeight;
	}

	/** Card under a viewer-space point, or null. */
	static Platform cardAt(double viewerX, double y, double panelHeight) {
		for (int index = 0; index < Platform.values().length; index++) {
			if (Math.abs(viewerX - cardCenterX(index, panelHeight)) <= CARD_WIDTH * panelHeight / 2
				&& Math.abs(y - CARDS_Y * panelHeight) <= CARD_HEIGHT * panelHeight / 2) {
				return Platform.values()[index];
			}
		}
		return null;
	}

	/**
	 * Draws the menu. The background writes depth like the page would; decorations do not (see the panel's
	 * z-fighting notes). The caller restores cull/depth state afterwards.
	 *
	 * @param millisShown time since the menu appeared, for the staggered card entrance
	 */
	static void render(Matrix4f pose, Vec3 origin, Vec3 towardCamera, Vec3 up, double halfHeight,
		List<double[]> outline, double opacity, double millisShown, Platform hovered) {
		double panelHeight = halfHeight * 2;
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		WorldVideoPanel.fillPolygon(pose, origin, 0, 0, outline, HoloscreenIntro.argbOf(opacity, BACKGROUND));
		RenderSystem.depthMask(false);

		Vec3 front = origin.add(towardCamera.scale(0.004));
		double title = ease(millisShown / 450);
		HoloscreenIntro.drawTexture(pose, front, towardCamera, up, HoloscreenIntro.WORDMARK,
			0, (0.35 - 0.02 * (1 - title)) * panelHeight, 0.065 * panelHeight * HoloscreenIntro.WORDMARK_ASPECT,
			0.065 * panelHeight, 0.7 * title * opacity);

		Platform[] platforms = Platform.values();
		for (int index = 0; index < platforms.length; index++) {
			Platform platform = platforms[index];
			double reveal = ease((millisShown - 120 - index * STAGGER_MILLIS) / 420);
			if (reveal <= 0) {
				continue;
			}
			boolean active = platform == hovered;
			double viewerX = cardCenterX(index, panelHeight);
			double y = (CARDS_Y - 0.04 * (1 - reveal) + (active ? 0.012 : 0)) * panelHeight;
			double alpha = reveal * opacity;
			double halfCardWidth = CARD_WIDTH * panelHeight / 2;
			double halfCardHeight = CARD_HEIGHT * panelHeight / 2;
			double radius = CARD_RADIUS * panelHeight;
			RenderSystem.setShader(GameRenderer::getPositionColorShader);
			if (active) {
				// Hairline white border: a slightly larger rounded rect behind the card.
				double border = 0.008 * panelHeight;
				WorldVideoPanel.fillPolygon(pose, front, -viewerX, y,
					PanelMath.roundedRect(halfCardWidth + border, halfCardHeight + border, radius + border),
					HoloscreenIntro.argbOf(0.6 * alpha, 0xFFFFFF));
			}
			WorldVideoPanel.fillPolygon(pose, front, -viewerX, y,
				PanelMath.roundedRect(halfCardWidth, halfCardHeight, radius),
				HoloscreenIntro.argbOf(alpha, active ? CARD_HOVER_COLOR : CARD_COLOR));
			double iconSize = 0.17 * panelHeight;
			HoloscreenIntro.drawTexture(pose, front, towardCamera, up, platform.icon(),
				viewerX, y + 0.06 * panelHeight, iconSize, iconSize, alpha);
			HoloscreenIntro.drawTexture(pose, front, towardCamera, up, platform.labelTexture(),
				viewerX, y - 0.13 * panelHeight, 0.24 * panelHeight, 0.06 * panelHeight, (active ? 1 : 0.8) * alpha);
		}
	}

	private static double ease(double t) {
		double clamped = Math.max(0, Math.min(1, t));
		double inverse = 1 - clamped;
		return 1 - inverse * inverse * inverse;
	}
}
