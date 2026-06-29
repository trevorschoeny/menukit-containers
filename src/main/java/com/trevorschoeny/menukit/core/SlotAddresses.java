package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.VanillaAddressing;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.jetbrains.annotations.ApiStatus;

/**
 * The one MKC-side entry that mints the {@link Address} of <em>any</em> live slot,
 * kind-dispatched — the server/enforcement counterpart of the client resolver.
 * THE ONE WINDOW's keystone is "one address scheme for every slot, vanilla or
 * created"; this is where the two enter through it:
 *
 * <ul>
 *   <li><b>Created slot</b> (an {@link MKCSlot}, or the creative wrapper around
 *       one) → its menu-independent created address ({@link CreatedSlotAdapter#addressOf}).
 *       MKC owns {@code MKCSlot}, so the detection is a direct type check — no port
 *       (the §0042 port is only needed where MK must reach an MKC type).</li>
 *   <li><b>Vanilla slot</b> → {@link VanillaAddressing#addressOf} (container
 *       identity when §0050-resolvable, else the menu-based fallback).</li>
 * </ul>
 *
 * Both the menu interaction seams ({@link WindowGating}) and a created slot's own
 * behavioral overrides ({@link MKCSlot}) resolve through the <em>same</em>
 * encoding, so a behavior set on a slot's address is found no matter which path
 * reaches it. Server-safe: {@link MKCSlotAccess#asMKCSlot} rides {@code Slots.target},
 * whose creative-wrapper unwrap is simply absent server-side.
 */
public final class SlotAddresses {

    private SlotAddresses() {}

    /**
     * The {@link Address} of {@code slot} as it sits in {@code menu}, kind-dispatched.
     *
     * <p><b>Internal minter.</b> Live-{@code Slot}→{@code Address} mapping is the
     * MKC enforcement/addressing-port plumbing (installed into
     * {@link com.trevorschoeny.menukit.window.ClientSlotAddressing} + called by
     * {@link WindowGating}). Consumers hold the created-slot address minted by
     * identity ({@link CreatedSlotAdapter#addressOf}), not raw slots.
     */
    @ApiStatus.Internal
    public static Address of(AbstractContainerMenu menu, Slot slot) {
        MKCSlot created = MKCSlotAccess.asMKCSlot(slot);
        if (created != null) return CreatedSlotAdapter.addressOf(created);
        return VanillaAddressing.addressOf(menu, slot);
    }
}
