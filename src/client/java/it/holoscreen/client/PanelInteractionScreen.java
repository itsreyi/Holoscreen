package it.holoscreen.client;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public final class PanelInteractionScreen extends Screen {
	private static final int SEGMENT_HEIGHT = 16;
	private static final int SEGMENT_PADDING = 10;
	private static final int GAP = 2;
	private static final int PADDING = 2;
	private static final int GROUP_GAP = 9;
	private static final int TOOLBAR_TOP = 10;
	private static final int CONTAINER_COLOR = 0x80000000;
	/** Smooth white gear (64x64, generated), drawn instead of a font glyph. */
	private static final ResourceLocation GEAR_ICON = ResourceLocation.fromNamespaceAndPath("holoscreen", "textures/gui/settings.png");
	private static final int GEAR_SIZE = 10;
	private static final int GEAR_PADDING = 6;
	private final boolean editMode;
	private WorldVideoPanel.EditMode selectedMode = WorldVideoPanel.EditMode.RESIZE;

	public PanelInteractionScreen(boolean editMode) {
		super(Component.translatable(editMode
			? "screen.holoscreen.panel_edit"
			: "screen.holoscreen.panel_interaction"));
		this.editMode = editMode;
		WorldVideoPanel.setEditMode(selectedMode);
	}

	@Override
	protected void init() {
		// The gear is drawn downscaled; bilinear filtering keeps it smooth.
		minecraft.getTextureManager().getTexture(GEAR_ICON).setFilter(true, false);
	}

	public boolean isEditMode() {
		return editMode;
	}

	public boolean isResizeMode() {
		return editMode && selectedMode == WorldVideoPanel.EditMode.RESIZE;
	}

	public boolean isOrbitMode() {
		return editMode && selectedMode == WorldVideoPanel.EditMode.ORBIT;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		BrowserCursor.reset();
		// Every edit happens inside this screen, so leaving it is the moment to persist the placement.
		WorldVideoPanel.persist();
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	private record Segment(int left, int right, Component label) {
	}

	/**
	 * Mode segments, then (after a divider) the follow toggle and the settings gear,
	 * laid out centered with widths fitted to their labels.
	 */
	private List<Segment> segments() {
		WorldVideoPanel.EditMode[] modes = WorldVideoPanel.EditMode.values();
		List<Component> labels = new ArrayList<>();
		for (WorldVideoPanel.EditMode mode : modes) {
			labels.add(modeLabel(mode));
		}
		labels.add(Component.translatable("screen.holoscreen.follow."
			+ WorldVideoPanel.getFollow().name().toLowerCase(Locale.ROOT)));
		labels.add(Component.translatable(WorldVideoPanel.isShared() ? "screen.holoscreen.shared" : "screen.holoscreen.private"));
		labels.add(Component.empty());
		int gearIndex = labels.size() - 1;
		int total = PADDING * 2 + GAP * (labels.size() - 2) + GROUP_GAP;
		for (int index = 0; index < labels.size(); index++) {
			total += segmentWidth(index == gearIndex, labels.get(index));
		}
		int left = (width - total) / 2 + PADDING;
		List<Segment> segments = new ArrayList<>(labels.size());
		for (int index = 0; index < labels.size(); index++) {
			Component label = labels.get(index);
			int right = left + segmentWidth(index == gearIndex, label);
			segments.add(new Segment(left, right, label));
			left = right + (index == modes.length - 1 ? GROUP_GAP : GAP);
		}
		return segments;
	}

	private int segmentWidth(boolean gear, Component label) {
		return gear ? GEAR_SIZE + GEAR_PADDING * 2 : font.width(label) + SEGMENT_PADDING * 2;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (!editMode) {
			drawHint(graphics, Component.translatable(WorldVideoPanel.isMenuOpen()
				? "screen.holoscreen.menu_hint"
				: "screen.holoscreen.panel_interaction_hint"), TOOLBAR_TOP);
			return;
		}

		List<Segment> segments = segments();
		int top = TOOLBAR_TOP + PADDING;
		int containerHeight = SEGMENT_HEIGHT + PADDING * 2;
		GuiShapes.fillRounded(graphics, segments.get(0).left() - PADDING, TOOLBAR_TOP,
			segments.get(segments.size() - 1).right() + PADDING, TOOLBAR_TOP + containerHeight, containerHeight / 2, CONTAINER_COLOR);
		WorldVideoPanel.EditMode[] modes = WorldVideoPanel.EditMode.values();
		for (int index = 0; index < segments.size(); index++) {
			Segment segment = segments.get(index);
			boolean selected = index < modes.length
				? modes[index] == selectedMode
				: index == modes.length && WorldVideoPanel.getFollow() != WorldVideoPanel.Follow.FIXED
					|| index == modes.length + 1 && WorldVideoPanel.isShared();
			boolean hovered = GuiShapes.contains(mouseX, mouseY, segment.left(), top, segment.right(), top + SEGMENT_HEIGHT);
			if (selected) {
				GuiShapes.fillRounded(graphics, segment.left(), top, segment.right(), top + SEGMENT_HEIGHT, SEGMENT_HEIGHT / 2, 0xF2FFFFFF);
			} else if (hovered) {
				GuiShapes.fillRounded(graphics, segment.left(), top, segment.right(), top + SEGMENT_HEIGHT, SEGMENT_HEIGHT / 2, 0x26FFFFFF);
			}
			int color = selected ? 0xFF101010 : hovered ? 0xFFFFFFFF : 0xB3FFFFFF;
			if (index == segments.size() - 1) {
				RenderSystem.enableBlend();
				RenderSystem.defaultBlendFunc();
				RenderSystem.setShaderColor(1, 1, 1, (color >>> 24) / 255F);
				graphics.blit(GEAR_ICON, segment.left() + GEAR_PADDING, top + (SEGMENT_HEIGHT - GEAR_SIZE) / 2,
					GEAR_SIZE, GEAR_SIZE, 0, 0, 64, 64, 64, 64);
				RenderSystem.setShaderColor(1, 1, 1, 1);
			} else {
				graphics.drawString(font, segment.label(), segment.left() + SEGMENT_PADDING, top + (SEGMENT_HEIGHT - 8) / 2, color, false);
			}
		}
		int dividerX = segments.get(modes.length).left() - (GROUP_GAP + 1) / 2;
		graphics.fill(dividerX, top + 3, dividerX + 1, top + SEGMENT_HEIGHT - 3, 0x40FFFFFF);

		Component hint = Component.translatable("screen.holoscreen.mode." + selectedMode.name().toLowerCase(Locale.ROOT) + ".hint")
			.append("  ·  ")
			.append(Component.translatable("screen.holoscreen.panel_edit_hint",
				PanelSettings.get().visibility.label(), PanelSettings.get().audio.label()));
		drawHint(graphics, hint, TOOLBAR_TOP + containerHeight + 6);
	}

	private void drawHint(GuiGraphics graphics, Component text, int top) {
		graphics.drawString(font, text, (width - font.width(text)) / 2, top, 0xB3FFFFFF, true);
	}

	private Component modeLabel(WorldVideoPanel.EditMode mode) {
		return Component.translatable("screen.holoscreen.mode." + mode.name().toLowerCase(Locale.ROOT));
	}

	/** Public/private switch; public needs Holoscreen on the server. */
	private void toggleShared() {
		boolean makePublic = !WorldVideoPanel.isShared();
		if (makePublic && !ScreenSync.serverSupported()) {
			if (minecraft.player != null) {
				minecraft.player.displayClientMessage(Component.translatable("message.holoscreen.server_unsupported"), true);
			}
			return;
		}
		PanelSounds.click();
		WorldVideoPanel.setShared(makePublic);
		if (minecraft.player != null) {
			minecraft.player.displayClientMessage(Component.translatable(makePublic
				? "message.holoscreen.now_shared"
				: "message.holoscreen.now_private"), true);
		}
	}

	private void selectMode(WorldVideoPanel.EditMode mode) {
		if (mode != selectedMode) {
			PanelSounds.click();
		}
		selectedMode = mode;
		WorldVideoPanel.setEditMode(mode);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		WorldVideoPanel.movePointer(Minecraft.getInstance(), mouseX, mouseY, editMode);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (editMode && clickToolbar(mouseX, mouseY)) {
			return true;
		}
		WorldVideoPanel.mousePressed(Minecraft.getInstance(), mouseX, mouseY, button, editMode);
		return true;
	}

	private boolean clickToolbar(double mouseX, double mouseY) {
		int top = TOOLBAR_TOP + PADDING;
		WorldVideoPanel.EditMode[] modes = WorldVideoPanel.EditMode.values();
		List<Segment> segments = segments();
		for (int index = 0; index < segments.size(); index++) {
			Segment segment = segments.get(index);
			if (!GuiShapes.contains(mouseX, mouseY, segment.left(), top, segment.right(), top + SEGMENT_HEIGHT)) {
				continue;
			}
			if (index < modes.length) {
				selectMode(modes[index]);
			} else if (index == modes.length) {
				PanelSounds.click();
				WorldVideoPanel.cycleFollow();
			} else if (index == modes.length + 1) {
				toggleShared();
			} else {
				minecraft.setScreen(new PanelSettingsScreen(this));
			}
			return true;
		}
		return GuiShapes.contains(mouseX, mouseY, segments.get(0).left() - PADDING, TOOLBAR_TOP,
			segments.get(segments.size() - 1).right() + PADDING, TOOLBAR_TOP + SEGMENT_HEIGHT + PADDING * 2);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		WorldVideoPanel.mouseReleased(Minecraft.getInstance(), mouseX, mouseY, button);
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		WorldVideoPanel.mouseScrolled(Minecraft.getInstance(), mouseX, mouseY, scrollY, editMode);
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (!editMode && WorldVideoPanel.isMenuOpen()) {
			int index = keyCode - GLFW.GLFW_KEY_1;
			if (index >= 0 && index < Platform.values().length) {
				WorldVideoPanel.choose(Platform.values()[index]);
			}
			return true;
		}
		if (!editMode && keyCode == GLFW.GLFW_KEY_L && hasControlDown()) {
			minecraft.setScreen(new PanelUrlScreen(this));
			return true;
		}
		if (editMode && hasControlDown()) {
			boolean redo = keyCode == GLFW.GLFW_KEY_Y || keyCode == GLFW.GLFW_KEY_Z && hasShiftDown();
			if (redo || keyCode == GLFW.GLFW_KEY_Z) {
				if (redo ? WorldVideoPanel.redo() : WorldVideoPanel.undo()) {
					PanelSounds.click();
				}
				return true;
			}
		}
		if (editMode) {
			WorldVideoPanel.EditMode[] modes = WorldVideoPanel.EditMode.values();
			int modeIndex = keyCode - GLFW.GLFW_KEY_1;
			if (modeIndex >= 0 && modeIndex < modes.length) {
				selectMode(modes[modeIndex]);
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_V) {
				PanelSettings.cycleVisibility();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_A) {
				PanelSettings.cycleAudio();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_F) {
				PanelSounds.click();
				WorldVideoPanel.cycleFollow();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_P) {
				toggleShared();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_U) {
				minecraft.setScreen(new PanelUrlScreen(this));
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_M) {
				WorldVideoPanel.openMenu();
				minecraft.setScreen(new PanelInteractionScreen(false));
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_R) {
				// R resets rotation and size where the panel is; Shift+R also brings it back in front of the player.
				if (hasShiftDown()) {
					WorldVideoPanel.recenter(Minecraft.getInstance());
				} else {
					WorldVideoPanel.resetInPlace(Minecraft.getInstance());
				}
				return true;
			}
		}
		// Only in edit mode: in interaction mode X must reach the page (e.g. typing in the search box).
		if (editMode && keyCode == GLFW.GLFW_KEY_X && WorldVideoPanel.remove()) {
			Minecraft client = Minecraft.getInstance();
			if (client.player != null) {
				client.player.displayClientMessage(Component.translatable("message.holoscreen.panel_removed"), false);
			}
			client.setScreen(null);
			return true;
		}
		if (!editMode) {
			WorldVideoPanel.keyPressed(keyCode, scanCode, modifiers);
		}
		return true;
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		if (!editMode) {
			WorldVideoPanel.keyReleased(keyCode, scanCode, modifiers);
		}
		return true;
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (!editMode) {
			WorldVideoPanel.charTyped(codePoint, modifiers);
		}
		return true;
	}
}
