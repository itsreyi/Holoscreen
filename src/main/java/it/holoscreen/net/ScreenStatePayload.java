package it.holoscreen.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: publish/update my public screen. Server → client: someone's public screen changed. */
public record ScreenStatePayload(ScreenState state) implements CustomPacketPayload {
	public static final Type<ScreenStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("holoscreen", "screen_state"));
	public static final StreamCodec<FriendlyByteBuf, ScreenStatePayload> CODEC = CustomPacketPayload.codec(
		(payload, buf) -> payload.state().write(buf),
		buf -> new ScreenStatePayload(ScreenState.read(buf)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
