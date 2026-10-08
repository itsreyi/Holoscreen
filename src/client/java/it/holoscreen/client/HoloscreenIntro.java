package it.holoscreen.client;

import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Native loading splash drawn over the panel while the page loads (platform icon, loading bar, small Holoscreen
 * wordmark), so the panel reacts instantly instead of showing an empty texture for a few seconds. It plays for at least {@link #MIN_MILLIS}, waits for the browser to
 * finish loading, then completes its progress bar and fades out over the page.
 *
 * <p>All layout values are fractions of the panel height, so the intro looks the same at any panel size.
 * Panel-local x points to the viewer's left, so viewer-space x is negated when emitting geometry.
 */
final class HoloscreenIntro {
	/** Pre-rendered "Holoscreen" wordmark (Inter Light, white on transparent); see the generator notes in CLAUDE.md. */
	static final ResourceLocation WORDMARK = ResourceLocation.fromNamespaceAndPath("holoscreen", "textures/misc/wordmark.png");
	static final double WORDMARK_ASPECT = 776.0 / 127.0;
	private static final Set<ResourceLocation> FILTERED = new HashSet<>();
	private static final long MIN_MILLIS = 1500;
	private static final long MAX_MILLIS = 12000;
	/** If the browser never reports loading (e.g. instant cache hit), treat it as loaded after this long. */
	private static final long NO_LOAD_SIGNAL_MILLIS = 2500;
	private static final long SETTLE_MILLIS = 400;
	private static final long OUTRO_MILLIS = 520;
	private static final int BACKGROUND = 0x0B0B0D;

	/** Null for the Holoscreen brand intro shown while Chromium starts; otherwise that platform's loader. */
	private final Platform platform;
	private final long startedAt = millis();
	private boolean sawLoading;
	private long idleSince = -1;
	private long outroAt = -1;
	private boolean done;

	HoloscreenIntro(Platform platform) {
		this.platform = platform;
	}

	boolean isDone() {
		return done;
	}

	/** True while the intro is fully opaque, so the page texture does not need to be drawn underneath. */
	boolean coversPage() {
		return !done && outroAt < 0;
	}

	/** Polls the browser's load state; call from the client tick. */
	void update(MCEFBrowser browser) {
		if (done || outroAt >= 0) {
			return;
		}
		long now = millis();
		boolean loading = browser.isLoading();
		if (loading) {
			sawLoading = true;
			idleSince = -1;
		} else if (idleSince < 0) {
			idleSince = now;
		}
		long elapsed = now - startedAt;
		boolean pageReady = !loading
			&& (sawLoading || elapsed >= NO_LOAD_SIGNAL_MILLIS)
			&& now - idleSince >= SETTLE_MILLIS;
		if ((pageReady && elapsed >= MIN_MILLIS) || elapsed >= MAX_MILLIS) {
			outroAt = now;
		}
	}

	void render(Matrix4f pose, Vec3 origin, Vec3 towardCamera, Vec3 up,
		double halfHeight, List<double[]> outline, float panelOpacity) {
		long now = millis();
		double time = now - startedAt;
		double outro = outroAt < 0 ? 0 : phase(now - outroAt, 0, OUTRO_MILLIS);
		if (outro >= 1) {
			done = true;
			return;
		}
		double fade = outroAt < 0 ? 1 : 1 - easeInOutCubic(phase(now - outroAt, 140, OUTRO_MILLIS - 140));
		double opacity = panelOpacity * fade;
		double panelHeight = halfHeight * 2;
		double scale = panelHeight * (1 + 0.035 * easeInOutCubic(outro));
		Vec3 front = origin.add(towardCamera.scale(0.004));

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		WorldVideoPanel.fillPolygon(pose, origin, 0, 0, outline, argb(opacity, BACKGROUND));
		// The decorations below are coplanar and overlap; if they wrote depth, GL_LEQUAL (Realistic visibility)
		// would z-fight between them. Only the background writes depth; draw order handles the layering.
		// renderPanel restores the depth mask afterwards.
		RenderSystem.depthMask(false);

		if (platform == null) {
			// Brand intro: the ring draws itself clockwise from the top while the glow fades in, the center dot pops
			// in, the wordmark rises in, and the loading bar runs below.
			drawLens(pose, front, scale,
				easeOutCubic(phase(time, 150, 900)),
				easeInOutCubic(phase(time, 0, 780)),
				phase(time, 380, 460),
				opacity);
			drawWordmark(pose, front.add(towardCamera.scale(0.002)), towardCamera, up, scale,
				-0.135 * scale, 0.105 * scale, easeOutCubic(phase(time, 520, 700)), opacity);
			renderProgress(pose, front, time, now, scale, -0.31 * scale, opacity);
			return;
		}

		// Platform loader: the icon pops in over a soft glow, the loading bar sits right below it, and a small
		// Holoscreen wordmark signs the bottom.
		double iconY = 0.1 * scale;
		fillRadial(pose, front, 0, iconY, 0.34 * scale, argb(0.10 * easeOutCubic(phase(time, 100, 900)) * opacity, 0xFFFFFF));
		double pop = phase(time, 0, 560);
		if (pop > 0) {
			double size = 0.26 * scale * easeOutBack(pop);
			drawTexture(pose, front.add(towardCamera.scale(0.001)), towardCamera, up, platform.icon(),
				0, iconY - (1 - easeOutCubic(pop)) * 0.03 * scale, size, size, easeOutCubic(pop) * opacity);
		}
		renderProgress(pose, front, time, now, scale, -0.13 * scale, opacity);
		drawWordmark(pose, front.add(towardCamera.scale(0.002)), towardCamera, up, scale,
			-0.36 * scale, 0.05 * scale, easeOutCubic(phase(time, 600, 700)), 0.45 * opacity);
	}

	/**
	 * Static logo and wordmark for the back of the panel, in subdued white. The caller has already drawn the back
	 * fill (which writes depth) and must restore cull/depth state afterwards, as after {@link #render}.
	 */
	static void renderBadge(Matrix4f pose, Vec3 origin, Vec3 towardCamera, Vec3 up, double halfHeight, double opacity) {
		double scale = halfHeight * 2 * 0.85;
		double badgeOpacity = opacity * 0.55;
		Vec3 front = origin.add(towardCamera.scale(0.004));
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.depthMask(false);
		drawLens(pose, front, scale, 0.5, 1, 1, badgeOpacity);
		drawWordmark(pose, front.add(towardCamera.scale(0.002)), towardCamera, up, scale,
			-0.135 * scale, 0.105 * scale, 1, badgeOpacity);
	}

	/**
	 * Holo lens: soft glow, a ring swept clockwise from the top (for a front viewer) and a center dot.
	 * glow/sweep/pop are 0..1 progress values; geometry is symmetric so it also reads correctly from behind.
	 */
	private static void drawLens(Matrix4f pose, Vec3 origin, double scale, double glow, double sweep, double pop, double opacity) {
		double ringY = 0.1 * scale;
		double ringRadius = 0.13 * scale;
		fillRadial(pose, origin, 0, ringY, ringRadius * 2.4, argb(0.10 * glow * opacity, 0xFFFFFF));
		if (sweep > 0.01) {
			WorldVideoPanel.strokePath(pose, origin, arc(0, ringY, ringRadius, sweep), 0.012 * scale, argb(0.92 * opacity, 0xFFFFFF));
		}
		if (pop > 0) {
			WorldVideoPanel.fillPolygon(pose, origin, 0, ringY,
				WorldVideoPanel.circle(0.042 * scale * easeOutBack(pop)), argb(opacity, 0xFFFFFF));
		}
	}

	static int argbOf(double alpha, int rgb) {
		return argb(alpha, rgb);
	}

	private void renderProgress(Matrix4f pose, Vec3 origin, double time, long now, double scale, double barY, double opacity) {
		double appear = easeOutCubic(phase(time, 450, 420));
		if (appear <= 0) {
			return;
		}
		double halfBar = 0.2 * scale * (0.6 + 0.4 * appear);
		double thickness = 0.008 * scale;
		WorldVideoPanel.strokePath(pose, origin, line(-halfBar, halfBar, barY), thickness, argb(0.16 * appear * opacity, 0xFFFFFF));

		double from;
		double to;
		if (outroAt < 0) {
			// Indeterminate: a short segment sweeps left to right.
			double cycle = ((time - 450) % 1150) / 1150;
			double segment = 0.32 * halfBar * 2;
			double head = -halfBar + easeInOutCubic(cycle) * (halfBar * 2 + segment);
			from = Math.max(-halfBar, head - segment);
			to = Math.min(halfBar, head);
		} else {
			// Loaded: fill the remaining track before fading out.
			from = -halfBar;
			to = -halfBar + halfBar * 2 * easeOutCubic(phase(now - outroAt, 0, 260));
		}
		if (to - from > thickness * 0.5) {
			WorldVideoPanel.strokePath(pose, origin, line(from, to, barY), thickness, argb(0.95 * appear * opacity, 0xFFFFFF));
		}
	}

	/**
	 * Wordmark centered at centerY. reveal (0..1) fades it in while it rises slightly and tightens from a wider
	 * letter spacing, so the texture keeps the feel of a per-letter entrance.
	 */
	private static void drawWordmark(Matrix4f pose, Vec3 origin, Vec3 towardCamera, Vec3 up,
		double scale, double centerY, double markHeight, double reveal, double opacity) {
		double markWidth = markHeight * WORDMARK_ASPECT * (1 + 0.06 * (1 - reveal));
		drawTexture(pose, origin, towardCamera, up, WORDMARK, 0, centerY - (1 - reveal) * 0.025 * scale,
			markWidth, markHeight, reveal * opacity);
	}

	/**
	 * Textured quad centered at (viewerX, centerY), where viewerX grows toward the viewer's right, so the texture
	 * reads correctly from either side of the panel. Must be called with blending enabled.
	 */
	static void drawTexture(Matrix4f pose, Vec3 origin, Vec3 towardCamera, Vec3 up, ResourceLocation texture,
		double viewerX, double centerY, double quadWidth, double quadHeight, double opacity) {
		int alpha = (int) Math.round(255 * PanelMath.clamp(opacity, 0, 1));
		if (alpha <= 0 || quadWidth <= 0 || quadHeight <= 0) {
			return;
		}
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.setShaderTexture(0, texture);
		if (FILTERED.add(texture)) {
			// Bilinear filtering keeps the downscaled texture smooth.
			Minecraft.getInstance().getTextureManager().getTexture(texture).setFilter(true, false);
		}
		// Viewer's right on the panel plane, valid from either side (up x towardCamera).
		Vec3 viewerRight = up.cross(towardCamera);
		Vec3 anchor = origin.add(viewerRight.scale(viewerX)).add(up.scale(centerY));
		Vec3 halfAcross = viewerRight.scale(quadWidth / 2);
		Vec3 halfUp = up.scale(quadHeight / 2);
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		texturedVertex(buffer, pose, anchor.subtract(halfAcross).subtract(halfUp), 0, 1, alpha);
		texturedVertex(buffer, pose, anchor.add(halfAcross).subtract(halfUp), 1, 1, alpha);
		texturedVertex(buffer, pose, anchor.add(halfAcross).add(halfUp), 1, 0, alpha);
		texturedVertex(buffer, pose, anchor.subtract(halfAcross).add(halfUp), 0, 0, alpha);
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.setShaderTexture(0, 0);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
	}

	private static void texturedVertex(BufferBuilder buffer, Matrix4f pose, Vec3 point, float u, float v, int alpha) {
		buffer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z).setUv(u, v).setColor(255, 255, 255, alpha);
	}

	/** Filled disc fading from the given color at the center to transparent at the edge. */
	private static void fillRadial(Matrix4f pose, Vec3 origin, double x, double y, double radius, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		List<double[]> edge = WorldVideoPanel.circle(radius);
		int transparent = argb & 0xFFFFFF;
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		for (int index = 0; index < edge.size(); index++) {
			double[] first = edge.get(index);
			double[] second = edge.get((index + 1) % edge.size());
			WorldVideoPanel.coloredVertex(buffer, pose, origin, x, y, argb);
			WorldVideoPanel.coloredVertex(buffer, pose, origin, x + first[0], y + first[1], transparent);
			WorldVideoPanel.coloredVertex(buffer, pose, origin, x + second[0], y + second[1], transparent);
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}

	/** Arc starting at the top and sweeping clockwise for the viewer (counter-clockwise in panel-local space). */
	private static List<double[]> arc(double centerX, double centerY, double radius, double fraction) {
		int steps = Math.max(2, (int) Math.ceil(56 * fraction));
		List<double[]> points = new ArrayList<>(steps + 1);
		for (int step = 0; step <= steps; step++) {
			double angle = Math.PI / 2 + 2 * Math.PI * fraction * step / steps;
			points.add(new double[]{centerX + radius * Math.cos(angle), centerY + radius * Math.sin(angle)});
		}
		return points;
	}

	/** Horizontal segment given in viewer space (left to right). */
	private static List<double[]> line(double viewerFrom, double viewerTo, double y) {
		return List.of(new double[]{-viewerFrom, y}, new double[]{-viewerTo, y});
	}

	private static int argb(double alpha, int rgb) {
		return ((int) Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24) | rgb;
	}

	private static double phase(double time, double start, double duration) {
		return Math.max(0, Math.min(1, (time - start) / duration));
	}

	private static double easeOutCubic(double t) {
		double inverse = 1 - t;
		return 1 - inverse * inverse * inverse;
	}

	private static double easeInOutCubic(double t) {
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	private static double easeOutBack(double t) {
		double overshoot = 1.70158;
		double shifted = t - 1;
		return 1 + (overshoot + 1) * shifted * shifted * shifted + overshoot * shifted * shifted;
	}

	private static long millis() {
		return System.nanoTime() / 1_000_000;
	}
}
