package it.holoscreen.client;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Sites offered by the launcher; each has a home page, a search URL prefix and an icon texture. */
public enum Platform {
	BROWSER("https://www.google.com", "https://www.google.com/search?q="),
	YOUTUBE("https://www.youtube.com", "https://www.youtube.com/results?search_query="),
	TWITCH("https://www.twitch.tv", "https://www.twitch.tv/search?term="),
	KICK("https://kick.com", "https://kick.com/search?query=");

	private final String homeUrl;
	private final String searchPrefix;
	private final ResourceLocation icon;
	private final ResourceLocation labelTexture;

	Platform(String homeUrl, String searchPrefix) {
		this.homeUrl = homeUrl;
		this.searchPrefix = searchPrefix;
		this.icon = ResourceLocation.fromNamespaceAndPath("holoscreen", "textures/misc/platform_" + id() + ".png");
		this.labelTexture = ResourceLocation.fromNamespaceAndPath("holoscreen", "textures/misc/label_" + id() + ".png");
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String homeUrl() {
		return homeUrl;
	}

	public String searchPrefix() {
		return searchPrefix;
	}

	public ResourceLocation icon() {
		return icon;
	}

	/** Pre-rendered name (Inter, 320x80) used on the in-panel menu. */
	public ResourceLocation labelTexture() {
		return labelTexture;
	}

	public Component label() {
		return Component.translatable("platform.holoscreen." + id());
	}

	static Platform parse(String id, Platform fallback) {
		for (Platform platform : values()) {
			if (platform.id().equals(id)) {
				return platform;
			}
		}
		return fallback;
	}
}
