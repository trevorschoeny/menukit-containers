package com.trevorschoeny.menukit.network;

import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → Client bulk delivery of per-slot state, sent in two contexts:
 * <ul>
 *   <li><b>Menu-open</b> — fires after {@code ServerPlayer.openMenu} returns
 *       successfully. Bundles every non-default channel value on every
 *       slot in the newly-opened menu.</li>
 *   <li><b>Player-join / respawn</b> — fires for player-scoped channels on
 *       {@code player.inventoryMenu}. Covers the gap where
 *       {@code InventoryMenu} never routes through {@code openMenu}.</li>
 * </ul>
 *
 * <p>{@code menuSlotIndex} is the slot's position in the current menu (client
 * addressing). Entries with unregistered channel IDs are preserved as opaque
 * {@code Tag}s in the client cache — unknown channels don't crash.
 */
public record SlotStateSnapshotS2CPayload(int menuId, List<Entry> entries)
        implements CustomPacketPayload {

    public static final Type<SlotStateSnapshotS2CPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("menukit", "slot_state_snapshot_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SlotStateSnapshotS2CPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SlotStateSnapshotS2CPayload::menuId,
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SlotStateSnapshotS2CPayload::entries,
                    SlotStateSnapshotS2CPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** One (slot, channel, encoded value) tuple in the snapshot. */
    public record Entry(int menuSlotIndex, Identifier channelId, Tag value) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Entry::menuSlotIndex,
                        Identifier.STREAM_CODEC, Entry::channelId,
                        ByteBufCodecs.TAG, Entry::value,
                        Entry::new);
    }
}
