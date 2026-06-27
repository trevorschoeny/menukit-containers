package com.trevorschoeny.menukit.core;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The value of the {@code GATING} server-tier behavior — what a slot accepts and
 * releases. The library's single server seam resolves a {@code SlotGate} for any
 * slot by {@link com.trevorschoeny.menukit.window.Address} and applies it: a
 * denied placement/pickup is vetoed before the mutation commits.
 *
 * <h2>The {@link GatingContext}</h2>
 *
 * Each decision receives the context of who/what is acting, so a gate can choose
 * its own policy on the non-modded bypass. A container lock (invisible to a
 * non-modded client) bypasses when the acting player can't see slot-state
 * (§0050); an equipment gate that only accepts an elytra enforces unconditionally.
 * The library owns the acting-player capture and the capability query; the gate
 * decides what to do with them.
 *
 * <p>This generalizes Inventory Max's hand-rolled container-lock enforcement: a
 * lock is just a {@code SlotGate} that denies place/pickup when its shared state
 * says "locked" and the acting player is capable.
 */
public interface SlotGate {

    /** Whether {@code stack} may be placed into the gated slot in this context. */
    boolean mayPlace(ItemStack stack, GatingContext context);

    /** Whether {@code player} may take from the gated slot in this context. */
    boolean mayPickup(Player player, GatingContext context);

    /** The permissive default — vanilla behavior (allows everything). The library
     *  default for the {@code GATING} key, so an un-gated slot is exactly vanilla. */
    SlotGate OPEN = new SlotGate() {
        @Override public boolean mayPlace(ItemStack stack, GatingContext context) { return true; }
        @Override public boolean mayPickup(Player player, GatingContext context) { return true; }
    };
}
