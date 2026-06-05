package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side render helper for grafted slots (§0045). Draws the slot frame
 * + item (+ hover highlight) for every revealed grafted {@link MenuKitSlot}
 * on a screen's menu.
 *
 * <h3>Why a render helper at all</h3>
 *
 * Grafted slots are real vanilla slots, so vanilla already draws their items,
 * hover highlights, and routes their clicks when they're active. The one
 * thing vanilla can't draw is the recessed 18×18 slot <em>frame</em> — those
 * live in the screen's GUI texture, and grafted slots sit outside it. This
 * helper draws the frame (and re-draws the item on top, since the frame would
 * otherwise cover vanilla's item) for each revealed grafted slot.
 *
 * <h3>Call site</h3>
 *
 * Invoke from the consumer's own client render mixin, <em>after</em> the
 * screen's slots have drawn — for inventory-family screens that means
 * {@code AbstractRecipeBookScreen.render} at
 * {@code INVOKE renderContents shift AFTER} (see MK's
 * {@code MenuKitRecipeBookPanelRenderMixin} for the exact injection point).
 * The library does not ship that mixin (§0019 / §0045 — render decoration is
 * consumer-owned, the same as panel decoration); the consumer composes this
 * helper inside their mixin.
 *
 * <h3>Client-only</h3>
 *
 * References client render types; only ever loaded client-side. The server
 * never touches it — the graft itself runs through {@link MenuKitGraft},
 * which carries no client types.
 *
 * <p>Inert (hidden) grafted slots are skipped: a hidden hover-reveal group
 * draws nothing, matching its inertness (§0021).
 */
public final class MenuKitGraftRender {

    private MenuKitGraftRender() {}

    /**
     * Draws every revealed grafted {@link MenuKitSlot} on {@code screen}'s
     * menu. No-op for inert slots (their panel hidden) and for non-grafted
     * slots. Safe to call on any container screen — screens without grafted
     * slots draw nothing.
     */
    public static void renderGraftedSlots(AbstractContainerScreen<?> screen,
                                          GuiGraphics graphics,
                                          int mouseX, int mouseY) {
        AbstractContainerMenu menu = screen.getMenu();
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int leftPos = acc.menuKit$getLeftPos();
        int topPos = acc.menuKit$getTopPos();

        for (Slot slot : menu.slots) {
            if (!(slot instanceof MenuKitSlot mk)) continue;
            if (mk.isInert()) continue; // hidden hover-reveal group → draw nothing

            int sx = leftPos + mk.graftX();
            int sy = topPos + mk.graftY();
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
