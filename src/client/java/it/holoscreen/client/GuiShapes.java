package it.holoscreen.client;

import net.minecraft.client.gui.GuiGraphics;

final class GuiShapes {
	private GuiShapes() {
	}

	/** Fills a rectangle with circular corners, one pixel column at a time for the rounded sides. */
	static void fillRounded(GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color) {
		int r = Math.max(0, Math.min(radius, Math.min((right - left) / 2, (bottom - top) / 2)));
		graphics.fill(left + r, top, right - r, bottom, color);
		for (int column = 0; column < r; column++) {
			double dx = r - column - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dx * dx));
			graphics.fill(left + column, top + inset, left + column + 1, bottom - inset, color);
			graphics.fill(right - column - 1, top + inset, right - column, bottom - inset, color);
		}
	}

	static boolean contains(double x, double y, int left, int top, int right, int bottom) {
		return x >= left && x < right && y >= top && y < bottom;
	}
}
