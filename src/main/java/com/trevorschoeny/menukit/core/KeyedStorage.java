package com.trevorschoeny.menukit.core;

/**
 * A {@link Storage} that knows its own persistent identity. Implementing
 * {@code KeyedStorage} is the opt-in path for making a custom-panel slot
 * resolvable by M1's persistent-state machinery — once the resolver can
 * map the slot's container back to a {@link PersistentContainerKey}, the
 * full {@link SlotStateChannel} API works against that slot the same way
 * it works against vanilla containers.
 *
 * <p><b>Why this lives in {@code menukit-containers} (MKC), not {@code menukit}:</b>
 * {@link PersistentContainerKey} itself lives in MKC because slot-state
 * persistence is an MKC-side concern (M1, the per-slot state mechanism,
 * is a container-extension feature). Adding the keyed-storage default to
 * the MK-side {@link Storage} interface would force MK to import an MKC
 * type — a direct §0042 partition violation. Keeping {@code KeyedStorage}
 * here, as a sibling interface in MKC, leaves {@link Storage} minimal in
 * MK and puts the persistence variant in the module that owns the key
 * type. Consumers who want their custom-storage slots to participate in
 * M1 persistence implement {@code KeyedStorage} (which extends
 * {@link Storage}) instead of {@link Storage} directly.
 *
 * <p><b>Resolution path:</b> the
 * {@link com.trevorschoeny.menukit.state.ContainerKeyResolver} sees that
 * the slot's container is a {@link StorageContainerAdapter}, inspects the
 * adapter's backing {@link Storage}, and — if it's a {@code KeyedStorage}
 * — uses {@link #storageKey()} as the persistent identity. Non-keyed
 * storages fall through to the existing resolution table (player
 * inventory, ender chest, block entity, modded resolvers).
 *
 * <p><b>Key choice:</b> the implementer picks the
 * {@link PersistentContainerKey} variant that matches the storage's
 * natural ownership scope. A player-attached storage typically returns a
 * {@link PersistentContainerKey.Modded} key with the player UUID encoded
 * in the payload; a block-entity-backed storage would return a
 * {@link PersistentContainerKey.BlockEntityKey}; etc.
 */
public interface KeyedStorage extends Storage {

    /**
     * Returns this storage's persistent identity. Used by M1 to look up
     * per-slot state in the attachment scoped to this owner.
     *
     * <p>Implementations should return a stable, equality-comparable key —
     * two calls on the same logical storage instance must produce keys
     * that compare {@code equals}, even if invoked across the
     * session/network boundary (server vs client sides of the same menu).
     *
     * @return the persistent key for this storage; must not be {@code null}.
     */
    PersistentContainerKey storageKey();
}
