package it.holoscreen.net;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * A public screen as seen by other players: owner, placement, page and playback. An empty {@code url} means the
 * owner is on the start menu. The server always overwrites {@code owner}/{@code ownerName} with the sender.
 */
public record ScreenState(
	UUID owner,
	String ownerName,
	String dimension,
	Vec3 center,
	Vec3 look,
	Vec3 right,
	Vec3 up,
	double width,
	double height,
	String url,
	String platform,
	boolean playing,
	double mediaTime
) {
	public static final int MAX_URL_LENGTH = 2048;
	private static final int MAX_NAME_LENGTH = 64;
	private static final int MAX_DIMENSION_LENGTH = 256;
	private static final int MAX_PLATFORM_LENGTH = 32;

	public ScreenState withOwner(UUID newOwner, String newOwnerName) {
		return new ScreenState(newOwner, newOwnerName, dimension, center, look, right, up, width, height, url, platform, playing, mediaTime);
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeUUID(owner);
		buf.writeUtf(ownerName, MAX_NAME_LENGTH);
		buf.writeUtf(dimension, MAX_DIMENSION_LENGTH);
		writeVec(buf, center);
		writeVec(buf, look);
		writeVec(buf, right);
		writeVec(buf, up);
		buf.writeDouble(width);
		buf.writeDouble(height);
		buf.writeUtf(url, MAX_URL_LENGTH);
		buf.writeUtf(platform, MAX_PLATFORM_LENGTH);
		buf.writeBoolean(playing);
		buf.writeDouble(mediaTime);
	}

	public static ScreenState read(FriendlyByteBuf buf) {
		return new ScreenState(
			buf.readUUID(),
			buf.readUtf(MAX_NAME_LENGTH),
			buf.readUtf(MAX_DIMENSION_LENGTH),
			readVec(buf),
			readVec(buf),
			readVec(buf),
			readVec(buf),
			buf.readDouble(),
			buf.readDouble(),
			buf.readUtf(MAX_URL_LENGTH),
			buf.readUtf(MAX_PLATFORM_LENGTH),
			buf.readBoolean(),
			buf.readDouble());
	}

	private static void writeVec(FriendlyByteBuf buf, Vec3 vector) {
		buf.writeDouble(vector.x);
		buf.writeDouble(vector.y);
		buf.writeDouble(vector.z);
	}

	private static Vec3 readVec(FriendlyByteBuf buf) {
		return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
	}
}
