package com.trevorschoeny.menukit.state;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

/**
 * Per-owner slot state bag — holds channel state for all registered channels
 * at a given persistent owner (one player for player-scoped keys, or one
 * player's slice inside a BE/entity's per-player map for shared owners).
 *
 * <p>Storage is a {@link CompoundTag} — NBT-native, legible via
 * {@code /data get} (per THESIS.md principle 6). Structure:
 *
 * <pre>
 * {
 *   "my-mod:sort_lock": {
 *     "5": &lt;Tag encoded via the channel's Codec using NbtOps&gt;,
 *     "12": &lt;Tag ...&gt;
 *   },
 *   "other-mod:annotation": {
 *     "3": &lt;Tag ...&gt;
 *   }
 * }
 * </pre>
 *
 * <p>The bag is mutable — callers get/put directly. Fabric serializes the
 * backing {@link CompoundTag} via the attachment's codec; library-side sync
 * uses a dedicated packet path (no {@code syncWith} auto-sync).
 */
public final class SlotStateBag {

    /** Codec that round-trips the bag to/from a CompoundTag (attachment persistence). */
    public static final Codec<SlotStateBag> CODEC =
            CompoundTag.CODEC.xmap(SlotStateBag::new, SlotStateBag::backing);

    private final CompoundTag backing;

    public SlotStateBag() {
        this(new CompoundTag());
    }

    public SlotStateBag(CompoundTag backing) {
        this.backing = backing;
    }

    /** The underlying CompoundTag. Exposed for codec round-trip only. */
    public CompoundTag backing() {
        return backing;
    }

    /**
     * Reads the stored Tag for the given channel + container-relative slot index.
     * Returns {@code null} if no value is stored. Callers decode via the channel's
     * {@code Codec<T>.parse(NbtOps.INSTANCE, tag)}.
     */
    public @Nullable Tag read(Identifier channelId, int containerSlotIndex) {
        if (!backing.contains(channelId.toString())) return null;
        CompoundTag channelTag = backing.getCompoundOrEmpty(channelId.toString());
        return channelTag.get(String.valueOf(containerSlotIndex));
    }

    /**
     * Writes the given Tag at the channel + slot. Overwrites any existing value.
     */
    public void write(Identifier channelId, int containerSlotIndex, Tag value) {
        String cid = channelId.toString();
        CompoundTag channelTag = backing.getCompoundOrEmpty(cid);
        channelTag.put(String.valueOf(containerSlotIndex), value);
        backing.put(cid, channelTag);
    }

    /** Removes the stored value for the channel + slot. No-op if absent. */
    public void clear(Identifier channelId, int containerSlotIndex) {
        if (!backing.contains(channelId.toString())) return;
        CompoundTag channelTag = backing.getCompoundOrEmpty(channelId.toString());
        channelTag.remove(String.valueOf(containerSlotIndex));
        if (channelTag.isEmpty()) {
            backing.remove(channelId.toString());
        } else {
            backing.put(channelId.toString(), channelTag);
        }
    }

    /** Removes every entry under the given channel id. Used by /mkverify cleanup. */
    public void clearChannel(Identifier channelId) {
        backing.remove(channelId.toString());
    }

    /** True if any channel has any entry. */
    public boolean isEmpty() {
        return backing.isEmpty();
    }
}
