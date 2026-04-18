package com.trevorschoeny.menukit.state;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.core.SlotStateChannel;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-side session cache for per-slot state. Populated by snapshot + update
 * packet receivers; queried by {@link SlotStateChannel#get(Slot)}.
 *
 * <p>Keyed first by {@link Container} (weak so containers can GC with menu
 * close), then by container-relative slot index, then by channel id. Mirrors
 * the {@code ClientLockStateHolder} structure that M1 supersedes — the data
 * shape was already right; M1 generalizes it.
 *
 * <p>Values are the decoded consumer-typed objects (not raw {@link Tag}s) so
 * reads don't pay the decode cost per frame.
 */
public final class SlotStateClientCache {

    // WeakHashMap outer so container refs GC with menu close; synchronized
    // wrapper for defensive thread safety across the client + render threads.
    private static final Map<Container, Map<Integer, Map<Identifier, Object>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SlotStateClientCache() {}

    @SuppressWarnings("unchecked")
    public static <T> T read(SlotStateChannel<T> channel, Slot slot) {
        Container container = slot.container;
        int index = slot.getContainerSlot();
        synchronized (CACHE) {
            Map<Integer, Map<Identifier, Object>> byIndex = CACHE.get(container);
            if (byIndex == null) return channel.defaultValue();
            Map<Identifier, Object> byChannel = byIndex.get(index);
            if (byChannel == null) return channel.defaultValue();
            Object v = byChannel.get(channel.id());
            if (v == null) return channel.defaultValue();
            return (T) v;
        }
    }

    /** Writes the decoded value into the cache. Called by the update-packet receiver. */
    public static <T> void write(SlotStateChannel<T> channel, Container container,
                                  int containerSlotIndex, T value) {
        synchronized (CACHE) {
            CACHE.computeIfAbsent(container, c -> new HashMap<>())
                 .computeIfAbsent(containerSlotIndex, i -> new HashMap<>())
                 .put(channel.id(), value);
        }
    }

    /**
     * Writes a raw Tag value into the cache — used when the channel isn't
     * registered on this client (unknown channel from another mod). Stored
     * under the Tag so a later registration could decode it.
     */
    public static void writeRaw(Identifier channelId, Container container,
                                 int containerSlotIndex, Tag tag) {
        synchronized (CACHE) {
            CACHE.computeIfAbsent(container, c -> new HashMap<>())
                 .computeIfAbsent(containerSlotIndex, i -> new HashMap<>())
                 .put(channelId, tag);
        }
    }

    /** Helper: decode a Tag via the channel's Codec + NbtOps. */
    public static <T> T decode(SlotStateChannel<T> channel, Tag tag) {
        Codec<T> codec = channel.codec();
        return codec.parse(NbtOps.INSTANCE, tag)
                    .getOrThrow(err -> new IllegalStateException(
                            "Codec failed to parse Tag for channel " + channel.id() + ": " + err));
    }

    /**
     * Writes a decoded value under the given channel id, taking {@code Object}
     * to bridge through generic wildcards at the snapshot-receive site.
     */
    public static void writeDecoded(Identifier channelId, Container container,
                                     int containerSlotIndex, Object decoded) {
        synchronized (CACHE) {
            CACHE.computeIfAbsent(container, c -> new HashMap<>())
                 .computeIfAbsent(containerSlotIndex, i -> new HashMap<>())
                 .put(channelId, decoded);
        }
    }

    /** Clears the entire cache. Used on disconnect or for /mkverify cleanup. */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
