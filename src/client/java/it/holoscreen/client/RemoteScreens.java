package it.holoscreen.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import it.holoscreen.HoloscreenMod;
import it.holoscreen.net.ScreenState;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Other players' public screens, received from the server. They are watch-only: each gets its own browser while
 * the player is close enough, follows the owner's placement smoothly and mirrors the owner's playback.
 */
final class RemoteScreens {
	private static final double LOAD_DISTANCE = 64;
	/** Larger than LOAD_DISTANCE so walking along the edge does not recreate the browser over and over. */
	private static final double UNLOAD_DISTANCE = 80;
	private static final double SNAP_DISTANCE = 16;
	private static final int AUDIO_UPDATE_TICKS = 2;
	private static final int SYNC_TICKS = 20;
	private static final int BROWSER_WIDTH = 1280;
	private static final int BROWSER_HEIGHT = 720;
	private static final int IDLE_BACKGROUND = 0x0B0B0D;

	private static final class Remote {
		ScreenState state;
		long receivedAt;
		Vec3 center;
		Vec3 look;
		Vec3 right;
		Vec3 up;
		double width;
		double height;
		long lastFrameAt;
		MCEFBrowser browser;
		boolean browserFailed;
		/** URL the browser was pointed at; null for the owner's start menu (shown as an idle Holoscreen face). */
		String loadedUrl;
		HoloscreenIntro intro;
		long openedAt;
		long closingAt;
		boolean closing;
		final PanelAudio.State audio = new PanelAudio.State();
	}

	/** Front of an owner's screen that is on the start menu: dark face with the Holoscreen badge. */
	private static final WorldVideoPanel.FrontLayer IDLE_FACE = (pose, origin, towardCamera, up, halfHeight, outline, opacity) -> {
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		WorldVideoPanel.fillPolygon(pose, origin, 0, 0, outline, HoloscreenIntro.argbOf(opacity, IDLE_BACKGROUND));
		HoloscreenIntro.renderBadge(pose, origin, towardCamera, up, halfHeight, opacity);
	};

	private static final Map<UUID, Remote> SCREENS = new HashMap<>();
	private static int ticks;

	private RemoteScreens() {
	}

	static boolean hasAny() {
		return !SCREENS.isEmpty();
	}

	static void update(ScreenState state) {
		Player player = Minecraft.getInstance().player;
		if (player != null && state.owner().equals(player.getUUID())) {
			return;
		}
		Remote remote = SCREENS.get(state.owner());
		if (remote == null || remote.closing) {
			if (remote != null) {
				release(remote);
			}
			remote = new Remote();
			remote.center = state.center();
			remote.look = state.look();
			remote.right = state.right();
			remote.up = state.up();
			remote.width = state.width();
			remote.height = state.height();
			SCREENS.put(state.owner(), remote);
		}
		remote.state = state;
		remote.receivedAt = System.nanoTime();
	}

	static void remove(UUID owner) {
		Remote remote = SCREENS.get(owner);
		if (remote == null) {
			return;
		}
		if (remote.browser == null || PanelSettings.get().animationMillis <= 0) {
			release(remote);
			SCREENS.remove(owner);
		} else {
			remote.closing = true;
			remote.closingAt = System.nanoTime();
		}
	}

	static void clear() {
		SCREENS.values().forEach(RemoteScreens::release);
		SCREENS.clear();
	}

	static void tick(Minecraft client) {
		Player player = client.player;
		if (player == null || SCREENS.isEmpty()) {
			return;
		}
		ticks++;
		String dimension = player.level().dimension().location().toString();
		Vec3 eyes = player.getEyePosition();
		Iterator<Remote> iterator = SCREENS.values().iterator();
		while (iterator.hasNext()) {
			Remote remote = iterator.next();
			if (remote.closing && openness(remote) <= 0) {
				release(remote);
				iterator.remove();
				continue;
			}
			boolean here = remote.state.dimension().equals(dimension);
			double distance = here ? eyes.distanceTo(remote.center) : Double.MAX_VALUE;
			boolean wanted = !remote.closing && here && distance < (remote.browser == null ? LOAD_DISTANCE : UNLOAD_DISTANCE);
			if (wanted && remote.browser == null && !remote.browserFailed) {
				create(remote);
			} else if (!wanted && remote.browser != null && !remote.closing) {
				release(remote);
			}
			if (remote.browser != null) {
				tickBrowser(remote);
			}
		}
	}

