package it.holoscreen.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.holoscreen.HoloscreenMod;
import it.holoscreen.net.ScreenState;
import it.holoscreen.net.ScreenValidation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/**
 * The single world-space video panel. Its transform ("placement") is independent of the browser: the placement is
 * persisted per world/server, while the browser only exists while the player is in the panel's dimension.
 */
public final class WorldVideoPanel {
	public enum EditMode {
		RESIZE, MOVE_XY, ZOOM, ORBIT, ROTATE
	}

	/** How the panel is anchored: fixed in the world, following the player's position, or following position and head rotation. */
	public enum Follow {
		FIXED, POSITION, HEAD;

		public Follow next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** World render events the panel can be drawn from; see {@link #wantedStage}. */
	public enum Stage {
		BEFORE_ENTITIES, LAST
	}

	static final String HOME_URL = Platform.YOUTUBE.homeUrl();
	private static final double INITIAL_WIDTH = 2.4;
	private static final double MIN_WIDTH = 0.6;
	private static final double MAX_WIDTH = 8.0;
	private static final double PLACE_DISTANCE = 3.0;
	private static final double EDGE_BAND = 0.10;
	private static final int BROWSER_WIDTH = 1280;
	private static final int BROWSER_HEIGHT = 720;
	private static final double FRAME_GAP = 0.03;
	private static final double HANDLE_THICKNESS = 0.018;
	private static final double SHADOW_SPREAD = 0.16;
	private static final int BACK_COLOR = 0x232326;
	private static final int AUDIO_UPDATE_TICKS = 2;
	private static final double ROTATE_DEGREES_PER_PIXEL = 0.5;
	private static final double ROLL_DEGREES_PER_NOTCH = 5;
	private static final double SNAP_DEGREES = 15;
	private static final int UNDO_LIMIT = 50;
	private static final long SCROLL_UNDO_MERGE_NANOS = 500_000_000L;

	private enum DragMode {
		NONE, MOVE, ORBIT, ROTATE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
	}

	private static final DragMode[] HANDLES = {
		DragMode.BOTTOM_LEFT, DragMode.BOTTOM, DragMode.BOTTOM_RIGHT, DragMode.RIGHT,
		DragMode.TOP_RIGHT, DragMode.TOP, DragMode.TOP_LEFT, DragMode.LEFT
	};

	private record PanelPoint(Vec3 world, double x, double y) {
	}

	/** Snapshot of everything an edit can change, for undo/redo. */
	private record Transform(Vec3 center, Vec3 look, Vec3 right, Vec3 up, double width, double height, Follow follow,
		Vec3 anchorOffset, Vec3 anchorLook, Vec3 anchorRight, Vec3 anchorUp) {
	}

	// Placement (persisted).
	private static Vec3 center;
	private static Vec3 look;
	private static Vec3 right;
	private static Vec3 up;
	private static double width = INITIAL_WIDTH;
	private static double height = INITIAL_WIDTH * BROWSER_HEIGHT / BROWSER_WIDTH;
	private static Follow follow = Follow.FIXED;
	// Transform expressed in the follow frame (see followFrame); only used when follow != FIXED.
	private static Vec3 anchorOffset;
	private static Vec3 anchorLook;
	private static Vec3 anchorRight;
	private static Vec3 anchorUp;
	private static String dimension;
	/** Page to show; null while the in-panel start menu is open (the browser then sits on about:blank). */
	private static String url;
	private static Platform platform = Platform.YOUTUBE;
	private static boolean menuOpen;
	/** Public (visible to every player on a server with Holoscreen) or private. */
	private static boolean shared;
	private static final PanelAudio.State AUDIO = new PanelAudio.State();
	/** When the menu became visible (after the brand intro), for its entrance animation; 0 while hidden. */
	private static long menuShownAt;
	private static String worldKey;

	// Browser and animation.
	private static MCEFBrowser browser;
	private static boolean browserFailed;
	private static HoloscreenIntro intro;
	private static long openedAt;
	private static long closingAt;
	private static boolean closing;
	private static int audioTicks;

	// Basis of the surface currently being drawn, used by the panel-local drawing helpers.
	private static Vec3 drawRight = new Vec3(1, 0, 0);
	private static Vec3 drawUp = new Vec3(0, 1, 0);

	// Picking, cached from the last rendered frame.
	private static Matrix4f inverseProjection;
	private static Quaternionf cameraRotation;
	private static Vec3 cameraPosition;
	private static float lastPartialTick = 1;

	// Interaction.
	private static EditMode editMode = EditMode.RESIZE;
	private static int lastMouseX;
	private static int lastMouseY;
	private static double cursorX;
	private static double cursorY;
	private static boolean cursorKnown;
	private static boolean mouseButtonDown;
	private static boolean browserMouseDown;
	private static DragMode dragMode = DragMode.NONE;
	private static Vec3 dragStartCenter;
	private static Vec3 dragOffset;
	private static Vec3 orbitPivot;
	private static Vec3 orbitStartOffset;
	private static double dragStartMouseX;
	private static double dragStartMouseY;
	private static double dragStartWidth;
	private static double dragStartHeight;
	private static double rotateYaw;
	private static double rotatePitch;
	private static double rotateRoll;
	private static final Deque<Transform> UNDO = new ArrayDeque<>();
	private static final Deque<Transform> REDO = new ArrayDeque<>();
	private static long lastScrollUndoAt;

	private WorldVideoPanel() {
	}

	// ---------------------------------------------------------------- lifecycle

	/** Placed in this world (possibly in another dimension) and not closing. */
	public static boolean isPlaced() {
		return center != null && !closing;
	}

	/** Placed, in the player's dimension and with a live browser, so it can be interacted with. */
	public static boolean isReady() {
		return isPlaced() && browser != null;
	}

	/**
	 * Places the panel in front of the player: a new panel plays the brand intro (while Chromium starts) and then
	 * shows the start menu. A panel that exists in another dimension is moved here instead, keeping its page.
	 */
	public static void place(Minecraft client) {
		boolean relocating = center != null;
		Player player = client.player;
		Vec3[] basis = PanelMath.basis(player.getLookAngle(), 0, new Vec3(1, 0, 0));
		look = basis[0];
		right = basis[1];
		up = basis[2];
		center = player.getEyePosition().add(look.scale(PLACE_DISTANCE));
		resetSize();
		follow = Follow.FIXED;
		dimension = currentDimension(client);
		if (!relocating) {
			url = null;
			menuOpen = true;
			menuShownAt = 0;
		}
		closing = false;
		browserFailed = false;
		UNDO.clear();
		REDO.clear();
		if (browser == null) {
			createBrowser();
		}
		PanelSounds.open();
		persist();
	}

	/** Starts the close animation; the placement is dropped by {@link #tick} once it finishes. */
	public static boolean remove() {
		if (!isPlaced()) {
			return false;
		}
		PanelSounds.close();
		if (browser == null || PanelSettings.get().animationMillis <= 0) {
			finishRemove();
			return true;
		}
		closing = true;
		closingAt = System.nanoTime();
		endDrag();
		return true;
	}

	private static void finishRemove() {
		releaseBrowser();
		clearPlacement();
		url = null;
		persist();
	}

	public static Platform platform() {
		return platform;
	}

	public static boolean isShared() {
		return shared;
	}

	public static void setShared(boolean value) {
		shared = value;
		persist();
	}

	/** Browser of the own panel, for playback probing; null when there is none. */
	static MCEFBrowser browser() {
		return browser;
	}

	/** State to publish for the own panel, or null when it is private, missing or closing. */
	static ScreenState publicState(Minecraft client) {
		if (!shared || center == null || closing || client.player == null || dimension == null) {
			return null;
		}
		double mediaTime = 0;
		boolean playing = false;
		PlaybackSync.Playback playback = browser == null ? null : PlaybackSync.latest(browser);
		if (playback != null && playback.hasVideo()) {
			playing = playback.playing();
			mediaTime = playback.time() + (playing ? (System.nanoTime() - playback.receivedAt()) / 1.0E9 : 0);
		}
		String page = menuOpen ? "" : currentUrl();
		if (!ScreenValidation.isAllowedUrl(page)) {
			// e.g. about:blank or an error page: show viewers the idle screen instead.
			page = "";
		}
		return new ScreenState(client.player.getUUID(), client.player.getGameProfile().getName(), dimension,
			center, look, right, up, width, height, page, platform.id(), playing, mediaTime);
	}

	public static boolean isMenuOpen() {
		return menuOpen;
	}

	/** Shows the start menu on the panel again (M in edit mode). */
	public static void openMenu() {
		if (!isReady()) {
			return;
		}
		menuOpen = true;
		menuShownAt = System.nanoTime();
		intro = null;
	}

	/** Leaves the menu and opens the chosen platform, playing its loader (icon + bar) while the page loads. */
	public static void choose(Platform chosen) {
		if (!isReady()) {
			return;
		}
		PanelSounds.click();
		menuOpen = false;
		menuShownAt = 0;
		platform = chosen;
		url = chosen.homeUrl();
		browser.loadURL(url);
		intro = new HoloscreenIntro(chosen);
		persist();
	}

	/** Restores the panel saved for the world/server just joined. */
	public static void onJoin(Minecraft client) {
		reset();
		worldKey = PanelStore.currentWorldKey(client);
		if (worldKey == null) {
			return;
		}
		PanelStore.Saved saved = PanelStore.load(worldKey);
		if (saved == null || saved.center == null || saved.look == null || saved.right == null || saved.up == null) {
			return;
		}
		center = vec(saved.center);
		look = vec(saved.look);
		right = vec(saved.right);
		up = vec(saved.up);
		width = PanelMath.clamp(saved.width, MIN_WIDTH, MAX_WIDTH);
		height = PanelMath.clamp(saved.height, MIN_WIDTH * BROWSER_HEIGHT / BROWSER_WIDTH, MAX_WIDTH * BROWSER_HEIGHT / BROWSER_WIDTH);
		follow = parseFollow(saved.follow);
		anchorOffset = vec(saved.anchorOffset);
		anchorLook = vec(saved.anchorLook);
		anchorRight = vec(saved.anchorRight);
		anchorUp = vec(saved.anchorUp);
		if (follow != Follow.FIXED && (anchorOffset == null || anchorLook == null || anchorRight == null || anchorUp == null)) {
			follow = Follow.FIXED;
		}
		dimension = saved.dimension;
		platform = Platform.parse(saved.platform, Platform.YOUTUBE);
		url = saved.url == null || saved.url.isBlank() ? null : saved.url;
		menuOpen = url == null;
		menuShownAt = 0;
		shared = saved.shared;
	}

	public static void onDisconnect() {
		if (closing) {
			finishRemove();
		} else {
			persist();
		}
		reset();
	}

	private static void reset() {
		releaseBrowser();
		clearPlacement();
		url = null;
		platform = Platform.YOUTUBE;
		menuOpen = false;
		menuShownAt = 0;
		worldKey = null;
		browserFailed = false;
	}

	public static void tick(Minecraft client) {
		if (closing && openness() <= 0) {
			finishRemove();
			return;
		}
		Player player = client.player;
		if (center == null || player == null) {
			return;
		}
		String current = currentDimension(client);
		if (follow != Follow.FIXED && !current.equals(dimension)) {
			// A following panel travels with the player.
			dimension = current;
		}
		boolean wanted = !closing && current.equals(dimension);
		if (wanted && browser == null && !browserFailed) {
			createBrowser();
		} else if (!wanted && browser != null && !closing) {
			persist();
			releaseBrowser();
		}
		if (browser == null) {
			return;
		}
		if (intro != null) {
			intro.update(browser);
			if (intro.isDone()) {
				intro = null;
			}
		}
		if (++audioTicks % AUDIO_UPDATE_TICKS == 0) {
			PanelAudio.update(browser, center, openness(), AUDIO, !shared);
		}
	}

	private static void createBrowser() {
		try {
			browser = MCEF.createBrowser(url == null ? "about:blank" : url, false);
		} catch (RuntimeException | LinkageError exception) {
			HoloscreenMod.LOGGER.error("Could not start the world-space MCEF browser", exception);
			browser = null;
		}
		if (browser == null) {
			browserFailed = true;
			Player player = Minecraft.getInstance().player;
			if (player != null) {
				player.displayClientMessage(Component.translatable("message.holoscreen.browser_failed"), false);
			}
			return;
		}
		browser.resize(BROWSER_WIDTH, BROWSER_HEIGHT);
		BrowserCursor.install(browser, () -> Minecraft.getInstance().screen instanceof PanelInteractionScreen screen && !screen.isEditMode());
		// The brand intro covers Chromium's start-up before the menu; a known page gets its platform loader.
		intro = new HoloscreenIntro(menuOpen ? null : platform);
		menuShownAt = 0;
		openedAt = System.nanoTime();
		audioTicks = 0;
	}

	private static void releaseBrowser() {
		if (browser != null) {
			String current = browser.getURL();
			if (!menuOpen && current != null && !current.isBlank()) {
				url = current;
			}
			browser.close();
			browser = null;
			BrowserCursor.reset();
		}
		intro = null;
		closing = false;
		endDrag();
	}

	/** Saves the placement for the current world/server (or deletes it when nothing is placed). */
	public static void persist() {
		if (worldKey != null) {
			PanelStore.save(worldKey, center == null ? null : saved());
		}
	}

	private static PanelStore.Saved saved() {
		PanelStore.Saved saved = new PanelStore.Saved();
		saved.dimension = dimension;
		saved.center = array(center);
		saved.look = array(look);
		saved.right = array(right);
		saved.up = array(up);
		saved.width = width;
		saved.height = height;
		saved.follow = follow.name();
		saved.anchorOffset = array(anchorOffset);
		saved.anchorLook = array(anchorLook);
		saved.anchorRight = array(anchorRight);
		saved.anchorUp = array(anchorUp);
		String current = browser == null || menuOpen ? null : browser.getURL();
		saved.url = menuOpen ? null : current == null || current.isBlank() ? url : current;
		saved.platform = platform.id();
		saved.shared = shared;
		return saved;
	}

	public static void loadUrl(String target) {
		url = target;
		menuOpen = false;
		menuShownAt = 0;
		if (browser != null) {
			browser.loadURL(target);
		}
		persist();
	}

	public static String currentUrl() {
		if (menuOpen) {
			return "";
		}
		String current = browser == null ? null : browser.getURL();
		return current == null || current.isBlank() ? (url == null ? "" : url) : current;
	}

	private static String currentDimension(Minecraft client) {
		return client.player.level().dimension().location().toString();
	}

	private static Follow parseFollow(String name) {
		try {
			return name == null ? Follow.FIXED : Follow.valueOf(name);
		} catch (IllegalArgumentException exception) {
			return Follow.FIXED;
		}
	}

	private static double[] array(Vec3 vector) {
		return vector == null ? null : new double[]{vector.x, vector.y, vector.z};
	}

	private static Vec3 vec(double[] values) {
		return values == null || values.length != 3 ? null : new Vec3(values[0], values[1], values[2]);
	}

	// ---------------------------------------------------------------- modes, follow, undo

	public static void setEditMode(EditMode mode) {
		editMode = mode;
		endDrag();
	}

	public static EditMode getEditMode() {
		return editMode;
	}

	public static Follow getFollow() {
		return follow;
	}

	public static void cycleFollow() {
		pushUndo();
		follow = follow.next();
		captureAnchor();
	}

	private static Transform snapshot() {
		return new Transform(center, look, right, up, width, height, follow, anchorOffset, anchorLook, anchorRight, anchorUp);
	}

	private static void restore(Transform transform) {
		center = transform.center();
		look = transform.look();
		right = transform.right();
		up = transform.up();
		width = transform.width();
		height = transform.height();
		follow = transform.follow();
		anchorOffset = transform.anchorOffset();
		anchorLook = transform.anchorLook();
		anchorRight = transform.anchorRight();
		anchorUp = transform.anchorUp();
	}

	private static void pushUndo() {
		if (center == null) {
			return;
		}
		UNDO.push(snapshot());
		while (UNDO.size() > UNDO_LIMIT) {
			UNDO.removeLast();
		}
		REDO.clear();
	}

	public static boolean undo() {
		if (UNDO.isEmpty() || !isReady()) {
			return false;
		}
		REDO.push(snapshot());
		restore(UNDO.pop());
		endDrag();
		return true;
	}

	public static boolean redo() {
		if (REDO.isEmpty() || !isReady()) {
			return false;
		}
		UNDO.push(snapshot());
		restore(REDO.pop());
		endDrag();
		return true;
	}

	/** Orthonormal {side, up, forward} frame the anchored transform is stored in. */
	private static Vec3[] followFrame(Player player, float partialTick) {
		return follow == Follow.HEAD
			? PanelMath.headFrame(player.getViewYRot(partialTick), player.getViewVector(partialTick))
			: PanelMath.worldFrame();
	}

	/** Stores the current world transform relative to the player; call after every edit while following. */
	private static void captureAnchor() {
		Player player = Minecraft.getInstance().player;
		if (follow == Follow.FIXED || player == null || center == null) {
			return;
		}
		Vec3[] frame = followFrame(player, lastPartialTick);
		anchorOffset = PanelMath.toLocal(center.subtract(player.getEyePosition(lastPartialTick)), frame);
		anchorLook = PanelMath.toLocal(look, frame);
		anchorRight = PanelMath.toLocal(right, frame);
		anchorUp = PanelMath.toLocal(up, frame);
	}

	private static void applyAnchor(float partialTick) {
		Player player = Minecraft.getInstance().player;
		if (player == null || anchorOffset == null) {
			return;
		}
		Vec3[] frame = followFrame(player, partialTick);
		center = player.getEyePosition(partialTick).add(PanelMath.toWorld(anchorOffset, frame));
		look = PanelMath.toWorld(anchorLook, frame);
		right = PanelMath.toWorld(anchorRight, frame);
		up = PanelMath.toWorld(anchorUp, frame);
	}

	// ---------------------------------------------------------------- rendering

	/** 1 when fully open, 0 when fully closed; animates between the two. */
	private static float openness() {
		int duration = PanelSettings.get().animationMillis;
		if (duration <= 0) {
			return closing ? 0 : 1;
		}
		float elapsed = (System.nanoTime() - (closing ? closingAt : openedAt)) / 1.0E6F / duration;
		float progress = Math.max(0, Math.min(1, elapsed));
		return closing ? 1 - progress : progress;
	}

	/**
	 * Over-blocks and realistic modes draw during BEFORE_ENTITIES so entities and clouds drawn later are depth-tested
	 * against the panel. Always-on-top, and any active shader pack (whose G-buffers would swallow mid-pass drawing),
	 * draw at LAST.
	 */
	private static Stage wantedStage(PanelSettings settings) {
		return settings.visibility == PanelSettings.Visibility.ALWAYS_ON_TOP || ShaderCompat.shaderPackInUse()
			? Stage.LAST
			: Stage.BEFORE_ENTITIES;
	}

	private static int depthFunc(PanelSettings settings) {
		return settings.visibility == PanelSettings.Visibility.REALISTIC && !ShaderCompat.shaderPackInUse()
			? GL11.GL_LEQUAL
			: GL11.GL_ALWAYS;
	}

	/**
	 * Everything needed to draw one screen surface (own or remote).
	 *
	 * @param showPage draw the browser texture (false while an intro covers it or the start menu is open)
	 * @param frontLayer extra layer drawn on the front above the page and below the intro (the start menu), or null
	 */
	record Surface(Vec3 center, Vec3 look, Vec3 right, Vec3 up, double width, double height, MCEFBrowser browser,
		HoloscreenIntro intro, float openness, boolean showPage, FrontLayer frontLayer) {
	}

	/** A layer drawn on the front of a surface, in the surface's coordinates. */
	interface FrontLayer {
		void draw(Matrix4f pose, Vec3 origin, Vec3 towardCamera, Vec3 up, double halfHeight, List<double[]> outline, double opacity);
	}

	public static void render(WorldRenderContext context, Stage stage) {
		PanelSettings settings = PanelSettings.get();
		if (stage != wantedStage(settings)) {
			return;
		}
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);
		lastPartialTick = partialTick;
		boolean own = browser != null && center != null && browser.getRenderer() != null && !(closing && openness() <= 0);
		if (!own && !RemoteScreens.hasAny()) {
			return;
		}
		if (own && follow != Follow.FIXED && dragMode == DragMode.NONE) {
			applyAnchor(partialTick);
		}

		Vec3 camera = context.camera().getPosition();
		inverseProjection = new Matrix4f(context.projectionMatrix()).invert();
		cameraRotation = new Quaternionf(context.camera().rotation());
		cameraPosition = camera;

		// Vertices are camera-relative and rotated by positionMatrix, so the model-view stack must be identity.
		// It already holds the camera rotation during AFTER_ENTITIES/LAST but not yet during BEFORE_ENTITIES.
		Matrix4f pose = new Matrix4f(context.positionMatrix());
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.identity();
		RenderSystem.applyModelViewMatrix();
		RenderSystem.enableDepthTest();
		// GL_ALWAYS draws over terrain but still writes depth, so entities and clouds behind the panel stay hidden.
		RenderSystem.depthFunc(depthFunc(settings));
		RenderSystem.depthMask(true);
		RenderSystem.disableCull();
		try {
			if (own && isVisible(context, center, width, height)) {
				if (menuOpen && menuShownAt == 0 && (intro == null || !intro.coversPage())) {
					menuShownAt = System.nanoTime();
				}
				boolean menuVisible = menuOpen && menuShownAt != 0;
				FrontLayer menu = menuVisible
					? (layerPose, origin, towardCamera, layerUp, halfHeight, outline, opacity) -> PanelMenu.render(layerPose, origin,
						towardCamera, layerUp, halfHeight, outline, opacity, (System.nanoTime() - menuShownAt) / 1.0E6, hoveredCard())
					: null;
				drawSurface(pose, camera, new Surface(center, look, right, up, width, height, browser, intro, openness(),
					!menuOpen && (intro == null || !intro.coversPage()), menu), settings);
				if (!closing && Minecraft.getInstance().screen instanceof PanelInteractionScreen screen) {
					boolean backFacing = camera.subtract(center).dot(look) > 0;
					Vec3 towardCamera = backFacing ? look : look.scale(-1);
					useBasis(right, up);
					renderOverlays(pose, center.subtract(camera), towardCamera, screen, settings);
				}
			}
			RemoteScreens.render(context, pose, camera, settings);
		} finally {
			RenderSystem.setShaderTexture(0, 0);
			RenderSystem.enableCull();
			RenderSystem.disableBlend();
			RenderSystem.depthFunc(GL11.GL_LEQUAL);
			RenderSystem.depthMask(true);
			modelView.popMatrix();
			RenderSystem.applyModelViewMatrix();
		}
	}

