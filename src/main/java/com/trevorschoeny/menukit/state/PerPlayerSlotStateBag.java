package com.trevorschoeny.menukit.state;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Per-player-per-owner state bag — wraps a {@link CompoundTag} whose keys are
 * player UUID strings and whose values are {@link SlotStateBag}s. Attached to
 * block entities and entities so each viewing player has their own private set
 * of marks on the shared container.
 *
 * <p>Shape chosen for V2 shared-state migration (see M1 design doc §4.2
 * storage-shape tradeoff): dropping the UUID layer promotes shared channels
 * without a data rewrite.
 */
public final class PerPlayerSlotStateBag {

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
