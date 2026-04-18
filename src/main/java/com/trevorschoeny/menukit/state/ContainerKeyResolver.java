package com.trevorschoeny.menukit.state;

import com.trevorschoeny.menukit.core.PersistentContainerKey;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/**
 * Resolves a {@link Container} to a {@link PersistentContainerKey}. Used by
 * M1's sync + storage paths to identify where a slot's state lives.
 *
 * <p>v1 coverage: player inventory, player ender chest, vanilla block-entity
 * containers. Entity-backed containers (donkey / minecart) and modded
 * resolvers are stubs in v1 — the resolver returns {@link Optional#empty}
 * and the library falls back to session-scoped state only (matches pre-M1
 * behavior for those containers).
 */
final class ContainerKeyResolver {

    private ContainerKeyResolver() {}

    static Optional<PersistentContainerKey> resolve(Container container) {
        if (container == null) return Optional.empty();

        // Player's main inventory (survival inventory, hotbar, armor, offhand).
        if (container instanceof Inventory inv) {
            return Optional.of(new PersistentContainerKey.PlayerInventory(inv.player.getUUID()));
        }

        // Player's ender chest.
        if (container instanceof PlayerEnderChestContainer enderChest) {
            // Ender chest owner is exposed via the player it was opened by.
            // In vanilla 1.21.11 the owner field isn't directly public; the
            // container is fetched via Player.getEnderChestInventory(). The
            // EnderChest attachment is on the Player, so we need the player
            // reference. v1 resolution requires the player owner — which we
            // don't have at the Container level. Server-side consumers use
            // the Player-explicit overload instead.
            //
            // For the /mkverify pass and F1, player inventory is the
            // primary path. Ender chest support is wired but depends on the
            // player-explicit API for lookup.
            return Optional.empty();
        }

        // Block-entity-backed containers (chest, shulker, barrel, hopper,
        // dispenser, dropper, furnace family, brewing stand).
        if (container instanceof BlockEntity be) {
            if (be.getLevel() == null) return Optional.empty();
            BlockPos pos = be.getBlockPos();
            ResourceKey<Level> dim = be.getLevel().dimension();
            return Optional.of(new PersistentContainerKey.BlockEntityKey(pos, dim));
        }

        // Registered modded BE resolvers.
        if (container instanceof BlockEntity be) {
            Optional<PersistentContainerKey> modded = SlotStateRegistry.resolveBlockEntity(be);
            if (modded.isPresent()) return modded;
        }

        // Entity-backed containers (v1: deferred — requires per-container-type
        // reverse lookup to the owning entity, which vanilla doesn't expose
        // uniformly). Registered modded entity resolvers are consulted by
        // whoever explicitly resolves an entity.

        return Optional.empty();
    }
}
