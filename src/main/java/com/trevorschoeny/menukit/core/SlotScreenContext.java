package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.SlotScreenRect;
import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.SlotWindowResolver;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Optional;

/**
 * The per-frame geometry handed to a {@link SlotScreenPresence}'s callbacks —
 * the current screen, its frame origin ({@code leftPos}/{@code topPos}), and the
 * cursor. Everything a consumer needs to position decoration, reposition registered
 * slots, or hit-test a widget on <em>this</em> screen, without re-deriving any of
 * it or importing accessor mixins.
 *
 * <h3>Address your slots, don't scan the menu</h3>
 *
 * To find one of your created slots on this screen, ask for it by {@link Address}
 * via {@link #slot(Address)} / {@link #slotRect(Address)} — NOT by iterating
 * {@code menu().slots} and unwrapping each with {@code asMKCSlot}. The lookup
 * resolves through the same creative-aware path the window uses, so it is correct
 * in survival and creative identically, and a consumer never holds a raw vanilla
 * {@code Slot} or branches on game mode.
 *
 * <p>Mouse coordinates are doubles (input precision); render-phase callbacks that
 * want pixels use {@link #mouseXInt()} / {@link #mouseYInt()}.
 *
 * <p>Client-only — handed out only from the client-side screen dispatch.
 */
public record SlotScreenContext(
        AbstractContainerScreen<?> screen,
        int leftPos,
        int topPos,
        double mouseX,
        double mouseY) {

    /**
     * The current screen's menu. Prefer {@link #slot(Address)} /
     * {@link #slotRect(Address)} for finding your created slots — reach for the raw
     * menu only for vanilla-menu introspection the Address path can't express.
     */
    public AbstractContainerMenu menu() {
        return screen.getMenu();
    }

    /**
     * The created slot at {@code address} on this screen, if present — the
     * Address-keyed replacement for scanning {@code menu().slots} and unwrapping
     * each with {@code asMKCSlot}. Resolves through the same creative-aware path the
     * window uses (survival + creative identical). Empty when {@code address} isn't
     * a created slot on this screen's menu.
     */
    public Optional<MKCSlot> slot(Address address) {
        return SlotWindowResolver.resolve(screen, address).map(MKCSlotAccess::asMKCSlot);
    }

    /**
     * The on-screen rect (absolute screen-space) of the slot at {@code address} on
     * this screen, if present — for drawing decoration relative to a slot without
     * touching {@code menu().slots} or deriving per-screen geometry. Covers created
     * slots (helper-rendered position) and vanilla slots alike.
     */
    public Optional<SlotScreenRect> slotRect(Address address) {
        return SlotWindowResolver.resolvePosition(screen, address);
    }

    /** Cursor x rounded to a pixel, for render-phase decoration. */
    public int mouseXInt() {
        return (int) mouseX;
    }

    /** Cursor y rounded to a pixel, for render-phase decoration. */
    public int mouseYInt() {
        return (int) mouseY;
    }
}
