package com.trevorschoeny.menukit.network;

import com.trevorschoeny.menukit.core.SlotStateChannel;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → Server write request. Carries the channel id, the menu-relative
 * slot index (client addresses by menu position), and the encoded value as
 * a {@link Tag}. Server resolves via
 * {@code player.containerMenu.slots.get(menuSlotIndex)} and extracts the
 * container-relative index for storage.
 */
public record SlotStateUpdateC2SPayload(Identifier channelId, int menuSlotIndex, Tag encodedValue)
        implements CustomPacketPayload {

    public static final Type<SlotStateUpdateC2SPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("menukit", "slot_state_update_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SlotStateUpdateC2SPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Identifier.STREAM_CODEC, SlotStateUpdateC2SPayload::channelId,
                    ByteBufCodecs.VAR_INT, SlotStateUpdateC2SPayload::menuSlotIndex,
                    ByteBufCodecs.TAG, SlotStateUpdateC2SPayload::encodedValue,
                    SlotStateUpdateC2SPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Convenience for the client-side write path — fires the C2S packet. */
    public static <T> void sendFromClient(SlotStateChannel<T> channel, int menuSlotIndex, Tag encoded) {
        ClientPlayNetworking.send(new SlotStateUpdateC2SPayload(channel.id(), menuSlotIndex, encoded));
    }
}