	static boolean isVisible(WorldRenderContext context, Vec3 surfaceCenter, double surfaceWidth, double surfaceHeight) {
		double reach = Math.max(surfaceWidth, surfaceHeight) / 2 + SHADOW_SPREAD + 0.4;
		return context.frustum() == null || context.frustum().isVisible(new AABB(surfaceCenter, surfaceCenter).inflate(reach));
	}

	/** Basis used by the panel-local drawing helpers (fillPolygon, strokePath, ...) for the surface being drawn. */
	private static void useBasis(Vec3 basisRight, Vec3 basisUp) {
		drawRight = basisRight;
		drawUp = basisUp;
	}

	/**
	 * Draws a screen surface: shadow, then either the back (dark fill + badge) or the page, front layer and intro.
	 * Must run inside {@link #render}'s GL setup.
	 */
	static void drawSurface(Matrix4f pose, Vec3 camera, Surface surface, PanelSettings settings) {
		useBasis(surface.right(), surface.up());
		Vec3 origin = surface.center().subtract(camera);
		// look points away from the viewer the panel was placed for, so a camera on the +look side sees the back.
		boolean backFacing = camera.subtract(surface.center()).dot(surface.look()) > 0;
		Vec3 towardCamera = backFacing ? surface.look() : surface.look().scale(-1);
		int alpha = Math.round(255 * easeOutCubic(surface.openness()));
		if (alpha <= 0) {
			return;
		}
		double scale = 0.7 + 0.3 * easeOutBack(surface.openness());
		double halfWidth = surface.width() * scale / 2;
		double halfHeight = surface.height() * scale / 2;
		double radius = settings.cornerRadius * Math.min(surface.width(), surface.height()) * scale;
		List<double[]> outline = PanelMath.roundedRect(halfWidth, halfHeight, radius);

		if (settings.shadow) {
			renderShadow(pose, origin.subtract(towardCamera.scale(0.003)), halfWidth, halfHeight, radius, alpha / 255.0);
			restorePanelState(settings);
		}
		if (alpha < 255) {
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
		} else {
			RenderSystem.disableBlend();
		}
		if (backFacing) {
			RenderSystem.setShader(GameRenderer::getPositionColorShader);
			fillPolygon(pose, origin, 0, 0, outline, (alpha << 24) | BACK_COLOR);
			HoloscreenIntro.renderBadge(pose, origin, towardCamera, surface.up(), halfHeight, alpha / 255.0);
			restorePanelState(settings);
			return;
		}
		if (surface.showPage() && surface.browser() != null && surface.browser().getRenderer() != null) {
			RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
			RenderSystem.setShaderTexture(0, surface.browser().getRenderer().getTextureID());
			BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
			for (int index = 0; index < outline.size(); index++) {
				double[] first = outline.get(index);
				double[] second = outline.get((index + 1) % outline.size());
				texturedVertex(buffer, pose, origin, 0, 0, halfWidth, halfHeight, alpha);
				texturedVertex(buffer, pose, origin, first[0], first[1], halfWidth, halfHeight, alpha);
				texturedVertex(buffer, pose, origin, second[0], second[1], halfWidth, halfHeight, alpha);
			}
			BufferUploader.drawWithShader(buffer.buildOrThrow());
			RenderSystem.setShaderTexture(0, 0);
		}
		if (surface.frontLayer() != null) {
			surface.frontLayer().draw(pose, origin, towardCamera, surface.up(), halfHeight, outline, alpha / 255.0);
			restorePanelState(settings);
		}
		if (surface.intro() != null && !surface.intro().isDone()) {
			surface.intro().render(pose, origin, towardCamera, surface.up(), halfHeight, outline, alpha / 255F);
			restorePanelState(settings);
		}
	}

