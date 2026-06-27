package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * Client-side render helper for registered slots (§0045). Draws the slot frame
 * + item (+ hover highlight) for every revealed registered {@link MKCSlot}
 * on a screen's menu.
 *
 * <h3>Why a render helper at all</h3>
 *
 * RegisteredSlots slots are real vanilla slots, so vanilla already draws their items,
 * hover highlights, and routes their clicks when they're active. The one
 * thing vanilla can't draw is the recessed 18×18 slot <em>frame</em> — those
 * live in the screen's GUI texture, and registered slots sit outside it. This
 * helper draws the frame (and re-draws the item on top, since the frame would
 * otherwise cover vanilla's item) for each revealed registered slot.
 *
 * <h3>Call site</h3>
 *
 * Invoke from the consumer's own client render mixin, <em>after</em> the
 * screen's slots have drawn — for inventory-family screens that means
 * {@code AbstractRecipeBookScreen.render} at
 * {@code INVOKE renderContents shift AFTER} (see MK's
 * {@code MKRecipeBookPanelRenderMixin} for the exact injection point).
 * The library does not ship that mixin (§0019 / §0045 — render decoration is
 * consumer-owned, the same as panel decoration); the consumer composes this
 * helper inside their mixin.
 *
 * <h3>Client-only</h3>
 *
 * References client render types; only ever loaded client-side. The server
 * never touches it — the slot itself runs through {@link MKCSlots},
 * which carries no client types.
 *
 * <p>Inert (hidden) registered slots are skipped: a hidden hover-reveal group
 * draws nothing, matching its inertness (§0021).
 */
public final class MKCSlotRender {

    private MKCSlotRender() {}

    /**
     * Draws every revealed registered {@link MKCSlot} on {@code screen}'s
     * menu. No-op for inert slots (their panel hidden) and for non-registered
     * slots. Safe to call on any container screen — screens without registered
     * slots draw nothing.
     *
     * <p>Draws all slots regardless of panel. Equivalent to
     * {@link #renderSlots(AbstractContainerScreen, GuiGraphics, int, int, String)}
     * with a {@code null} filter.
     */
    public static void renderSlots(AbstractContainerScreen<?> screen,
                                          GuiGraphics graphics,
                                          int mouseX, int mouseY) {
        renderSlots(screen, graphics, mouseX, mouseY, null);
    }

    /**
     * Draws the revealed registered slots on {@code screen}'s menu whose panel id
     * equals {@code panelId} (or all slots when {@code panelId} is null). The
     * screen dispatcher passes one panel id per registered presence so each
     * slot draws under its own decoration and respects its own per-screen
     * opt-out.
     *
     * <p>Recognises slots both directly ({@code MKCSlot} in the menu, the
     * survival inventory) and through the creative {@code SlotWrapper} that wraps
     * them ({@link Slots#asMKCSlot}) — so the same call renders correctly on
     * the creative screen, where the wrappers are parked off-screen by
     * {@code MKCCreativeSlotParkMixin} and the slot is drawn here at its
     * live {@code renderX/renderY} instead.
     */
    public static void renderSlots(AbstractContainerScreen<?> screen,
                                          GuiGraphics graphics,
                                          int mouseX, int mouseY,
                                          @Nullable String panelId) {
        AbstractContainerMenu menu = screen.getMenu();
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int leftPos = acc.mk$getLeftPos();
        int topPos = acc.mk$getTopPos();

        for (Slot slot : menu.slots) {
            MKCSlot mk = MKCSlotAccess.asMKCSlot(slot);
            if (mk == null) continue;
            if (panelId != null && !panelId.equals(mk.getPanelId())) continue;
            if (mk.isInert()) continue; // hidden hover-reveal group → draw nothing

            int sx = leftPos + mk.renderX();
            int sy = topPos + mk.renderY();
            int size = SlotRendering.DEFAULT_SIZE;

            // Frame first (covers vanilla's under-frame item render), then the
            // item on top, then a hover highlight if the cursor is inside the
            // item area. Mouse-in-bounds is computed directly so this carries
            // no dependency on the vanilla hovered-slot accessor.
            SlotRendering.drawSlotBackground(graphics, sx, sy, size, false);
            ItemStack stack = mk.getItem();
            if (!stack.isEmpty()) {
                SlotRendering.drawItem(graphics, stack, sx, sy, size, false);
            }
            boolean hovered = mouseX >= sx + SlotRendering.ITEM_INSET
                    && mouseX < sx + size - SlotRendering.ITEM_INSET
                    && mouseY >= sy + SlotRendering.ITEM_INSET
                    && mouseY < sy + size - SlotRendering.ITEM_INSET;
            if (hovered) {
                SlotRendering.drawHoverHighlight(graphics, sx, sy, size);
            }
        }
    }
}
