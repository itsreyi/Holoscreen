package it.holoscreen.client;

import com.cinemamod.mcef.MCEFBrowser;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;

/**
 * Replaces MCEF's default cursor listener, which calls glfwSetInputMode on Minecraft's window: that un-grabs the
 * mouse during gameplay (breaking the camera) and hides the cursor whenever the page does. Here the browser may
 * only change the cursor shape, and only while the player is actually interacting with the page.
 */
final class BrowserCursor {
	private static final Map<Integer, Long> STANDARD_CURSORS = new HashMap<>();

	private BrowserCursor() {
	}

	static void install(MCEFBrowser browser, BooleanSupplier interacting) {
		browser.setCursorChangeListener(cursorId -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> apply(interacting.getAsBoolean() ? glfwShape(CefCursorType.fromId(cursorId)) : 0));
		});
	}

	/** Restores Minecraft's default arrow; call when leaving a browser screen. */
	static void reset() {
		apply(0);
	}

	private static void apply(int shape) {
		long window = Minecraft.getInstance().getWindow().getWindow();
		long cursor = shape == 0 ? 0 : STANDARD_CURSORS.computeIfAbsent(shape, GLFW::glfwCreateStandardCursor);
		GLFW.glfwSetCursor(window, cursor);
	}

	/** GLFW standard shape for a CEF cursor, or 0 for the default arrow. Hidden (NONE) also maps to the arrow. */
	private static int glfwShape(CefCursorType type) {
		if (type == null) {
			return 0;
		}
		return switch (type) {
			case HAND -> GLFW.GLFW_HAND_CURSOR;
			case IBEAM, VERTICAL_IBEAM -> GLFW.GLFW_IBEAM_CURSOR;
			case CROSS -> GLFW.GLFW_CROSSHAIR_CURSOR;
			case EAST_RESIZE, WEST_RESIZE, EAST_WEST_RESIZE, COLUMN_RESIZE -> GLFW.GLFW_HRESIZE_CURSOR;
			case NORTH_RESIZE, SOUTH_RESIZE, NORTH_SOUTH_RESIZE, ROW_RESIZE -> GLFW.GLFW_VRESIZE_CURSOR;
			default -> 0;
		};
	}
}
