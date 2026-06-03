package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side input-resolution helper for grafted slots — the input
 * counterpart to {@link MenuKitGraftRender}, and the graft-context enforcement
 * of §0046 (a panel makes what it covers inert, in every context).
 *
 * <h3>The gap this closes</h3>
 *
 * Grafted {@link MenuKitSlot}s are appended <em>last</em> to {@code menu.slots},
 * so vanilla's {@code getHoveredSlot} — which returns the first hovering slot —
 * resolves to the <em>vanilla</em> slot beneath a graft, not the graft itself.
 * A revealed pocket is then visually on top (via {@link MenuKitGraftRender}) but
 * click-through: §0037's "everything behind a panel is inert" never reached it,
 * because §0037's enforcement keys off MenuKit's panel registry and a grafted
 * panel isn't in it. This helper supplies the graft-context resolution.
 *
 * <h3>What it does</h3>
 *
 * {@link #resolveHoveredSlot} answers, for a screen point:
 * <ul>
 *   <li>over a revealed grafted slot → that slot <b>wins</b> (hover + click
 *       route to it over the covered vanilla slot);</li>
 *   <li>inside a revealed grafted panel but between slots → <b>blocked</b>
 *       (the covered vanilla slot is inert — §0046 / §0037 bounding-box);</li>
 *   <li>elsewhere → <b>pass</b> (vanilla resolution proceeds untouched).</li>
 * </ul>
 *
 * Hidden (inert) grafted panels contribute nothing — they neither win nor
 * block, matching their inertness (§0021).
 *
 * <h3>Consumer wiring (§0045 — the consumer owns the mixins)</h3>
 *
 * Wire this from your own client mixins, exactly as you wire
 * {@link MenuKitGraftRender} for render:
 * <pre>{@code
 * // @Mixin(AbstractContainerScreen.class)
 * @Inject(method = "getHoveredSlot", at = @At("HEAD"), cancellable = true)
 * void preferGraft(double mx, double my, CallbackInfoReturnable<Slot> cir) {
 *     var r = MenuKitGraftInput.resolveHoveredSlot((AbstractContainerScreen<?>)(Object)this, mx, my);
 *     if (r.handled()) cir.setReturnValue(r.slot());   // grafted slot, or null for a gap
 * }
 *
 * @Inject(method = "mouseClicked(...)", at = @At("HEAD"), cancellable = true)
 * void blockCoveredClick(MouseButtonEvent e, boolean dbl, CallbackInfoReturnable<Boolean> cir) {
 *     // ...handle your own panel widgets (resize buttons, etc.) first, returning early...
 *     var r = MenuKitGraftInput.resolveHoveredSlot((AbstractContainerScreen<?>)(Object)this, e.x(), e.y());
 *     if (r.handled() && r.slot() == null) cir.setReturnValue(true);  // eat gap clicks → no item drop
 * }
 * }</pre>
 *
 * The {@code getHoveredSlot} hook makes the grafted slot win hover, tooltip and
 * the click target (vanilla routes the click to whatever {@code getHoveredSlot}
 * returns); the {@code mouseClicked} hook makes the panel's empty space inert
 * so a click in a gap can't fall through and drop a carried item.
 *
 * <h3>Client-only</h3>
 *
 * References client screen types; only ever loaded client-side, like
 * {@link MenuKitGraftRender}. The graft itself ({@link MenuKitGraft}) carries no
 * client types and runs on both sides.
 */
public final class MenuKitGraftInput {

    private MenuKitGraftInput() {}

    /**
     * Resolution of a screen point against the revealed grafted panels on a
     * screen.
     *
     * @param handled whether a revealed grafted panel claims this point — when
     *                true, the caller overrides vanilla's slot resolution with
     *                {@link #slot()}.
     * @param slot    the grafted slot under the point, or {@code null} when the
     *                point is inside a revealed panel but not on a slot (the
     *                covered vanilla slot is inert).
     */
    public record Resolution(boolean handled, Slot slot) {
        private static final Resolution PASS = new Resolution(false, null);
        private static final Resolution BLOCKED = new Resolution(true, null);
        private static Resolution hit(Slot s) { return new Resolution(true, s); }
    }

    /**
     * Resolves the slot under {@code (mouseX, mouseY)} considering the revealed
     * grafted panels on {@code screen}. See the class javadoc for the three
     * outcomes. Safe on any container screen — one with no revealed grafted
     * slots returns {@link Resolution#PASS}.
     */
    public static Resolution resolveHoveredSlot(AbstractContainerScreen<?> screen,
                                                double mouseX, double mouseY) {
        AbstractContainerMenu menu = screen.getMenu();
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int leftPos = acc.menuKit$getLeftPos();
        int topPos = acc.menuKit$getTopPos();

        // Cursor in container-relative space (vanilla's isHovering frame).
        double relX = mouseX - leftPos;
        double relY = mouseY - topPos;

        // Per-panel bounding box over revealed grafted slots, so the
        // "inside a revealed panel" gap test (§0037 bounding-box opacity) is
        // decided per panel — never one hull spanning two separate panels.
        Map<String, int[]> bounds = null; // panelId -> {minX, minY, maxX, maxY}

        for (Slot slot : menu.slots) {
            if (!(slot instanceof MenuKitSlot mk) || mk.isInert()) continue;

            // Vanilla hover frame for an 18px slot cell: x-1 .. x+17.
            int x0 = mk.x - 1, y0 = mk.y - 1;
            int x1 = mk.x + 17, y1 = mk.y + 17;

            if (relX >= x0 && relX < x1 && relY >= y0 && relY < y1) {
                return Resolution.hit(mk); // grafted slot wins outright
            }

            if (bounds == null) bounds = new HashMap<>();
            int[] b = bounds.computeIfAbsent(mk.getPanelId(),
                    k -> new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE,
                                   Integer.MIN_VALUE, Integer.MIN_VALUE});
            if (x0 < b[0]) b[0] = x0;
            if (y0 < b[1]) b[1] = y0;
            if (x1 > b[2]) b[2] = x1;
            if (y1 > b[3]) b[3] = y1;
        }

        // No slot hit: inside a revealed panel's bounds → the covered vanilla
        // slot is inert (block the click/hover behind the panel's empty space).
        if (bounds != null) {
            for (int[] b : bounds.values()) {
                if (relX >= b[0] && relX < b[2] && relY >= b[1] && relY < b[3]) {
                    return Resolution.BLOCKED;
                }
            }
        }

        return Resolution.PASS;
    }
}