	/** Menu card under the cursor while the interaction screen is open, or null. */
	private static Platform hoveredCard() {
		if (!cursorKnown || !(Minecraft.getInstance().screen instanceof PanelInteractionScreen screen) || screen.isEditMode()) {
			return null;
		}
		PanelPoint point = panelPoint(Minecraft.getInstance(), cursorX, cursorY, 0, true);
		return point == null ? null : PanelMenu.cardAt(-point.x(), point.y(), height);
	}

	/** Soft halo fading out from the panel edge; drawn slightly behind the panel without writing depth. */
	private static void renderShadow(Matrix4f pose, Vec3 origin, double halfWidth, double halfHeight, double radius, double opacity) {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.depthMask(false);
		int inner = ((int) Math.round(0.38 * 255 * opacity) << 24);
		fillGradientBand(pose, origin,
			PanelMath.roundedRect(halfWidth + SHADOW_SPREAD, halfHeight + SHADOW_SPREAD, radius + SHADOW_SPREAD),
			PanelMath.roundedRect(halfWidth, halfHeight, radius),
			0, inner);
	}

	/** The wordmark's text render type, the intro and the shadow change GL state; restore what the panel expects. */
	private static void restorePanelState(PanelSettings settings) {
		RenderSystem.disableCull();
		RenderSystem.depthFunc(depthFunc(settings));
		RenderSystem.depthMask(true);
	}

