package com.trevorschoeny.menukit.core;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;

/**
 * Stable cross-menu identity for a slot: the pair of its backing
 * {@link Container} reference and its index within that container
 * ({@code Slot.getContainerSlot()}).
 *
 * <p><b>Use case.</b> Consumer-mod per-slot state (lock markers,
 * annotations, palette choices — any per-slot metadata) that needs to
 * persist across menu transitions. Vanilla creates fresh {@link Slot}
 * instances each time a menu opens, even when the underlying storage
 * is the same. A chest's "slot 5" is a different {@code Slot} object
 * on every open; the backing {@code Container} reference and the
 * slot-index-within-container are the stable coordinates.
 *
 * <h3>Identity stability guarantees</h3>
 *
 * Two slots have equal {@code SlotIdentity} iff they reference the same
 * underlying container position. Concretely:
 *
 * <ul>
 *   <li><b>Player-inventory slots</b> — the player's main inventory,
 *       hotbar, armor, and offhand slots share the same {@code Container}
 *       ({@code Player.getInventory()}) across every menu that exposes
 *       them. Survival {@code InventoryMenu}, creative {@code ItemPickerMenu},
 *       chest {@code ChestMenu}'s player-inventory portion, etc. — all
 *       expose the same 41 slot-positions under the same {@code Inventory}
 *       instance. Identity is stable across these menu transitions.</li>
 *   <li><b>Block-entity-backed slots</b> (chests, shulkers as placed
 *       blocks, hoppers, dispensers) — identity is stable while the
 *       block entity persists. Breaking and replacing the block, or
 *       unloading and reloading its chunk, produces a new block entity
 *       instance and therefore new identities.</li>
 *   <li><b>Item-container slots</b> (shulker-box contents viewed via a
 *       consumer-mod peek mechanism, bundle contents) — generally
 *       unstable. Such containers are usually ephemeral wrappers around
 *       item NBT; a fresh wrapper is constructed per observation.</li>
 * </ul>
 *
 * <p><b>Identity is session-scoped.</b> Container references die with
 * the JVM session. For persistence across disconnect/relog, consumer
 * mods store their state in Fabric attachments or similar per-world
 * persistent storage, keyed by a stable per-world identifier (player
 * UUID + slot-in-inventory index, block position, etc.) rather than by
 * {@code SlotIdentity} directly.
 *
 * <h3>Typical consumer use</h3>
 *
 * <pre>{@code
 * // Lock a slot on keybind press:
 * SlotIdentity id = SlotIdentity.of(hoveredSlot);
 * myLockMap.put(id, new LockState(true));
 *
 * // Check later from a different menu showing the same underlying slot:
 * boolean locked = Optional.ofNullable(myLockMap.get(SlotIdentity.of(otherSlot)))
 *         .map(LockState::isLocked)
 *         .orElse(false);
 * }</pre>
 *
 * <p>Consumers that key data by {@code SlotIdentity} should mitigate
 * memory retention in long-running sessions by using {@code WeakHashMap}
 * keyed on the {@link Container} reference as the outer-level storage,
 * with the slot index as the inner key. That pattern allows container
 * references (and their associated state) to be GC'd when vanilla
 * releases them (chunk unload, block break).
 *
 * <h3>Why this ships as a MenuKit primitive</h3>
 *
 * Phase 11 consumer-mod refactor work surfaced the cross-menu slot-state
 * need in inventory-plus's lock-state feature. The (container, index)
 * tuple is already vanilla's natural stable slot identity; MenuKit
 * formalizes the abstraction so consumers don't each reinvent it.
 *
 * <p>The library ships only the identity primitive — not a registry,
 * not a state-management service, not cross-menu enumeration helpers.
 * Consumers manage their own state keyed by {@code SlotIdentity};
 * library-not-platform discipline holds.
 */
public record SlotIdentity(Container container, int containerSlot) {

    /**
     * Creates a {@code SlotIdentity} from a live {@link Slot}. The
     * identity captures the slot's backing container reference and its
     * index within that container.
     *
     * @param slot the slot to identify; must not be null
     * @return the slot's identity
     */
    public static SlotIdentity of(Slot slot) {
        return new SlotIdentity(slot.container, slot.getContainerSlot());
    }
}
