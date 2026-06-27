package com.trevorschoeny.menukit.core;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * The per-frame geometry handed to a {@link SlotScreenPresence}'s callbacks —
 * the current screen, its frame origin ({@code leftPos}/{@code topPos}), and the
 * cursor. Everything a consumer needs to position decoration, reposition registered
 * slots, or hit-test a widget on <em>this</em> screen, without re-deriving any of
 * it or importing accessor mixins.
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

    /** The current screen's menu — where the registered slots live (and get repositioned). */
    public AbstractContainerMenu menu() {
        return screen.getMenu();
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
