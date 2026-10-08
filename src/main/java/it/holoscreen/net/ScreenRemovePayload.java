package it.holoscreen.net;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: my screen is no longer public (the owner field is ignored; the sender is used).
 * Server → client: that owner's public screen is gone.
 */
public record ScreenRemovePayload(UUID owner) implements CustomPacketPayload {
	public static final Type<ScreenRemovePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("holoscreen", "screen_remove"));
	public static final StreamCodec<FriendlyByteBuf, ScreenRemovePayload> CODEC = CustomPacketPayload.codec(
		(payload, buf) -> buf.writeUUID(payload.owner()),
		buf -> new ScreenRemovePayload(buf.readUUID()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
