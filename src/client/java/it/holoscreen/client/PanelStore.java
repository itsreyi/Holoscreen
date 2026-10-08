package it.holoscreen.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import it.holoscreen.HoloscreenMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Persists the panel per world/server in {@code config/holoscreen/panels.json}. */
final class PanelStore {
	/** Serializable panel state; vectors are {x, y, z} arrays. */
	static final class Saved {
		String dimension;
		double[] center;
		double[] look;
		double[] right;
		double[] up;
		double width;
		double height;
		String follow;
		double[] anchorOffset;
		double[] anchorLook;
		double[] anchorRight;
		double[] anchorUp;
		String url;
		String platform;
		boolean shared;
	}

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("holoscreen").resolve("panels.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private PanelStore() {
	}

	/** Key of the world or server the client is connected to, or null when it cannot be determined. */
	static String currentWorldKey(Minecraft client) {
		MinecraftServer server = client.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return root.getFileName() == null ? null : "singleplayer:" + root.getFileName();
		}
		ServerData data = client.getCurrentServer();
		return data == null ? null : "server:" + data.ip;
	}

	static Saved load(String worldKey) {
		return readAll().get(worldKey);
	}

	static void save(String worldKey, Saved saved) {
		Map<String, Saved> all = readAll();
		if (saved == null) {
			all.remove(worldKey);
		} else {
			all.put(worldKey, saved);
		}
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(all, writer);
			}
		} catch (IOException exception) {
			HoloscreenMod.LOGGER.warn("Could not write {}", PATH, exception);
		}
	}

	private static Map<String, Saved> readAll() {
		if (!Files.exists(PATH)) {
			return new HashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(PATH)) {
			Map<String, Saved> all = GSON.fromJson(reader, new TypeToken<Map<String, Saved>>() {
			}.getType());
			return all == null ? new HashMap<>() : new HashMap<>(all);
		} catch (IOException | JsonParseException exception) {
			HoloscreenMod.LOGGER.warn("Could not read {}, ignoring saved panels", PATH, exception);
			return new HashMap<>();
		}
	}
}