	private static void texturedVertex(BufferBuilder buffer, Matrix4f pose, Vec3 origin, double x, double y, double halfWidth, double halfHeight, int alpha) {
		double[] uv = PanelMath.textureUv(x, y, halfWidth * 2, halfHeight * 2);
		Vec3 point = at(origin, x, y);
		buffer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
			.setUv((float) uv[0], (float) uv[1])
			.setColor(255, 255, 255, alpha);
	}

	private static void renderOverlays(Matrix4f pose, Vec3 origin, Vec3 towardCamera, PanelInteractionScreen screen, PanelSettings settings) {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		double radius = settings.cornerRadius * Math.min(width, height);
		Vec3 lifted = origin.add(towardCamera.scale(0.006));

		if (screen.isResizeMode()) {
			renderHandles(pose, lifted, radius);
		} else if (screen.isEditMode()) {
			fillBand(pose, lifted, 0, 0,
				PanelMath.roundedRect(width / 2 + FRAME_GAP, height / 2 + FRAME_GAP, radius + FRAME_GAP),
				PanelMath.roundedRect(width / 2 + FRAME_GAP - 0.008, height / 2 + FRAME_GAP - 0.008, radius + FRAME_GAP - 0.008),
				0x59FFFFFF);
		}
		if (screen.isOrbitMode()) {
			renderOrbitBar(pose, lifted);
		}
	}

