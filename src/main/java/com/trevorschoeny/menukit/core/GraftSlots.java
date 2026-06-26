package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.SlotWrapperAccessor;

import net.minecraft.world.inventory.Slot;

import org.jspecify.annotations.Nullable;

/**
 * Resolves a slot in a screen's {@code menu.slots} to the {@link MenuKitSlot} it
 * represents — directly, or through the creative {@code SlotWrapper} that wraps
 * it. The one place the "is this a grafted slot?" question is answered uniformly
 * across the survival inventory (raw {@code MenuKitSlot}s in the menu) and the
 * creative screen (each graft wrapped in a {@code SlotWrapper}).
 *
 * <p>Client-only: the creative wrapper is a client type. Only the render + input
 * helpers (themselves client-only) call this.
 */
public final class GraftSlots {

    private GraftSlots() {}

    /**
     * The grafted slot {@code slot} is or wraps, or {@code null} if it is an
     * ordinary slot. Unwraps a creative {@code SlotWrapper} via
     * {@link SlotWrapperAccessor} ({@code instanceof} the accessor interface is
     * true only for the creative wrapper, which the mixin is applied to).
     */
    public static @Nullable MenuKitSlot asGraft(Slot slot) {
        if (slot instanceof MenuKitSlot mk) {
            return mk;
        }
        if (slot instanceof SlotWrapperAccessor wrapper
                && wrapper.menuKit$getTarget() instanceof MenuKitSlot mk) {
            return mk;
        }
        return null;
    }
}
