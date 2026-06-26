package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.Slots;

import net.minecraft.world.inventory.Slot;

import org.jspecify.annotations.Nullable;

/**
 * Resolves a slot in a screen's {@code menu.slots} to the {@link MenuKitSlot} it
 * represents — directly, or through the creative {@code SlotWrapper} that wraps
 * it. The one place the "is this a grafted slot?" question is answered uniformly
 * across the survival inventory (raw {@code MenuKitSlot}s in the menu) and the
 * creative screen (each graft wrapped in a {@code SlotWrapper}).
 *
 * <p>Rides MenuKit's generic {@link Slots#target} unwrap — the same seam
 * {@link com.trevorschoeny.menukit.inject.VanillaSlotResolver} uses for vanilla
 * slots — so there is one unwrap path for grafts and non-grafts alike. This asks
 * only the containers-specific question on top: is the unwrapped target a graft?
 *
 * <p>Client-only: the creative wrapper is a client type. Only the render + input
 * helpers (themselves client-only) call this.
 */
public final class GraftSlots {

    private GraftSlots() {}

    /**
     * The grafted slot {@code slot} is or wraps, or {@code null} if it is an
     * ordinary slot. Unwraps a creative {@code SlotWrapper} via
     * {@link Slots#target}, then tests whether the real slot is a graft.
     */
    public static @Nullable MenuKitSlot asGraft(Slot slot) {
        return Slots.target(slot) instanceof MenuKitSlot mk ? mk : null;
    }
}
