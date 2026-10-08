package it.holoscreen.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry used by the panel: basis construction, rotations, follow frames, outlines and picking.
 * No rendering or game state, so it is covered by unit tests.
 *
 * <p>Panel convention: {@code look} points away from the viewer the panel was placed for, {@code right} is
 * {@code (look.z, 0, -look.x)} (the viewer's <em>left</em>), and {@code up = look x right}.
 */
final class PanelMath {
	static final int CORNER_SEGMENTS = 8;
	static final int CIRCLE_SEGMENTS = 20;
	private static final double EPSILON = 1.0E-6;

	private PanelMath() {
	}

	/** Rodrigues rotation of a vector around a unit axis. */
	static Vec3 rotate(Vec3 vector, Vec3 unitAxis, double angle) {
		double cos = Math.cos(angle);
		return vector.scale(cos)
			.add(unitAxis.cross(vector).scale(Math.sin(angle)))
			.add(unitAxis.scale(unitAxis.dot(vector) * (1 - cos)));
	}

	static Vec3 horizontalRight(Vec3 forward, Vec3 fallback) {
		Vec3 horizontal = new Vec3(forward.z, 0, -forward.x);
		return horizontal.lengthSqr() < EPSILON ? fallback : horizontal.normalize();
	}

	/** {look, right, up} for a forward direction and a roll (radians) around it. */
	static Vec3[] basis(Vec3 forward, double roll, Vec3 fallbackRight) {
		Vec3 look = forward.normalize();
		Vec3 base = horizontalRight(look, fallbackRight);
		Vec3 baseUp = look.cross(base).normalize();
		Vec3 right = base.scale(Math.cos(roll)).add(baseUp.scale(Math.sin(roll)));
		return new Vec3[]{look, right, look.cross(right).normalize()};
	}

	/** Roll (radians) of a basis around look, measured from the roll-free basis. */
	static double roll(Vec3 look, Vec3 right) {
		Vec3 base = horizontalRight(look, right);
		Vec3 baseUp = look.cross(base).normalize();
		return Math.atan2(right.dot(baseUp), right.dot(base));
	}

	/** Unit direction for Minecraft-style yaw/pitch in degrees (yaw 0 faces +Z, positive pitch looks down). */
	static Vec3 direction(double yawDegrees, double pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
	}

	static double yaw(Vec3 direction) {
		return Math.toDegrees(Math.atan2(-direction.x, direction.z));
	}

	static double pitch(Vec3 direction) {
		return Math.toDegrees(-Math.asin(clamp(direction.normalize().y, -1, 1)));
	}

	static double snap(double value, double step) {
		return Math.round(value / step) * step;
	}

	/**
	 * Orthonormal {side, up, forward} frame for following a player's head: the side axis comes from yaw alone, so
	 * the frame stays defined when looking straight up or down.
	 */
	static Vec3[] headFrame(double yawDegrees, Vec3 forward) {
		double yaw = Math.toRadians(yawDegrees);
		Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		return new Vec3[]{side, forward.cross(side).normalize(), forward};
	}

	static Vec3[] worldFrame() {
		return new Vec3[]{new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
	}

	static Vec3 toLocal(Vec3 vector, Vec3[] frame) {
		return new Vec3(vector.dot(frame[0]), vector.dot(frame[1]), vector.dot(frame[2]));
	}

	static Vec3 toWorld(Vec3 local, Vec3[] frame) {
		return frame[0].scale(local.x).add(frame[1].scale(local.y)).add(frame[2].scale(local.z));
	}

	/**
	 * Distance along a unit ray to the panel plane, or NaN when there is no hit in front of the ray origin.
	 * With {@code frontOnly}, the plane only counts when seen from its front side (the side opposite to look).
	 */
	static double rayPlaneDistance(Vec3 rayOrigin, Vec3 ray, Vec3 planeCenter, Vec3 look, boolean frontOnly) {
		Vec3 normal = look.scale(-1);
		double denominator = ray.dot(normal);
		if (frontOnly ? denominator >= -EPSILON : Math.abs(denominator) < EPSILON) {
			return Double.NaN;
		}
		double distance = planeCenter.subtract(rayOrigin).dot(normal) / denominator;
		return distance > 0 ? distance : Double.NaN;
	}

	/** Texture coordinates for a panel-local point; local +x is the viewer's left, so it maps to u=0. */
	static double[] textureUv(double x, double y, double width, double height) {
		return new double[]{0.5 - x / width, 0.5 - y / height};
	}

	/** Browser pixel for a panel-local point, clamped to the page. */
	static int[] browserPixel(double x, double y, double width, double height, int browserWidth, int browserHeight) {
		double[] uv = textureUv(x, y, width, height);
		return new int[]{
			(int) (clamp(uv[0], 0, 1) * browserWidth),
			(int) (clamp(uv[1], 0, 1) * browserHeight)
		};
	}

	/** Counter-clockwise outline with 4 * (CORNER_SEGMENTS + 1) points for any radius, so outlines can be banded. */
	static List<double[]> roundedRect(double halfWidth, double halfHeight, double radius) {
		double r = clamp(radius, 0, Math.min(halfWidth, halfHeight));
		double[][] corners = {
			{halfWidth - r, -halfHeight + r, -90},
			{halfWidth - r, halfHeight - r, 0},
			{-halfWidth + r, halfHeight - r, 90},
			{-halfWidth + r, -halfHeight + r, 180}
		};
		List<double[]> points = new ArrayList<>(4 * (CORNER_SEGMENTS + 1));
		for (double[] corner : corners) {
			for (int step = 0; step <= CORNER_SEGMENTS; step++) {
				double angle = Math.toRadians(corner[2] + 90.0 * step / CORNER_SEGMENTS);
				points.add(new double[]{corner[0] + r * Math.cos(angle), corner[1] + r * Math.sin(angle)});
			}
		}
		return points;
	}

	static List<double[]> circle(double radius) {
		List<double[]> points = new ArrayList<>(CIRCLE_SEGMENTS);
		for (int step = 0; step < CIRCLE_SEGMENTS; step++) {
			double angle = 2 * Math.PI * step / CIRCLE_SEGMENTS;
			points.add(new double[]{radius * Math.cos(angle), radius * Math.sin(angle)});
		}
		return points;
	}

	static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
