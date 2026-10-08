package it.holoscreen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PanelMathTest {
	private static final double EPSILON = 1.0E-9;

	private static void assertVec(Vec3 expected, Vec3 actual) {
		assertEquals(expected.x, actual.x, 1.0E-7, "x of " + actual);
		assertEquals(expected.y, actual.y, 1.0E-7, "y of " + actual);
		assertEquals(expected.z, actual.z, 1.0E-7, "z of " + actual);
	}

	private static void assertOrthonormal(Vec3[] frame) {
		for (int i = 0; i < 3; i++) {
			assertEquals(1, frame[i].length(), 1.0E-7);
			for (int j = i + 1; j < 3; j++) {
				assertEquals(0, frame[i].dot(frame[j]), 1.0E-7);
			}
		}
	}

	@Test
	void rotateFollowsRightHandRule() {
		assertVec(new Vec3(0, 0, -1), PanelMath.rotate(new Vec3(1, 0, 0), new Vec3(0, 1, 0), Math.PI / 2));
		assertVec(new Vec3(1, 2, 3), PanelMath.rotate(new Vec3(1, 2, 3), new Vec3(0, 0, 1), 2 * Math.PI));
	}

	@Test
	void rollFreeBasisMatchesPlacementConvention() {
		Vec3[] basis = PanelMath.basis(new Vec3(0, 0, 1), 0, new Vec3(1, 0, 0));
		assertVec(new Vec3(0, 0, 1), basis[0]);
		assertVec(new Vec3(1, 0, 0), basis[1]);
		assertVec(new Vec3(0, 1, 0), basis[2]);
	}

	@Test
	void basisIsOrthonormalAndRightHandedForAnyDirectionAndRoll() {
		for (double yaw = -180; yaw < 180; yaw += 37) {
			for (double pitch = -85; pitch <= 85; pitch += 34) {
				for (double roll = -3; roll <= 3; roll += 1.5) {
					Vec3[] basis = PanelMath.basis(PanelMath.direction(yaw, pitch), roll, new Vec3(1, 0, 0));
					assertOrthonormal(basis);
					assertVec(basis[0].cross(basis[1]), basis[2]);
				}
			}
		}
	}

	@Test
	void basisFallsBackWhenLookingStraightUp() {
		Vec3[] basis = PanelMath.basis(new Vec3(0, 1, 0), 0, new Vec3(1, 0, 0));
		assertOrthonormal(basis);
		assertVec(new Vec3(1, 0, 0), basis[1]);
	}

	@Test
	void rollRoundTrips() {
		for (double roll = -3; roll <= 3; roll += 0.25) {
			Vec3[] basis = PanelMath.basis(PanelMath.direction(40, -20), roll, new Vec3(1, 0, 0));
			assertEquals(roll, PanelMath.roll(basis[0], basis[1]), 1.0E-7);
		}
	}

	@Test
	void directionMatchesMinecraftConvention() {
		assertVec(new Vec3(0, 0, 1), PanelMath.direction(0, 0));
		assertVec(new Vec3(-1, 0, 0), PanelMath.direction(90, 0));
		assertVec(new Vec3(0, -1, 0), PanelMath.direction(0, 90));
	}

	@Test
	void yawAndPitchRoundTrip() {
		for (double yaw = -170; yaw < 180; yaw += 25) {
			for (double pitch = -80; pitch <= 80; pitch += 20) {
				Vec3 direction = PanelMath.direction(yaw, pitch);
				assertEquals(yaw, PanelMath.yaw(direction), 1.0E-7);
				assertEquals(pitch, PanelMath.pitch(direction), 1.0E-7);
			}
		}
	}

	@Test
	void snapRoundsToNearestStep() {
		assertEquals(15, PanelMath.snap(8, 15), EPSILON);
		assertEquals(0, PanelMath.snap(7, 15), EPSILON);
		assertEquals(-30, PanelMath.snap(-31, 15), EPSILON);
	}

	@Test
	void headFrameIsOrthonormalEvenLookingStraightUpOrDown() {
		for (double yaw = -180; yaw < 180; yaw += 45) {
			for (double pitch : new double[]{-90, -45, 0, 45, 90}) {
				assertOrthonormal(PanelMath.headFrame(yaw, PanelMath.direction(yaw, pitch)));
			}
		}
	}

	@Test
	void localAndWorldConversionsAreInverse() {
		Vec3[] frame = PanelMath.headFrame(33, PanelMath.direction(33, -12));
		Vec3 vector = new Vec3(1.5, -2, 0.25);
		assertVec(vector, PanelMath.toWorld(PanelMath.toLocal(vector, frame), frame));
		assertVec(vector, PanelMath.toLocal(vector, PanelMath.worldFrame()));
	}

	@Test
	void rayHitsFrontOfPanel() {
		Vec3 look = new Vec3(0, 0, 1);
		double distance = PanelMath.rayPlaneDistance(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 3), look, true);
		assertEquals(3, distance, EPSILON);
	}

	@Test
	void backOfPanelOnlyHitsWhenAllowed() {
		Vec3 look = new Vec3(0, 0, 1);
		Vec3 behind = new Vec3(0, 0, 6);
		Vec3 towardPanel = new Vec3(0, 0, -1);
		assertTrue(Double.isNaN(PanelMath.rayPlaneDistance(behind, towardPanel, new Vec3(0, 0, 3), look, true)));
		assertEquals(3, PanelMath.rayPlaneDistance(behind, towardPanel, new Vec3(0, 0, 3), look, false), EPSILON);
	}

	@Test
	void rayMissesWhenParallelOrPointingAway() {
		Vec3 look = new Vec3(0, 0, 1);
		Vec3 panel = new Vec3(0, 0, 3);
		assertTrue(Double.isNaN(PanelMath.rayPlaneDistance(Vec3.ZERO, new Vec3(1, 0, 0), panel, look, false)));
		assertTrue(Double.isNaN(PanelMath.rayPlaneDistance(Vec3.ZERO, new Vec3(0, 0, -1), panel, look, false)));
	}

	@Test
	void textureMappingMirrorsLocalX() {
		double[] corner = PanelMath.textureUv(-1, -0.5, 2, 1);
		assertEquals(1, corner[0], EPSILON);
		assertEquals(1, corner[1], EPSILON);
		double[] middle = PanelMath.textureUv(0, 0, 2, 1);
		assertEquals(0.5, middle[0], EPSILON);
		assertEquals(0.5, middle[1], EPSILON);
	}

	@Test
	void browserPixelIsClampedToPage() {
		int[] inside = PanelMath.browserPixel(0, 0, 2, 1, 1280, 720);
		assertEquals(640, inside[0]);
		assertEquals(360, inside[1]);
		int[] outside = PanelMath.browserPixel(-5, 5, 2, 1, 1280, 720);
		assertEquals(1280, outside[0]);
		assertEquals(0, outside[1]);
	}

	@Test
	void roundedRectHasStablePointCountAndStaysInBounds() {
		int expected = 4 * (PanelMath.CORNER_SEGMENTS + 1);
		for (double radius : new double[]{0, 0.1, 0.5, 10}) {
			List<double[]> outline = PanelMath.roundedRect(1, 0.5, radius);
			assertEquals(expected, outline.size());
			for (double[] point : outline) {
				assertTrue(Math.abs(point[0]) <= 1 + EPSILON && Math.abs(point[1]) <= 0.5 + EPSILON);
			}
		}
	}

	@Test
	void sharpRoundedRectHitsTheCorners() {
		List<double[]> outline = PanelMath.roundedRect(1, 0.5, 0);
		assertEquals(1, outline.get(0)[0], EPSILON);
		assertEquals(-0.5, outline.get(0)[1], EPSILON);
	}
}
