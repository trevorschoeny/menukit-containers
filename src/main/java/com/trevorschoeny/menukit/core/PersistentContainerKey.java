package com.trevorschoeny.menukit.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Persistent identity for a {@code Container} — the owner a slot's state
 * attaches to, in a form that survives session boundaries.
 *
 * <p>{@link SlotIdentity} provides session-scoped identity via the container
 * reference. {@code PersistentContainerKey} is the across-session counterpart:
 * each variant names a stable owner (player UUID, block position + dimension,
 * entity UUID, or a mod-defined scope) that the library uses to look up the
 * slot's state in a Fabric attachment on the natural owner class.
 *
 * <p>See {@code menukit/Design Docs/Phase 12/M1_PER_SLOT_STATE.md} §4.1 for
 * the resolution table and rationale.
 */
public sealed interface PersistentContainerKey {

    /** The player's main inventory, hotbar, armor, and offhand slots. */
    record PlayerInventory(UUID playerId) implements PersistentContainerKey {}

    /** The player's ender chest (distinct from their main inventory). */
    record EnderChest(UUID playerId) implements PersistentContainerKey {}

    /** Block-entity-backed container (chest, shulker box, furnace, hopper, etc.). */
    record BlockEntityKey(BlockPos pos, ResourceKey<Level> dimension)
            implements PersistentContainerKey {}

    /** Entity-backed container (donkey, llama, mule, minecart-with-chest, etc.). */
    record EntityKey(UUID entityId) implements PersistentContainerKey {}

    /**
     * Modded container type. The {@code payload} CompoundTag is opaque to the
     * library — the mod defines its shape. {@code resolverId} identifies the
     * mod's registered resolver + attachment binding.
     */
    record Modded(Identifier resolverId, CompoundTag payload)
            implements PersistentContainerKey {}
}
