package it.holoscreen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UrlInputTest {
	private static final String SEARCH = "https://www.youtube.com/results?search_query=";

	@Test
	void blankInputIsIgnored() {
		assertNull(UrlInput.toUrl("   ", SEARCH));
	}

	@Test
	void httpLinksOpenAsIs() {
		assertEquals("https://youtu.be/abc", UrlInput.toUrl("  https://youtu.be/abc ", SEARCH));
		assertEquals("HTTP://example.com", UrlInput.toUrl("HTTP://example.com", SEARCH));
	}

	@Test
	void bareDomainsGetHttps() {
		assertEquals("https://youtube.com/watch?v=abc", UrlInput.toUrl("youtube.com/watch?v=abc", SEARCH));
	}

	@Test
	void textBecomesYoutubeSearch() {
		assertEquals(SEARCH + "lofi+hip+hop", UrlInput.toUrl("lofi hip hop", SEARCH));
	}

	@Test
	void otherSchemesAreSearchedNotOpened() {
		assertEquals(SEARCH + "file%3A%2F%2F%2FC%3A%2Fsecret.txt", UrlInput.toUrl("file:///C:/secret.txt", SEARCH));
		assertEquals(SEARCH + "javascript%3Aalert%281%29", UrlInput.toUrl("javascript:alert(1)", SEARCH));
	}
}
