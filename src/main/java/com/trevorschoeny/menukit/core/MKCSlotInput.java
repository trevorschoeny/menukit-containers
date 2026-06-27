package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Client-side input-resolution helper for registered slots — the input
 * counterpart to {@link MKCSlotRender}, and the slot-context enforcement
 * of §0046 (a panel makes what it covers inert, in every context).
 *
 * <h3>The gap this closes</h3>
 *
 * RegisteredSlots {@link MKCSlot}s are appended <em>last</em> to {@code menu.slots},
 * so vanilla's {@code getHoveredSlot} — which returns the first hovering slot —
 * resolves to the <em>vanilla</em> slot beneath a slot, not the slot itself.
 * A revealed pocket is then visually on top (via {@link MKCSlotRender}) but
 * click-through: §0037's "everything behind a panel is inert" never reached it,
 * because §0037's enforcement keys off MenuKit's panel registry and a registered
 * panel isn't in it. This helper supplies the slot-context resolution.
 *
 * <h3>What it does</h3>
 *
 * {@link #resolveHoveredSlot} answers, for a screen point:
 * <ul>
 *   <li>over a revealed registered slot → that slot <b>wins</b> (hover + click
 *       route to it over the covered vanilla slot);</li>
 *   <li>inside a revealed registered panel but between slots → <b>blocked</b>
 *       (the covered vanilla slot is inert — §0046 / §0037 bounding-box);</li>
 *   <li>elsewhere → <b>pass</b> (vanilla resolution proceeds untouched).</li>
 * </ul>
 *
 * Hidden (inert) registered panels contribute nothing — they neither win nor
 * block, matching their inertness (§0021).
 *
 * <h3>Consumer wiring (§0045 — the consumer owns the mixins)</h3>
 *
 * Wire this from your own client mixins, exactly as you wire
 * {@link MKCSlotRender} for render:
 * <pre>{@code
 * // @Mixin(AbstractContainerScreen.class)
 * @Inject(method = "getHoveredSlot", at = @At("HEAD"), cancellable = true)
 * void preferSlot(double mx, double my, CallbackInfoReturnable<Slot> cir) {
 *     var r = MKCSlotInput.resolveHoveredSlot((AbstractContainerScreen<?>)(Object)this, mx, my);
 *     if (r.handled()) cir.setReturnValue(r.slot());   // registered slot, or null for a gap
 * }
 *
 * @Inject(method = "mouseClicked(...)", at = @At("HEAD"), cancellable = true)
 * void blockCoveredClick(MouseButtonEvent e, boolean dbl, CallbackInfoReturnable<Boolean> cir) {
 *     // ...handle your own panel widgets (resize buttons, etc.) first, returning early...
 *     var r = MKCSlotInput.resolveHoveredSlot((AbstractContainerScreen<?>)(Object)this, e.x(), e.y());
 *     if (r.handled() && r.slot() == null) cir.setReturnValue(true);  // eat gap clicks → no item drop
 * }
 * }</pre>
 *
 * The {@code getHoveredSlot} hook makes the registered slot win hover, tooltip and
 * the click target (vanilla routes the click to whatever {@code getHoveredSlot}
 * returns); the {@code mouseClicked} hook makes the panel's empty space inert
 * so a click in a gap can't fall through and drop a carried item.
 *
 * <h3>Client-only</h3>
 *
 * References client screen types; only ever loaded client-side, like
 * {@link MKCSlotRender}. The slot itself ({@link MKCSlots}) carries no
 * client types and runs on both sides.
 */
public final class MKCSlotInput {

    private MKCSlotInput() {}

    /**
     * Resolution of a screen point against the revealed registered panels on a
     * screen.
     *
     * @param handled whether a revealed registered panel claims this point — when
     *                true, the caller overrides vanilla's slot resolution with
     *                {@link #slot()}.
     * @param slot    the registered slot under the point, or {@code null} when the
     *                point is inside a revealed panel but not on a slot (the
     *                covered vanilla slot is inert).
     */
    public record Resolution(boolean handled, Slot slot) {
        private static final Resolution PASS = new Resolution(false, null);
        private static final Resolution BLOCKED = new Resolution(true, null);
        private static Resolution hit(Slot s) { return new Resolution(true, s); }
    }

    /**
     * Resolves the slot under {@code (mouseX, mouseY)} considering every revealed
     * registered panel on {@code screen}. Equivalent to
     * {@link #resolveHoveredSlot(AbstractContainerScreen, double, double, Set)}
     * with a {@code null} filter.
     */
    public static Resolution resolveHoveredSlot(AbstractContainerScreen<?> screen,
                                                double mouseX, double mouseY) {
        return resolveHoveredSlot(screen, mouseX, mouseY, null);
    }

    /**
     * Resolves the slot under {@code (mouseX, mouseY)} considering only the
     * revealed slots whose panel id is in {@code panelFilter} (or all slots when
     * {@code panelFilter} is null). The screen dispatcher passes the active
     * panels for the current screen so an opted-out slot neither wins hover nor
     * blocks the slot behind it.
     *
     * <p>Recognises slots directly and through the creative {@code SlotWrapper}
     * ({@link Slots#asMKCSlot}). On a hit it returns the slot that is actually
     * in {@code menu.slots} — the raw {@code MKCSlot} on the survival
     * inventory, the {@code SlotWrapper} on the creative screen — because the
     * caller (vanilla {@code getHoveredSlot}) routes the click to whatever this
     * returns, and creative's click path requires the wrapper.
     *
     * <p>Safe on any container screen — one with no matching revealed slots
     * returns {@link Resolution#PASS}.
     */
    public static Resolution resolveHoveredSlot(AbstractContainerScreen<?> screen,
                                                double mouseX, double mouseY,
                                                @Nullable Set<String> panelFilter) {
        AbstractContainerMenu menu = screen.getMenu();
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int leftPos = acc.menuKit$getLeftPos();
        int topPos = acc.menuKit$getTopPos();

        // Cursor in container-relative space (vanilla's isHovering frame).
        double relX = mouseX - leftPos;
        double relY = mouseY - topPos;

        // Per-panel bounding box over revealed registered slots, so the
        // "inside a revealed panel" gap test (§0037 bounding-box opacity) is
        // decided per panel — never one hull spanning two separate panels.
        Map<String, int[]> bounds = null; // panelId -> {minX, minY, maxX, maxY}

        for (Slot slot : menu.slots) {
            MKCSlot mk = MKCSlotAccess.asMKCSlot(slot);
            if (mk == null || mk.isInert()) continue;
            if (panelFilter != null && !panelFilter.contains(mk.getPanelId())) continue;

            // Vanilla hover frame for an 18px slot cell: x-1 .. x+17, around the
            // slot's (mutable, §0047) presentation position.
            int x0 = mk.renderX() - 1, y0 = mk.renderY() - 1;
            int x1 = mk.renderX() + 17, y1 = mk.renderY() + 17;

            if (relX >= x0 && relX < x1 && relY >= y0 && relY < y1) {
                // Return the in-menu slot (raw slot, or the creative wrapper).
                return Resolution.hit(slot); // registered slot wins outright
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
