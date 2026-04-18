package com.trevorschoeny.menukit.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server → Client mutation broadcast. Carries the channel id, the
 * container-relative slot index (stable across observers for V2-readiness),
 * and the encoded value. Client scans its current menu for a slot whose
 * {@code getContainerSlot() == containerSlotIndex} and whose container
 * resolves to a matching persistent key.
 *
 * <p>v1 usage: server-initiated writes only (tooling / mixin hooks). Client
 * writes update the local cache optimistically and don't need an echo. V2
 * shared-state will expand this to broadcast across all observers of a
 * container.
 */
public record SlotStateUpdateS2CPayload(Identifier channelId, int containerSlotIndex, Tag encodedValue)
        implements CustomPacketPayload {

    public static final Type<SlotStateUpdateS2CPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("menukit", "slot_state_update_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SlotStateUpdateS2CPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Identifier.STREAM_CODEC, SlotStateUpdateS2CPayload::channelId,
                    ByteBufCodecs.VAR_INT, SlotStateUpdateS2CPayload::containerSlotIndex,
                    ByteBufCodecs.TAG, SlotStateUpdateS2CPayload::encodedValue,
                    SlotStateUpdateS2CPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void sendTo(ServerPlayer player, Identifier channelId,
                               int containerSlotIndex, Tag encodedValue) {
        ServerPlayNetworking.send(player,
                new SlotStateUpdateS2CPayload(channelId, containerSlotIndex, encodedValue));
    }
}
