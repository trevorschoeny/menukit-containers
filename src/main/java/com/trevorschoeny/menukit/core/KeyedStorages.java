package com.trevorschoeny.menukit.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Factories for {@link KeyedStorage} — a {@link Storage} that declares its
 * own {@link PersistentContainerKey} so M1 per-slot metadata persists for
 * grafted slots (§0045).
 *
 * <p>The player-scoped factory is the supported path for grafting
 * player-owned storage (IP Pockets, IP Equipment Slots) with per-slot
 * metadata. It wraps a content {@link Storage} (typically bound from a
 * {@link StorageAttachment}) and tags it with a
 * {@link PersistentContainerKey.Modded} key whose payload encodes the owning
 * player. M1's {@code ContainerKeyResolver} routes the grafted slot to this
 * key; the server resolves it to a resolver-id-namespaced bag on the player
 * (so distinct grafts, and distinct mods, never collide).
 *
 * <p>The payload shape is <b>library-owned</b> — these are library-produced
 * keys, distinct from the consumer-defined opaque payloads of §0034's general
 * modded-resolver path. The keys below are the contract between this factory
 * and {@code SlotStateServer}'s {@code Modded} resolution.
 */
public final class KeyedStorages {

    private KeyedStorages() {}

    /** Payload key: graft ownership scope. */
    public static final String SCOPE_KEY = "menukit:scope";
    /** Payload key: owning player UUID (string form), when scope is {@link #SCOPE_PLAYER}. */
    public static final String OWNER_KEY = "menukit:owner";
    /** {@link #SCOPE_KEY} value for player-owned grafts. */
    public static final String SCOPE_PLAYER = "player";

    /**
     * Wraps {@code backing} as a player-scoped {@link KeyedStorage}. The
     * returned storage delegates all content operations to {@code backing} and
     * reports a stable {@link PersistentContainerKey.Modded} key identifying
     * ({@code resolverId}, {@code playerId}) — so per-slot metadata written
     * against the grafted slots persists on the player, namespaced by
     * {@code resolverId}.
     *
     * <p>The key is equality-stable across the client/server boundary (same
     * resolver-id + same payload content), satisfying {@link KeyedStorage}'s
     * contract.
     *
     * @param backing    the content storage (e.g. {@code POCKETS.bind(player)})
     * @param resolverId stable identity of this graft (e.g.
     *                   {@code "inventory-plus:pockets"}); namespaces the bag
     * @param playerId   the owning player's UUID
     */
    public static KeyedStorage player(Storage backing, Identifier resolverId, UUID playerId) {
        CompoundTag payload = new CompoundTag();
        payload.putString(SCOPE_KEY, SCOPE_PLAYER);
        payload.putString(OWNER_KEY, playerId.toString());
        PersistentContainerKey key = new PersistentContainerKey.Modded(resolverId, payload);
        return new DelegatingKeyedStorage(backing, key);
    }

    /** Forwards content ops to a backing {@link Storage} and adds a fixed key. */
    private record DelegatingKeyedStorage(Storage backing, PersistentContainerKey key)
            implements KeyedStorage {

        @Override public PersistentContainerKey storageKey() { return key; }

        @Override public ItemStack getStack(int localIndex) { return backing.getStack(localIndex); }
        @Override public void setStack(int localIndex, ItemStack stack) { backing.setStack(localIndex, stack); }
        @Override public int size() { return backing.size(); }
        @Override public void markDirty() { backing.markDirty(); }
    }
}
