package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * A registered MKC slot presented as a {@link PanelElement} — the keystone of
 * the everything-is-a-panel model. A slot stops being a thing the old slot
 * "decoration" overlay world drew and positioned, and becomes just another
 * element you drop into a panel, alongside buttons and labels.
 *
 * <h3>The two halves of a slot</h3>
 *
 * A slot has always had two separable layers:
 * <ul>
 *   <li><b>Registration</b> — a real, server-synced {@link MKCSlot} in
 *       {@code menu.slots} (JEI/EMI see a normal slot). Built at menu-construction
 *       time via {@link MKCSlots}; <em>untouched by this class</em>.</li>
 *   <li><b>Presentation</b> — where it sits on screen, and its frame/item/hover
 *       draw. Historically a per-slot {@code onPrepare} reposition callback plus a
 *       standalone render helper, both inside the slot overlay world. <em>This
 *       is what {@code SlotElement} takes over.</em></li>
 * </ul>
 *
 * The panel already resolves its own screen origin every frame (region anchors,
 * resize, recipe-book toggle — all handled by {@code ScreenPanelAdapter}). A
 * {@code SlotElement} is a panel-local child, so it rides that origin like any
 * other element: per-screen position becomes a <em>panel</em> property, not a
 * per-slot mechanism. That deletes the duplicate positioning system instead of
 * bridging to it.
 *
 * <h3>Located, not held — the menu-attach lifecycle</h3>
 *
 * A {@code SlotElement} does <em>not</em> hold a slot reference; it holds the
 * slot's stable identity ({@code panelId}/{@code groupId}/{@code localIndex}) and
 * resolves the live {@link MKCSlot} from the current screen's menu each frame.
 * This is what lets one statically-registered panel work on every screen: the
 * survival inventory and the creative screen carry <em>different</em> slot
 * instances for the same logical slot (creative wraps it in a {@code SlotWrapper}
 * and projects it onto a throwaway menu), and a held reference would bind to one
 * and be wrong on the other. Resolving by identity each frame — the same thing the
 * old per-frame reposition scan did — is screen-agnostic by construction.
 *
 * <h3>Why a slot is a click-through HOLE in its panel</h3>
 *
 * A slot's actual item interaction is owned by vanilla: a click flows through
 * {@code AbstractContainerScreen.mouseClicked} → {@code slotClicked(getHoveredSlot())},
 * and MenuKit's library-owned {@code getHoveredSlot} interception
 * ({@link MKCSlotInput}) routes that to the registered slot — making it win
 * over, and so block, the vanilla slot it covers. So the panel must <em>not</em>
 * eat the click; {@link #isElementOpaque()} returns {@code false} (a hole) so the
 * click reaches vanilla. The "block what's behind" is done by that hover
 * resolution, exactly as slots have always worked — the panel here owns only
 * position + render. {@link #render} keeps the slot's presentation position
 * current so the hover resolution finds it; {@link SlotElementRegistry} tells the
 * library's screen hook which panels currently host a {@code SlotElement}, so it
 * resolves them through {@link MKCSlotInput}.
 */
public final class SlotElement implements PanelElement {

    private final String panelId;
    private final String groupId;
    private final int localIndex;
    private final int childX;
    private final int childY;

    /**
     * @param panelId    the registered slot's panel id (as given to
     *                   {@link MKCSlots.Builder#panel})
     * @param groupId    the registered slot's group id ({@link MKCSlots.Builder#group})
     * @param localIndex the slot's index within its group (0-based)
     * @param childX     panel-local X (within the panel's content area, after padding)
     * @param childY     panel-local Y
     */
    public SlotElement(String panelId, String groupId, int localIndex,
                       int childX, int childY) {
        this.panelId = panelId;
        this.groupId = groupId;
        this.localIndex = localIndex;
        this.childX = childX;
        this.childY = childY;
    }

    /** The registered slot's panel id this element presents (its registry key). */
    public String panelId() { return panelId; }

    @Override public int getChildX() { return childX; }
    @Override public int getChildY() { return childY; }
    @Override public int getWidth()  { return SlotRendering.DEFAULT_SIZE; }
    @Override public int getHeight() { return SlotRendering.DEFAULT_SIZE; }

    /**
     * A slot is a click-through hole (see class javadoc): vanilla's slot-click
     * machinery owns the interaction, so the panel must let the click pass
     * rather than eat it. The covered vanilla slot is made inert by the slot
     * {@code getHoveredSlot} resolution, not by panel opacity.
     */
    @Override public boolean isElementOpaque() { return false; }

    /** Hidden when the slot can't be resolved on this screen, or its panel is hidden. */
    @Override
    public boolean isVisible() {
        MKCSlot slot = resolve();
        return slot != null && !slot.isInert();
    }

    @Override
    public void render(RenderContext ctx) {
        MKCSlot slot = resolve();
        if (slot == null || slot.isInert()) return;

        int screenX = ctx.originX() + childX;
        int screenY = ctx.originY() + childY;

        // Keep the slot's presentation position current, in the slot coordinate
        // space (leftPos-relative), so the library's getHoveredSlot resolution
        // ({@link MKCSlotInput}) hit-tests it where the panel actually drew
        // it. This is the per-frame positioning that used to be an onPrepare
        // callback — now it's just "an element rides its panel".
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof AbstractContainerScreen<?> acs) {
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) acs;
            slot.setRenderPosition(screenX - acc.mk$getLeftPos(),
                    screenY - acc.mk$getTopPos());
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

    /**
     * Resolves the live {@link MKCSlot} for this element's identity from the
     * current screen's menu (unwrapping a creative {@code SlotWrapper} via
     * {@link Slots#asMKCSlot}). Returns {@code null} when the screen isn't a
     * container screen or the slot isn't present (e.g. a screen the slot doesn't
     * apply to).
     */
    private @Nullable MKCSlot resolve() {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return null;
        AbstractContainerMenu menu = acs.getMenu();
        for (Slot slot : menu.slots) {
            MKCSlot mk = MKCSlotAccess.asMKCSlot(slot);
            if (mk == null) continue;
            if (mk.getLocalIndex() == localIndex
                    && groupId.equals(mk.getGroupId())
                    && panelId.equals(mk.getPanelId())) {
                return mk;
            }
        }
        return null;
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
