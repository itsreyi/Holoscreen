package it.holoscreen.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ScreenValidationTest {
	private static ScreenState state(Vec3 center, String url, double width) {
		return new ScreenState(UUID.randomUUID(), "Owner", "minecraft:overworld", center,
			new Vec3(0, 0, 2), new Vec3(3, 0, 0), new Vec3(0, 1, 0), width, width * 9 / 16, url, "youtube", true, 12.5);
	}

	@Test
	void acceptsAndNormalizesAValidScreen() {
		ScreenState sanitized = ScreenValidation.sanitize(state(new Vec3(0, 64, 3), "https://youtu.be/x", 2.4), null, new Vec3(0, 64, 0));
		assertNotNull(sanitized);
		assertEquals(1, sanitized.look().length(), 1.0E-9);
		assertEquals(1, sanitized.right().length(), 1.0E-9);
	}

	@Test
	void clampsSize() {
		ScreenState sanitized = ScreenValidation.sanitize(state(Vec3.ZERO, "", 100), null, Vec3.ZERO);
		assertEquals(ScreenValidation.MAX_WIDTH, sanitized.width(), 1.0E-9);
	}

	@Test
	void rejectsNonHttpUrls() {
		assertNull(ScreenValidation.sanitize(state(Vec3.ZERO, "file:///C:/secret", 2), null, Vec3.ZERO));
		assertNull(ScreenValidation.sanitize(state(Vec3.ZERO, "javascript:alert(1)", 2), null, Vec3.ZERO));
		assertTrue(ScreenValidation.isAllowedUrl(""));
		assertFalse(ScreenValidation.isAllowedUrl("https://" + "a".repeat(ScreenState.MAX_URL_LENGTH)));
	}

	@Test
	void rejectsNonFiniteValues() {
		assertNull(ScreenValidation.sanitize(state(new Vec3(Double.NaN, 0, 0), "", 2), null, Vec3.ZERO));
		assertNull(ScreenValidation.sanitize(state(Vec3.ZERO, "", Double.POSITIVE_INFINITY), null, Vec3.ZERO));
	}

	@Test
	void firstPublishMayBeFarFromTheOwner() {
		assertNotNull(ScreenValidation.sanitize(state(new Vec3(500, 64, 0), "", 2), null, Vec3.ZERO));
		assertNotNull(ScreenValidation.sanitize(state(new Vec3(500, 64, 0), "", 2), null, null));
	}

	@Test
	void movesMustStayNearTheOwner() {
		Vec3 previous = new Vec3(0, 64, 3);
		assertNotNull(ScreenValidation.sanitize(state(new Vec3(0, 64, 10), "", 2), previous, new Vec3(0, 64, 0)));
		assertNull(ScreenValidation.sanitize(state(new Vec3(0, 64, 300), "", 2), previous, new Vec3(0, 64, 0)));
		assertNull(ScreenValidation.sanitize(state(new Vec3(0, 64, 10), "", 2), previous, null));
	}

	@Test
	void unmovedScreenMayStayFarAway() {
		Vec3 far = new Vec3(400, 64, 0);
		assertNotNull(ScreenValidation.sanitize(state(far, "", 2), far, new Vec3(0, 64, 0)));
	}
}
