package com.trevorschoeny.menukit.state;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Resolver-namespaced slot-state bags for player-scoped grafted slots
 * (§0045). Wraps a {@link CompoundTag} whose keys are graft resolver-id
 * strings and whose values are {@link SlotStateBag}s. Attached to a
 * {@code Player} via {@link SlotStateAttachments#MODDED_PLAYER}.
 *
 * <p>The resolver-id namespace keeps multiple grafts on the same player from
 * colliding: a mod grafting both "pockets" and "equipment" onto the inventory
 * gets a distinct bag per resolver-id, so their independent slot-index spaces
 * (each {@code 0..N}) never overwrite each other — and the same holds across
 * mods (§0019 coexistence).
 *
 * <p>Same shape as {@link PerPlayerSlotStateBag}, but keyed by resolver-id
 * rather than viewer UUID: here the owner <em>is</em> the player, and the
 * namespace is the graft, not the viewer.
 */
@ApiStatus.Internal
public final class NamespacedSlotStateBag {

    /** Codec that round-trips the bag to/from a CompoundTag (attachment persistence). */
    public static final Codec<NamespacedSlotStateBag> CODEC =
            CompoundTag.CODEC.xmap(NamespacedSlotStateBag::new, NamespacedSlotStateBag::backing);

    private final CompoundTag backing;

    public NamespacedSlotStateBag() {
        this(new CompoundTag());
    }

    public NamespacedSlotStateBag(CompoundTag backing) {
        this.backing = backing;
    }

    public CompoundTag backing() {
        return backing;
    }

    /**
     * Returns the bag for {@code resolverId}, creating an empty one if absent.
     * The returned bag is backed by this storage — mutating it mutates the
     * underlying CompoundTag.
     */
    public SlotStateBag getOrCreate(Identifier resolverId) {
        String key = resolverId.toString();
        CompoundTag sub = backing.getCompoundOrEmpty(key);
        backing.put(key, sub); // back-write so a fresh empty sub-tag sticks
        return new SlotStateBag(sub);
    }

    /** Returns the bag for {@code resolverId}, or {@code null} if absent. */
    public @Nullable SlotStateBag get(Identifier resolverId) {
        String key = resolverId.toString();
        if (!backing.contains(key)) return null;
        return new SlotStateBag(backing.getCompoundOrEmpty(key));
    }

    public boolean isEmpty() {
        return backing.isEmpty();
    }
}