	private static void tickBrowser(Remote remote) {
		String target = remote.state.url().isEmpty() ? null : remote.state.url();
		if (!Objects.equals(target, remote.loadedUrl)) {
			remote.browser.loadURL(target == null ? "about:blank" : target);
			remote.loadedUrl = target;
			remote.intro = target == null ? null : new HoloscreenIntro(Platform.parse(remote.state.platform(), Platform.BROWSER));
		}
		if (remote.intro != null) {
			remote.intro.update(remote.browser);
			if (remote.intro.isDone()) {
				remote.intro = null;
			}
		}
		if (ticks % AUDIO_UPDATE_TICKS == 0) {
			PanelAudio.update(remote.browser, remote.center, openness(remote), remote.audio, true);
		}
		if (ticks % SYNC_TICKS == 0 && target != null) {
			PlaybackSync.apply(remote.browser, remote.state.mediaTime(), remote.state.playing(),
				(System.nanoTime() - remote.receivedAt) / 1.0E9);
		}
	}

	private static void create(Remote remote) {
		String target = remote.state.url().isEmpty() ? null : remote.state.url();
		try {
			remote.browser = MCEF.createBrowser(target == null ? "about:blank" : target, false);
		} catch (RuntimeException | LinkageError exception) {
			HoloscreenMod.LOGGER.error("Could not start the browser for {}'s screen", remote.state.ownerName(), exception);
			remote.browser = null;
		}
		if (remote.browser == null) {
			remote.browserFailed = true;
			return;
		}
		remote.browser.resize(BROWSER_WIDTH, BROWSER_HEIGHT);
		// Viewers never interact with someone else's page, so it must never change the game's cursor.
		BrowserCursor.install(remote.browser, () -> false);
		remote.loadedUrl = target;
		remote.intro = new HoloscreenIntro(target == null ? null : Platform.parse(remote.state.platform(), Platform.BROWSER));
		remote.openedAt = System.nanoTime();
	}

	private static void release(Remote remote) {
		if (remote.browser != null) {
			remote.browser.close();
			remote.browser = null;
		}
		remote.intro = null;
		remote.loadedUrl = null;
	}

	private static float openness(Remote remote) {
		int duration = PanelSettings.get().animationMillis;
		if (duration <= 0) {
			return remote.closing ? 0 : 1;
		}
		float elapsed = (System.nanoTime() - (remote.closing ? remote.closingAt : remote.openedAt)) / 1.0E6F / duration;
		float progress = Math.max(0, Math.min(1, elapsed));
		return remote.closing ? 1 - progress : progress;
	}

	static void render(WorldRenderContext context, Matrix4f pose, Vec3 camera, PanelSettings settings) {
		Player player = Minecraft.getInstance().player;
		if (player == null || SCREENS.isEmpty()) {
			return;
		}
		String dimension = player.level().dimension().location().toString();
		long now = System.nanoTime();
		for (Remote remote : SCREENS.values()) {
			if (remote.browser == null || !remote.state.dimension().equals(dimension)) {
				continue;
			}
			smooth(remote, now);
			if (!WorldVideoPanel.isVisible(context, remote.center, remote.width, remote.height)) {
				continue;
			}
			boolean idle = remote.loadedUrl == null;
			boolean covered = remote.intro != null && remote.intro.coversPage();
			WorldVideoPanel.drawSurface(pose, camera, new WorldVideoPanel.Surface(remote.center, remote.look, remote.right,
				remote.up, remote.width, remote.height, remote.browser, remote.intro, openness(remote),
				!idle && !covered, idle ? IDLE_FACE : null), settings);
		}
	}

	/** Eases the drawn placement toward the latest received one (updates arrive at most ~10 times a second). */
	private static void smooth(Remote remote, long now) {
		ScreenState target = remote.state;
		double seconds = remote.lastFrameAt == 0 ? 1 : Math.min(1, (now - remote.lastFrameAt) / 1.0E9);
		remote.lastFrameAt = now;
		if (remote.center.distanceTo(target.center()) > SNAP_DISTANCE) {
			remote.center = target.center();
			remote.look = target.look();
			remote.right = target.right();
			remote.up = target.up();
			remote.width = target.width();
			remote.height = target.height();
			return;
		}
		double blend = 1 - Math.exp(-seconds * 14);
		remote.center = remote.center.lerp(target.center(), blend);
		remote.width += (target.width() - remote.width) * blend;
		remote.height += (target.height() - remote.height) * blend;
		Vec3 look = remote.look.lerp(target.look(), blend);
		Vec3 right = remote.right.lerp(target.right(), blend);
		if (look.lengthSqr() < 1.0E-8 || right.lengthSqr() < 1.0E-8) {
			look = target.look();
			right = target.right();
		}
		// Re-orthonormalize: look first, then right without its look component, then up = look x right.
		look = look.normalize();
		right = right.subtract(look.scale(right.dot(look)));
		right = right.lengthSqr() < 1.0E-8 ? target.right() : right.normalize();
		remote.look = look;
		remote.right = right;
		remote.up = look.cross(right).normalize();
	}
}
