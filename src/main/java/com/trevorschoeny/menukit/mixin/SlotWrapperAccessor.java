package com.trevorschoeny.menukit.mixin;

import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Unwraps a creative {@code CreativeModeInventoryScreen$SlotWrapper} back to the
 * vanilla {@link Slot} it delegates to (its {@code target} field).
 *
 * <h3>Why grafts need this</h3>
 *
 * The creative inventory tab rebuilds its menu by wrapping every
 * {@code player.inventoryMenu} slot — grafted slots included — in a package-private
 * {@code SlotWrapper}. So on the creative screen {@code menu.slots} holds
 * {@code SlotWrapper}s, not the raw {@code MenuKitSlot}s (those stay in
 * {@code player.inventoryMenu}). The grafted-slot render + input helpers walk
 * {@code menu.slots}; to recognise a grafted slot there they unwrap each
 * {@code SlotWrapper} via this accessor and test the {@code target}. Surfacing the
 * wrapper itself (rather than the bare graft) is also what makes a creative click
 * route correctly — creative's click path hard-casts the hovered slot to
 * {@code SlotWrapper} and reads {@code target.index}.
 *
 * <p>Client-only mixin (creative screen is a client type). Targets the inner class
 * by binary name since it is not public.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
public interface SlotWrapperAccessor {

    /** The slot this wrapper delegates to (creative's {@code SlotWrapper.target}). */
    @Accessor("target")
    Slot menuKit$getTarget();
}
