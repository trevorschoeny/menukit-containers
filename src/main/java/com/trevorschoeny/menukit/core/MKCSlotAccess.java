package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.Slots;

import net.minecraft.world.inventory.Slot;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a slot in a screen's {@code menu.slots} to the {@link MKCSlot} it
 * represents — directly, or through the creative {@code SlotWrapper} that wraps
 * it. The one place the "is this a registered slot?" question is answered uniformly
 * across the survival inventory (raw {@code MKCSlot}s in the menu) and the
 * creative screen (each slot wrapped in a {@code SlotWrapper}).
 *
 * <p>Rides MenuKit's generic {@link Slots#target} unwrap — the same seam
 * {@link com.trevorschoeny.menukit.inject.VanillaSlotResolver} uses for vanilla
 * slots — so there is one unwrap path for slots and non-slots alike. This asks
 * only the containers-specific question on top: is the unwrapped target a slot?
 *
 * <p>Universal (both sides). The creative {@code SlotWrapper} it unwraps is a
 * client type, but {@link Slots#target} tests an interface in MenuKit's own jar
 * and never class-loads that client type — server-side no slot is a wrapper, so
 * it returns the slot unchanged. THE ONE WINDOW's server enforcement
 * ({@link SlotAddresses}/{@link WindowGating}) calls this on the logical server
 * to detect a created slot; the render + input helpers call it on the client.
 *
 * <p><b>Internal plumbing.</b> {@link #asMKCSlot(Slot)} is the engine's
 * raw-{@code Slot}→{@code MKCSlot} detection primitive. Consumers never hand MK a
 * raw vanilla {@code Slot}; they address created slots by their
 * {@link com.trevorschoeny.menukit.window.Address}.
 */
@ApiStatus.Internal
public final class MKCSlotAccess {

    private MKCSlotAccess() {}

    /**
     * The registered slot {@code slot} is or wraps, or {@code null} if it is an
     * ordinary slot. Unwraps a creative {@code SlotWrapper} via
     * {@link Slots#target}, then tests whether the real slot is a slot.
     */
    public static @Nullable MKCSlot asMKCSlot(Slot slot) {
        return Slots.target(slot) instanceof MKCSlot mk ? mk : null;
    }
}