	/** Minimal handles: C-shaped brackets hugging each rounded corner plus short bars on the edge midpoints. */
	private static void renderHandles(Matrix4f pose, Vec3 origin, double cornerRadius) {
		DragMode hovered = dragMode;
		if (hovered == DragMode.NONE && cursorKnown) {
			PanelPoint point = panelPoint(Minecraft.getInstance(), cursorX, cursorY, EDGE_BAND, false);
			if (point != null) {
				hovered = dragMode(point.x(), point.y());
			}
		}
		double halfWidth = width / 2 + FRAME_GAP;
		double halfHeight = height / 2 + FRAME_GAP;
		double radius = Math.min(cornerRadius + FRAME_GAP, Math.min(halfWidth, halfHeight));
		double tail = Math.min(0.2, Math.min(halfWidth, halfHeight) * 0.5);
		for (DragMode handle : HANDLES) {
			boolean active = handle == hovered;
			double thickness = active ? HANDLE_THICKNESS * 1.6 : HANDLE_THICKNESS;
			int color = active ? 0xFFFFFFFF : 0xB3FFFFFF;
			boolean corner = (isLeft(handle) || isRight(handle)) && (isTop(handle) || isBottom(handle));
			List<double[]> path = corner
				? cornerBracket(handle, halfWidth, halfHeight, radius, tail)
				: edgeBar(handle, halfWidth, halfHeight, tail);
			strokePath(pose, origin, path, thickness, color);
		}
	}

	private static List<double[]> cornerBracket(DragMode handle, double halfWidth, double halfHeight, double radius, double tail) {
		double centerX = isLeft(handle) ? -halfWidth + radius : halfWidth - radius;
		double centerY = isBottom(handle) ? -halfHeight + radius : halfHeight - radius;
		double startAngle = handle == DragMode.BOTTOM_RIGHT ? -90 : handle == DragMode.TOP_RIGHT ? 0 : handle == DragMode.TOP_LEFT ? 90 : 180;
		List<double[]> path = new ArrayList<>(PanelMath.CORNER_SEGMENTS + 3);
		for (int step = 0; step <= PanelMath.CORNER_SEGMENTS; step++) {
			double angle = Math.toRadians(startAngle + 90.0 * step / PanelMath.CORNER_SEGMENTS);
			path.add(new double[]{centerX + radius * Math.cos(angle), centerY + radius * Math.sin(angle)});
		}
		// Extend both ends along the counter-clockwise tangent so the bracket continues onto the straight edges.
		double start = Math.toRadians(startAngle);
		double end = Math.toRadians(startAngle + 90);
		double[] first = path.get(0);
		double[] last = path.get(path.size() - 1);
		path.add(0, new double[]{first[0] + Math.sin(start) * tail, first[1] - Math.cos(start) * tail});
		path.add(new double[]{last[0] - Math.sin(end) * tail, last[1] + Math.cos(end) * tail});
		return path;
	}

	private static List<double[]> edgeBar(DragMode handle, double halfWidth, double halfHeight, double length) {
		List<double[]> path = new ArrayList<>(2);
		if (handle == DragMode.LEFT || handle == DragMode.RIGHT) {
			double x = handle == DragMode.LEFT ? -halfWidth : halfWidth;
			path.add(new double[]{x, -length / 2});
			path.add(new double[]{x, length / 2});
		} else {
			double y = handle == DragMode.BOTTOM ? -halfHeight : halfHeight;
			path.add(new double[]{-length / 2, y});
			path.add(new double[]{length / 2, y});
		}
		return path;
	}

	/** A thin home-indicator style capsule below the panel. */
	private static void renderOrbitBar(Matrix4f pose, Vec3 origin) {
		boolean hovered = dragMode == DragMode.ORBIT;
		if (!hovered && cursorKnown) {
			PanelPoint point = panelCoordinates(Minecraft.getInstance(), cursorX, cursorY, false);
			hovered = point != null && isOrbitBar(point.x(), point.y());
		}
		double barY = -height / 2 - 0.14;
		double halfWidth = orbitBarWidth() / 2;
		List<double[]> path = List.of(new double[]{-halfWidth, barY}, new double[]{halfWidth, barY});
		strokePath(pose, origin, path, hovered ? 0.045 : 0.032, hovered ? 0xFFFFFFFF : 0x99FFFFFF);
	}

