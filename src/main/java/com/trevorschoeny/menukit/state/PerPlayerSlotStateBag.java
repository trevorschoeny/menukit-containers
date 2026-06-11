package com.trevorschoeny.menukit.state;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-player-per-owner state bag — wraps a {@link CompoundTag} whose keys are
 * player UUID strings (and, for §0049 SHARED channels, the reserved
 * {@link #SHARED_KEY}) and whose values are {@link SlotStateBag}s. Attached to
 * block entities and entities so each viewing player has their own private set
 * of marks on the shared container — except shared channels, which all viewers
 * resolve to one bag.
 *
 * <p>Shape chosen for V2 shared-state migration (see M1 design doc §4.2
 * storage-shape tradeoff): dropping the UUID layer promotes shared channels
 * without a data rewrite. §0049 realizes that — {@link #getOrCreateShared()}.
 */
@ApiStatus.Internal
public final class PerPlayerSlotStateBag {

    /**
     * Reserved key for the player-agnostic shared bag (§0049). Not a valid UUID
     * string, so it can never collide with a per-player entry, and it lives in
     * the same backing tag — the §0048 travel bridge carries it with the rest.
     */
    private static final String SHARED_KEY = "shared";

    /** Codec that round-trips the bag to/from a CompoundTag. */
    public static final Codec<PerPlayerSlotStateBag> CODEC =
            CompoundTag.CODEC.xmap(PerPlayerSlotStateBag::new, PerPlayerSlotStateBag::backing);

    private final CompoundTag backing;

    public PerPlayerSlotStateBag() {
        this(new CompoundTag());
    }

    public PerPlayerSlotStateBag(CompoundTag backing) {
        this.backing = backing;
    }

    public CompoundTag backing() {
        return backing;
    }

    /**
     * Returns the bag for {@code playerId}, creating an empty one if absent.
     * The returned bag is backed by this per-player storage — mutating it
     * mutates the underlying CompoundTag.
     */
    public SlotStateBag getOrCreate(UUID playerId) {
        String key = playerId.toString();
        CompoundTag playerTag = backing.getCompoundOrEmpty(key);
        // Back-write so the sub-tag sticks even if getCompoundOrEmpty
        // returned a fresh empty tag.
        backing.put(key, playerTag);
        return new SlotStateBag(playerTag);
    }

    /**
     * Returns the player-agnostic shared bag (§0049), creating it if absent.
     * SHARED channels resolve here instead of {@link #getOrCreate(UUID)} so
     * every viewer reads and writes one value. Mirrors {@link #getOrCreate} but
     * under the reserved {@link #SHARED_KEY}.
     */
    public SlotStateBag getOrCreateShared() {
        CompoundTag sharedTag = backing.getCompoundOrEmpty(SHARED_KEY);
        backing.put(SHARED_KEY, sharedTag);
        return new SlotStateBag(sharedTag);
    }

    /** Returns the bag for {@code playerId} or {@code null} if no entry exists. */
    public SlotStateBag get(UUID playerId) {
        String key = playerId.toString();
        if (!backing.contains(key)) return null;
        return new SlotStateBag(backing.getCompoundOrEmpty(key));
    }

    public boolean isEmpty() {
        return backing.isEmpty();
    }
}
