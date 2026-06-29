package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.SlotHoverResult;
import com.trevorschoeny.menukit.inject.SlotScreenHook;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.Set;

/**
 * MenuKit-Containers' implementation of MenuKit's neutral {@link SlotScreenHook} —
 * the one place panel-hosted registered-slot <em>input resolution</em> plugs into
 * MenuKit's library-owned screen dispatch (§0042). Registered with
 * {@code SlotScreenDispatcher.setHook} at MKC client init.
 *
 * <p>The input limb only. A registered slot draws itself inline as a
 * {@code SlotElement} on the panel pipeline, so this hook no longer renders anything
 * and holds no presence list. Its sole job is hover/click resolution: fed by the live
 * {@link SlotElementRegistry} (the set of panel ids that currently host a
 * {@code SlotElement}), it asks {@link MKCSlotInput} which {@code MKCSlot} a screen
 * point covers, so vanilla's {@code getHoveredSlot} routes hover/click to the
 * registered slot rather than the vanilla slot beneath it — and eats clicks that land
 * in a revealed panel's empty space (the gap-block backstop).
 *
 * <p>Client-only — every method runs on the render/input thread; the server never
 * constructs this.
 */
public final class MKCSlotScreenHook implements SlotScreenHook {

    // ── SlotScreenHook ─────────────────────────────────────────────────────

    @Override
    public SlotHoverResult resolveHover(AbstractContainerScreen<?> screen,
                                         double mouseX, double mouseY) {
        Set<String> ids = SlotElementRegistry.activePanelIds();
        if (ids.isEmpty()) return SlotHoverResult.PASS;

        MKCSlotInput.Resolution r =
                MKCSlotInput.resolveHoveredSlot(screen, mouseX, mouseY, ids);
        if (!r.handled()) return SlotHoverResult.PASS;
        // handled with a slot → slot wins; handled with null → in-panel gap (block).
        return r.slot() == null ? SlotHoverResult.BLOCK : SlotHoverResult.of(r.slot());
    }

    @Override
    public boolean mouseClicked(AbstractContainerScreen<?> screen,
                                double mouseX, double mouseY, int button) {
        // Gap-block backstop: eat a click that lands in a revealed panel's empty space
        // so a carried item can't fall through to the inert vanilla slot behind it. A
        // click on the slot itself is NOT eaten — getHoveredSlot routed it. For a
        // panel-hosted SlotElement in an opaque panel this is a backstop: panel opacity
        // already eats gap clicks at the MouseHandler level before this fires; it still
        // covers a non-opaque panel of bare slots.
        Set<String> ids = SlotElementRegistry.activePanelIds();
        if (ids.isEmpty()) return false;
        MKCSlotInput.Resolution r =
                MKCSlotInput.resolveHoveredSlot(screen, mouseX, mouseY, ids);
        return r.handled() && r.slot() == null;
    }
}
