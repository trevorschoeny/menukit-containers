package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * A registered MKC slot presented as a {@link PanelElement} — the keystone of
 * the everything-is-a-panel model. A slot stops being a thing the old graft
 * "decoration" overlay world drew and positioned, and becomes just another
 * element you drop into a panel, alongside buttons and labels.
 *
 * <h3>The two halves of a slot</h3>
 *
 * A slot has always had two separable layers:
 * <ul>
 *   <li><b>Registration</b> — the underlying {@link MenuKitSlot} is a real,
 *       server-synced entry in {@code menu.slots} (JEI/EMI see a normal slot).
 *       Built at menu-construction time via {@link MenuKitGraft}; <em>untouched
 *       by this class</em>.</li>
 *   <li><b>Presentation</b> — where it sits on screen, and its frame/item/hover
 *       draw. Historically driven by a per-slot {@code onPrepare} reposition
 *       callback plus {@link MenuKitGraftRender}, both inside the graft overlay
 *       world. <em>This is what {@code SlotElement} takes over.</em></li>
 * </ul>
 *
 * The panel already resolves its own screen origin every frame (region anchors,
 * resize, recipe-book toggle — all handled by {@code ScreenPanelAdapter}). A
 * {@code SlotElement} is a panel-local child, so it rides that origin like any
 * other element: per-screen position becomes a <em>panel</em> property, not a
 * per-slot mechanism. That deletes the duplicate positioning system instead of
 * bridging to it.
 *
 * <h3>Why a slot is a click-through HOLE in its panel</h3>
 *
 * A slot's actual item interaction is owned by vanilla: a click flows through
 * {@code AbstractContainerScreen.mouseClicked} → {@code slotClicked(getHoveredSlot())},
 * and MenuKit's library-owned {@code getHoveredSlot} interception
 * ({@link MenuKitGraftInput}) routes that to the grafted slot — making it win
 * over, and so block, the vanilla slot it covers. So the panel must <em>not</em>
 * eat the click; {@link #isElementOpaque()} returns {@code false} (a hole) so the
 * click reaches vanilla. The "block what's behind" is done by that hover
 * resolution, exactly as grafts have always worked — the panel here owns only
 * position + render. {@link #render} keeps the slot's presentation position
 * current so the hover resolution finds it; {@link SlotElementRegistry} lets the
 * library's screen hook resolve panel-hosted slots even with no
 * {@code GraftScreenPresence} registered.
 */
public final class SlotElement implements PanelElement {

    private final MenuKitSlot slot;
    private final int childX;
    private final int childY;

    /**
     * @param slot   the registered slot this element presents (created by
     *               {@link MenuKitGraft} at menu-build time)
     * @param childX panel-local X (within the panel's content area, after padding)
     * @param childY panel-local Y
     */
    public SlotElement(MenuKitSlot slot, int childX, int childY) {
        this.slot = slot;
        this.childX = childX;
        this.childY = childY;
    }

    /** The registered slot this element presents. */
    public MenuKitSlot slot() { return slot; }

    @Override public int getChildX() { return childX; }
    @Override public int getChildY() { return childY; }
    @Override public int getWidth()  { return SlotRendering.DEFAULT_SIZE; }
    @Override public int getHeight() { return SlotRendering.DEFAULT_SIZE; }

    /**
     * A slot is a click-through hole (see class javadoc): vanilla's slot-click
     * machinery owns the interaction, so the panel must let the click pass
     * rather than eat it. The covered vanilla slot is made inert by the graft
     * {@code getHoveredSlot} resolution, not by panel opacity.
     */
    @Override public boolean isElementOpaque() { return false; }

    /** Hidden when the owning panel is hidden — mirrors the slot's own inertness. */
    @Override public boolean isVisible() { return !slot.isInert(); }

    @Override
    public void render(RenderContext ctx) {
        int screenX = ctx.originX() + childX;
        int screenY = ctx.originY() + childY;

        // Keep the underlying slot's presentation position current, in the graft
        // coordinate space (leftPos-relative), so the library's getHoveredSlot
        // resolution ({@link MenuKitGraftInput}) hit-tests it where the panel
        // actually drew it. This is the per-frame positioning that used to be a
        // per-slot onPrepare callback — now it's just "an element rides its panel".
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof AbstractContainerScreen<?> acs) {
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) acs;
            slot.setGraftPosition(screenX - acc.menuKit$getLeftPos(),
                    screenY - acc.menuKit$getTopPos());
        }

        int size = SlotRendering.DEFAULT_SIZE;
        SlotRendering.drawSlotBackground(ctx.graphics(), screenX, screenY, size, false);
        ItemStack stack = slot.getItem();
        if (!stack.isEmpty()) {
            SlotRendering.drawItem(ctx.graphics(), stack, screenX, screenY, size, false);
        }
        if (ctx.isHovered(childX, childY, size, size)) {
            SlotRendering.drawHoverHighlight(ctx.graphics(), screenX, screenY, size);
        }
    }

    // ── Lifecycle: join/leave the active-slot registry so the screen hook can
    //    resolve hover/click for this panel-hosted slot ──────────────────────

    @Override
    public void onAttach(Screen screen) {
        SlotElementRegistry.add(this);
    }

    @Override
    public void onDetach(Screen screen) {
        SlotElementRegistry.remove(this);
    }
}
