package it.holoscreen.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Turns address-bar input into a URL the panel may load. */
final class UrlInput {
	private UrlInput() {
	}

	/**
	 * http(s) links open as-is, bare domains get https://, and anything else (including other schemes such as
	 * file: or javascript:) becomes a search with the given URL prefix. Returns null for blank input.
	 */
	static String toUrl(String raw, String searchPrefix) {
		String text = raw.trim();
		if (text.isEmpty()) {
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.startsWith("https://") || lower.startsWith("http://")) {
			return text;
		}
		if (!text.contains(" ") && !lower.contains(":") && text.contains(".")) {
			return "https://" + text;
		}
		return searchPrefix + URLEncoder.encode(text, StandardCharsets.UTF_8);
	}
}