	/** Strokes an open polyline with round caps; joints use the averaged normal of the adjacent segments. */
	static void strokePath(Matrix4f pose, Vec3 origin, List<double[]> path, double thickness, int argb) {
		int count = path.size();
		double[][] outer = new double[count][];
		double[][] inner = new double[count][];
		for (int index = 0; index < count; index++) {
			double[] previous = path.get(Math.max(0, index - 1));
			double[] next = path.get(Math.min(count - 1, index + 1));
			double dx = next[0] - previous[0];
			double dy = next[1] - previous[1];
			double length = Math.sqrt(dx * dx + dy * dy);
			double normalX = length < 1.0E-9 ? 0 : -dy / length * thickness / 2;
			double normalY = length < 1.0E-9 ? 0 : dx / length * thickness / 2;
			double[] point = path.get(index);
			outer[index] = new double[]{point[0] + normalX, point[1] + normalY};
			inner[index] = new double[]{point[0] - normalX, point[1] - normalY};
		}
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		for (int index = 0; index + 1 < count; index++) {
			coloredVertex(buffer, pose, origin, outer[index][0], outer[index][1], argb);
			coloredVertex(buffer, pose, origin, outer[index + 1][0], outer[index + 1][1], argb);
			coloredVertex(buffer, pose, origin, inner[index + 1][0], inner[index + 1][1], argb);
			coloredVertex(buffer, pose, origin, outer[index][0], outer[index][1], argb);
			coloredVertex(buffer, pose, origin, inner[index + 1][0], inner[index + 1][1], argb);
			coloredVertex(buffer, pose, origin, inner[index][0], inner[index][1], argb);
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		double[] first = path.get(0);
		double[] last = path.get(count - 1);
		fillPolygon(pose, origin, first[0], first[1], circle(thickness / 2), argb);
		fillPolygon(pose, origin, last[0], last[1], circle(thickness / 2), argb);
	}

	/** Draws a convex polygon given in panel-local coordinates around (x, y). */
	static void fillPolygon(Matrix4f pose, Vec3 origin, double x, double y, List<double[]> points, int argb) {
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		for (int index = 0; index < points.size(); index++) {
			double[] first = points.get(index);
			double[] second = points.get((index + 1) % points.size());
			coloredVertex(buffer, pose, origin, x, y, argb);
			coloredVertex(buffer, pose, origin, x + first[0], y + first[1], argb);
			coloredVertex(buffer, pose, origin, x + second[0], y + second[1], argb);
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}

	/** Draws the ring between two outlines with the same number of points. */
	private static void fillBand(Matrix4f pose, Vec3 origin, double x, double y, List<double[]> outer, List<double[]> inner, int argb) {
		fillGradientBand(pose, origin.add(drawRight.scale(x)).add(drawUp.scale(y)), outer, inner, argb, argb);
	}

	/** Ring between two outlines with the same number of points, colored from outerArgb to innerArgb. */
	private static void fillGradientBand(Matrix4f pose, Vec3 origin, List<double[]> outer, List<double[]> inner, int outerArgb, int innerArgb) {
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		for (int index = 0; index < outer.size(); index++) {
			int next = (index + 1) % outer.size();
			double[] outerA = outer.get(index);
			double[] outerB = outer.get(next);
			double[] innerA = inner.get(index);
			double[] innerB = inner.get(next);
			coloredVertex(buffer, pose, origin, outerA[0], outerA[1], outerArgb);
			coloredVertex(buffer, pose, origin, outerB[0], outerB[1], outerArgb);
			coloredVertex(buffer, pose, origin, innerB[0], innerB[1], innerArgb);
			coloredVertex(buffer, pose, origin, outerA[0], outerA[1], outerArgb);
			coloredVertex(buffer, pose, origin, innerB[0], innerB[1], innerArgb);
			coloredVertex(buffer, pose, origin, innerA[0], innerA[1], innerArgb);
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}

	static void coloredVertex(BufferBuilder buffer, Matrix4f pose, Vec3 origin, double x, double y, int argb) {
		Vec3 point = at(origin, x, y);
		buffer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
			.setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >>> 24);
	}

	private static Vec3 at(Vec3 origin, double x, double y) {
		return origin.add(drawRight.scale(x)).add(drawUp.scale(y));
	}

	static List<double[]> circle(double radius) {
		return PanelMath.circle(radius);
	}

	private static float easeOutCubic(float t) {
		float inverse = 1 - t;
		return 1 - inverse * inverse * inverse;
	}

	private static float easeOutBack(float t) {
		float overshoot = 1.70158F;
		float shifted = t - 1;
		return 1 + (overshoot + 1) * shifted * shifted * shifted + overshoot * shifted * shifted;
	}

	private static double orbitBarWidth() {
		return Math.min(width * 0.3, 0.7);
	}

	// ---------------------------------------------------------------- input

	public static void movePointer(Minecraft client, double mouseX, double mouseY, boolean editing) {
		cursorX = mouseX;
		cursorY = mouseY;
		cursorKnown = true;
		if (dragMode != DragMode.NONE) {
			applyDrag(client, mouseX, mouseY);
			captureAnchor();
			return;
		}

		if (editing || !isReady() || menuOpen) {
			return;
		}
		PanelPoint point = panelPoint(client, mouseX, mouseY, 0, true);
		if (point != null) {
			int[] position = browserPixel(point);
			lastMouseX = position[0];
			lastMouseY = position[1];
			browser.sendMouseMove(lastMouseX, lastMouseY);
		}
	}

	public static void mousePressed(Minecraft client, double mouseX, double mouseY, int button, boolean editing) {
		cursorX = mouseX;
		cursorY = mouseY;
		cursorKnown = true;
		if (!isReady()) {
			return;
		}

		if (!editing && menuOpen) {
			if (menuShownAt != 0) {
				PanelPoint point = panelPoint(client, mouseX, mouseY, 0, true);
				Platform card = point == null ? null : PanelMenu.cardAt(-point.x(), point.y(), height);
				if (card != null) {
					choose(card);
				}
			}
			return;
		}

		if (!editing) {
			PanelPoint content = panelPoint(client, mouseX, mouseY, 0, true);
			if (content != null) {
				int[] position = browserPixel(content);
				lastMouseX = position[0];
				lastMouseY = position[1];
				mouseButtonDown = true;
				browserMouseDown = true;
				browser.sendMousePress(lastMouseX, lastMouseY, button);
				browser.setFocus(true);
			}
			return;
		}

		if (editMode == EditMode.ROTATE) {
			pushUndo();
			dragMode = DragMode.ROTATE;
			mouseButtonDown = true;
			dragStartMouseX = mouseX;
			dragStartMouseY = mouseY;
			rotateYaw = PanelMath.yaw(look);
			rotatePitch = PanelMath.pitch(look);
			rotateRoll = PanelMath.roll(look, right);
			return;
		}

		PanelPoint planePoint = panelCoordinates(client, mouseX, mouseY, false);
		if (planePoint == null) return;

		if (editMode == EditMode.ORBIT) {
			if (isOrbitBar(planePoint.x(), planePoint.y())) {
				pushUndo();
				dragMode = DragMode.ORBIT;
				mouseButtonDown = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				orbitPivot = client.player.position().add(0, client.player.getBbHeight() / 2.0, 0);
				orbitStartOffset = center.subtract(orbitPivot);
				dragStartCenter = center;
			}
			return;
		}

		PanelPoint content = panelPoint(client, mouseX, mouseY, editMode == EditMode.RESIZE ? EDGE_BAND : 0, false);
		if (content == null) return;
		DragMode handle = DragMode.NONE;
		if (editMode == EditMode.RESIZE) {
			handle = dragMode(content.x(), content.y());
		} else if (editMode == EditMode.MOVE_XY) {
			handle = DragMode.MOVE;
		}
		if (handle == DragMode.NONE) return;
		pushUndo();
		mouseButtonDown = true;
		dragMode = handle;
		dragStartCenter = center;
		dragOffset = content.world().subtract(center);
		dragStartWidth = width;
		dragStartHeight = height;
	}

	public static void mouseReleased(Minecraft client, double mouseX, double mouseY, int button) {
		if (browser == null || !mouseButtonDown) {
			return;
		}
		if (browserMouseDown) {
			PanelPoint point = panelPoint(client, mouseX, mouseY, EDGE_BAND, true);
			if (point != null) {
				int[] position = browserPixel(point);
				lastMouseX = position[0];
				lastMouseY = position[1];
			}
			browser.sendMouseRelease(lastMouseX, lastMouseY, button);
		}
		endDrag();
	}

	private static void endDrag() {
		dragMode = DragMode.NONE;
		mouseButtonDown = false;
		browserMouseDown = false;
		orbitPivot = null;
		orbitStartOffset = null;
	}

	public static void mouseScrolled(Minecraft client, double mouseX, double mouseY, double delta, boolean editing) {
		if (!isReady()) return;
		if (editing && editMode == EditMode.ROTATE) {
			pushScrollUndo();
			double roll = Math.toDegrees(PanelMath.roll(look, right));
			double target = Screen.hasShiftDown()
				? PanelMath.snap(roll + Math.signum(delta) * SNAP_DEGREES, SNAP_DEGREES)
				: roll + delta * ROLL_DEGREES_PER_NOTCH;
			setBasis(look, Math.toRadians(target));
			captureAnchor();
			return;
		}
		PanelPoint point = panelPoint(client, mouseX, mouseY, 0, !editing);
		if (point == null) return;
		if (editing && editMode == EditMode.ZOOM) {
			pushScrollUndo();
			width = PanelMath.clamp(width * Math.pow(1.12, delta), MIN_WIDTH, MAX_WIDTH);
			height = width * BROWSER_HEIGHT / BROWSER_WIDTH;
		} else if (!editing && !menuOpen) {
			int[] position = browserPixel(point);
			browser.sendMouseWheel(position[0], position[1], delta, 0);
		}
	}

	/** Scroll edits arrive notch by notch; group a burst of notches into one undo step. */
	private static void pushScrollUndo() {
		long now = System.nanoTime();
		if (now - lastScrollUndoAt > SCROLL_UNDO_MERGE_NANOS) {
			pushUndo();
		}
		lastScrollUndoAt = now;
	}

	private static boolean isOrbitBar(double x, double y) {
		return Math.abs(x) <= orbitBarWidth() / 2 + 0.1
			&& y <= -height / 2 - 0.06
			&& y >= -height / 2 - 0.26;
	}

	/** Shift+R: brings the panel back in front of the player and restores rotation and size. */
	public static void recenter(Minecraft client) {
		if (client.player == null || !isReady()) return;
		pushUndo();
		Vec3[] basis = PanelMath.basis(client.player.getLookAngle(), 0, right);
		look = basis[0];
		right = basis[1];
		up = basis[2];
		center = client.player.getEyePosition().add(look.scale(PLACE_DISTANCE));
		resetSize();
		captureAnchor();
	}

	/** R: restores the placement state (facing the player squarely, initial size) without moving the panel. */
	public static void resetInPlace(Minecraft client) {
		if (client.player == null || !isReady()) return;
		pushUndo();
		setBasis(center.subtract(client.player.getEyePosition()), 0);
		resetSize();
		captureAnchor();
	}

	private static void resetSize() {
		width = INITIAL_WIDTH;
		height = width * BROWSER_HEIGHT / BROWSER_WIDTH;
	}

	private static void setBasis(Vec3 forward, double roll) {
		Vec3[] basis = PanelMath.basis(forward, roll, right);
		look = basis[0];
		right = basis[1];
		up = basis[2];
	}

	private static boolean isLeft(DragMode mode) {
		return mode == DragMode.LEFT || mode == DragMode.TOP_LEFT || mode == DragMode.BOTTOM_LEFT;
	}

	private static boolean isRight(DragMode mode) {
		return mode == DragMode.RIGHT || mode == DragMode.TOP_RIGHT || mode == DragMode.BOTTOM_RIGHT;
	}

	private static boolean isTop(DragMode mode) {
		return mode == DragMode.TOP || mode == DragMode.TOP_LEFT || mode == DragMode.TOP_RIGHT;
	}

	private static boolean isBottom(DragMode mode) {
		return mode == DragMode.BOTTOM || mode == DragMode.BOTTOM_LEFT || mode == DragMode.BOTTOM_RIGHT;
	}

	private static DragMode dragMode(double x, double y) {
		boolean left = Math.abs(x + width / 2) <= EDGE_BAND;
		boolean rightEdge = Math.abs(x - width / 2) <= EDGE_BAND;
		boolean bottom = Math.abs(y + height / 2) <= EDGE_BAND;
		boolean top = Math.abs(y - height / 2) <= EDGE_BAND;
		if (left && top) return DragMode.TOP_LEFT;
		if (rightEdge && top) return DragMode.TOP_RIGHT;
		if (left && bottom) return DragMode.BOTTOM_LEFT;
		if (rightEdge && bottom) return DragMode.BOTTOM_RIGHT;
		if (left) return DragMode.LEFT;
		if (rightEdge) return DragMode.RIGHT;
		if (top) return DragMode.TOP;
		if (bottom) return DragMode.BOTTOM;
		return DragMode.NONE;
	}

	private static void applyDrag(Minecraft client, double mouseX, double mouseY) {
		if (dragMode == DragMode.ROTATE) {
			// Horizontal drag turns around world Y, vertical drag tilts; Shift snaps both to 15 degree steps.
			double yaw = rotateYaw - (mouseX - dragStartMouseX) * ROTATE_DEGREES_PER_PIXEL;
			double pitch = PanelMath.clamp(rotatePitch + (mouseY - dragStartMouseY) * ROTATE_DEGREES_PER_PIXEL, -89.9, 89.9);
			if (Screen.hasShiftDown()) {
				yaw = PanelMath.snap(yaw, SNAP_DEGREES);
				pitch = PanelMath.clamp(PanelMath.snap(pitch, SNAP_DEGREES), -89.9, 89.9);
			}
			setBasis(PanelMath.direction(yaw, pitch), rotateRoll);
			return;
		}
		if (dragMode == DragMode.ORBIT) {
			double radius = orbitStartOffset.length();
			double yaw = Math.atan2(orbitStartOffset.z, orbitStartOffset.x) - (mouseX - dragStartMouseX) * 0.01;
			double pitch = Math.asin(orbitStartOffset.y / radius) + (mouseY - dragStartMouseY) * 0.01;
			pitch = PanelMath.clamp(pitch, -1.35, 1.35);
			Vec3 offset = new Vec3(radius * Math.cos(pitch) * Math.cos(yaw), radius * Math.sin(pitch), radius * Math.cos(pitch) * Math.sin(yaw));
			center = orbitPivot.add(offset);
			Vec3 viewDirection = center.subtract(client.player.getEyePosition()).normalize();
			float viewYaw = (float) PanelMath.yaw(viewDirection);
			float viewPitch = (float) PanelMath.pitch(viewDirection);
			client.player.setYRot(viewYaw);
			client.player.setXRot(viewPitch);
			client.player.setYHeadRot(viewYaw);
			// Must point away from the player like in place(); the opposite sign mirrors the panel and breaks picking.
			setBasis(center.subtract(orbitPivot), PanelMath.roll(look, right));
			return;
		}

		Vec3 point = intersectPanelPlane(client, mouseX, mouseY, false);
		if (point == null) {
			return;
		}
		if (dragMode == DragMode.MOVE) {
			center = point.subtract(dragOffset);
			return;
		}

		double x = point.subtract(dragStartCenter).dot(right);
		double y = point.subtract(dragStartCenter).dot(up);
		boolean left = isLeft(dragMode);
		boolean rightEdge = isRight(dragMode);
		boolean top = isTop(dragMode);
		boolean bottom = isBottom(dragMode);

		if ((left || rightEdge) && (top || bottom)) {
			double anchorX = left ? dragStartWidth / 2 : -dragStartWidth / 2;
			double anchorY = bottom ? dragStartHeight / 2 : -dragStartHeight / 2;
			double moveX = left ? -1 : 1;
			double moveY = bottom ? -1 : 1;
			double requestedWidth = Math.max(Math.abs(anchorX - x), Math.abs(anchorY - y) * BROWSER_WIDTH / BROWSER_HEIGHT);
			width = PanelMath.clamp(requestedWidth, MIN_WIDTH, MAX_WIDTH);
			height = width * BROWSER_HEIGHT / BROWSER_WIDTH;
			center = dragStartCenter
				.add(right.scale(anchorX + moveX * width / 2))
				.add(up.scale(anchorY + moveY * height / 2));
			return;
		}

		if (left || rightEdge) {
			width = PanelMath.clamp(left ? dragStartWidth / 2 - x : x + dragStartWidth / 2, MIN_WIDTH, MAX_WIDTH);
			center = dragStartCenter.add(right.scale(left ? (dragStartWidth - width) / 2 : (width - dragStartWidth) / 2));
		}
		if (top || bottom) {
			height = PanelMath.clamp(bottom ? dragStartHeight / 2 - y : y + dragStartHeight / 2, MIN_WIDTH * BROWSER_HEIGHT / BROWSER_WIDTH, MAX_WIDTH * BROWSER_HEIGHT / BROWSER_WIDTH);
			center = dragStartCenter.add(up.scale(bottom ? (dragStartHeight - height) / 2 : (height - dragStartHeight) / 2));
		}
	}

	public static void keyPressed(int keyCode, int scanCode, int modifiers) {
		if (isReady() && !menuOpen) {
			browser.sendKeyPress(keyCode, scanCode, modifiers);
			browser.setFocus(true);
		}
	}

	public static void keyReleased(int keyCode, int scanCode, int modifiers) {
		if (isReady() && !menuOpen) {
			browser.sendKeyRelease(keyCode, scanCode, modifiers);
		}
	}

	public static void charTyped(char codePoint, int modifiers) {
		if (isReady() && !menuOpen && codePoint != 0) {
			browser.sendKeyTyped(codePoint, modifiers);
		}
	}

	// ---------------------------------------------------------------- picking

	/** Point on the panel within the given margin; {@code frontOnly} for browser input, false for editing. */
	private static PanelPoint panelPoint(Minecraft client, double mouseX, double mouseY, double margin, boolean frontOnly) {
		PanelPoint point = panelCoordinates(client, mouseX, mouseY, frontOnly);
		if (point == null) {
			return null;
		}
		if (Math.abs(point.x()) > width / 2 + margin || Math.abs(point.y()) > height / 2 + margin) {
			return null;
		}
		return point;
	}

	private static PanelPoint panelCoordinates(Minecraft client, double mouseX, double mouseY, boolean frontOnly) {
		Vec3 worldPoint = intersectPanelPlane(client, mouseX, mouseY, frontOnly);
		if (worldPoint == null) return null;
		Vec3 offset = worldPoint.subtract(center);
		return new PanelPoint(worldPoint, offset.dot(right), offset.dot(up));
	}

	private static Vec3 intersectPanelPlane(Minecraft client, double mouseX, double mouseY, boolean frontOnly) {
		if (browser == null || center == null || inverseProjection == null || cameraRotation == null || cameraPosition == null) {
			return null;
		}

		float ndcX = (float) (2.0 * mouseX / client.getWindow().getGuiScaledWidth() - 1.0);
		float ndcY = (float) (1.0 - 2.0 * mouseY / client.getWindow().getGuiScaledHeight());
		Vector4f viewPosition = inverseProjection.transform(new Vector4f(ndcX, ndcY, -1.0F, 1.0F));
		if (Math.abs(viewPosition.w) < 1.0E-6F) {
			return null;
		}
		viewPosition.div(viewPosition.w);
		Vector3f worldDirection = cameraRotation.transform(new Vector3f(viewPosition.x, viewPosition.y, viewPosition.z));
		Vec3 ray = new Vec3(worldDirection.x, worldDirection.y, worldDirection.z).normalize();
		double distance = PanelMath.rayPlaneDistance(cameraPosition, ray, center, look, frontOnly);
		return Double.isNaN(distance) ? null : cameraPosition.add(ray.scale(distance));
	}

	private static int[] browserPixel(PanelPoint point) {
		return PanelMath.browserPixel(point.x(), point.y(), width, height, BROWSER_WIDTH, BROWSER_HEIGHT);
	}

	private static void clearPlacement() {
		center = null;
		look = null;
		right = null;
		up = null;
		dimension = null;
		cameraPosition = null;
		orbitPivot = null;
		orbitStartOffset = null;
		dragStartCenter = null;
		dragOffset = null;
		editMode = EditMode.RESIZE;
		follow = Follow.FIXED;
		anchorOffset = null;
		anchorLook = null;
		anchorRight = null;
		anchorUp = null;
		inverseProjection = null;
		cameraRotation = null;
		closing = false;
		menuOpen = false;
		menuShownAt = 0;
		shared = false;
		UNDO.clear();
		REDO.clear();
		resetSize();
	}
}
