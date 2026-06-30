package com.trevorschoeny.menukit.core;

import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * The reactive slot-wrapping primitive — the slot analogue of element/text
 * reactive sizing (Movement ④). One {@link PanelElement} that owns a panel's
 * registered slots and FLOWS the currently-visible ones into the panel's
 * available content width, wrapping to new rows that grow downward.
 *
 * <h3>Why a flow, not a fixed grid</h3>
 *
 * Historically a {@link MKCContainerPanel}'s slots were baked at fixed
 * {@code (childX, childY)} positions on a consumer-declared column count, and a
 * panel whose groups reveal independently had to pin a wide fixed width to
 * reserve the maximal extent — leaving empty space when only a narrow group was
 * up, and sliding sideways (the "flash") when a wider group revealed over a
 * narrow one near the screen edge. This element replaces that: there is no fixed
 * grid and no pinned width. The visible slots simply flow into whatever width
 * the panel has this frame and the panel hugs the result.
 *
 * <h3>Stable budget, reactive hug</h3>
 *
 * The flow's {@link #naturalWidth()} reports the ALL-slots single-row width — a
 * value that does not depend on which groups are revealed. The owning panel
 * computes {@code contentWidth = min(naturalWidth, ceiling)}; because the natural
 * width is (almost always) larger than the screen-edge ceiling the placement
 * layer imposes, the budget handed to {@link #layoutWithin} is that ceiling — a
 * value that is stable across reveals (it depends on screen geometry, not slot
 * visibility). So the panel never has to re-run its (cached) configuration pass
 * when a group toggles: {@link #getWidth()}, {@link #getHeight()} and
 * {@link #render} recompute the hug layout from the CURRENT visibility every
 * frame against that stable budget. The panel then reports the hug width (so it
 * stays narrow → never trips the screen-edge slide), while the flow internally
 * knows it MAY grow up to the budget before wrapping. Reactive to reveals for
 * free, no empty space, no flash.
 *
 * <h3>Client-render-only (§0047)</h3>
 *
 * The flow positions are pure presentation: each child {@link SlotElement}
 * resolves its live slot by identity and writes its render position every frame,
 * so the real synced slots' identity/sync are untouched — only where they DRAW
 * (and therefore where vanilla hit-tests them) reflows. A slot stays a
 * click-through hole; this element is too ({@link #isElementOpaque()} false).
 */
public final class SlotFlowElement implements PanelElement {

    private static final int PITCH = MKCSlots.SLOT_PITCH;

    /** A small default budget so frame 0 (before the first layoutWithin) reads
     *  as a sane ~9-wide row rather than a single column. Overwritten by the
     *  real screen-edge ceiling on the first configuration pass. */
    private static final int DEFAULT_BUDGET = 9 * PITCH;

    /** All slots this panel owns, in declared order. A group's slots are a
     *  contiguous run; the flow is continuous across groups (group identity is
     *  carried by each slot's name/tooltip, not by a row break), so a revealed
     *  group's slots simply join the stream and wrap with everything else. */
    private final List<SlotElement> slots;

    private final int childX;
    private final int childY;

    /** The width the flow may occupy before wrapping — the panel's stable
     *  screen-edge ceiling. See the class doc. */
    private int budget = DEFAULT_BUDGET;

    public SlotFlowElement(List<SlotElement> slots, int childX, int childY) {
        this.slots = List.copyOf(slots);
        this.childX = childX;
        this.childY = childY;
    }

    @Override public int getChildX() { return childX; }
    @Override public int getChildY() { return childY; }

    /**
     * The panel hands this the content-width budget; we store it as the wrap
     * ceiling. Because {@link #naturalWidth()} reports the (larger) all-slots
     * width, this budget is the screen-edge ceiling — stable across reveals.
     */
    @Override
    public void layoutWithin(int budget) {
        this.budget = Math.max(PITCH, budget);
    }

    /**
     * All-slots single-row width — intentionally visibility-INDEPENDENT, so the
     * panel's {@code min(naturalWidth, ceiling)} resolves to the stable ceiling
     * (see class doc). Never the per-frame hug width — that is {@link #getWidth()}.
     */
    @Override
    public int naturalWidth() {
        return Math.max(PITCH, slots.size() * PITCH);
    }

    /** Columns that fit in the current budget (≥ 1). */
    private int columns() {
        return Math.max(1, budget / PITCH);
    }

    /** Visible slot count this frame (a slot is visible when its group is
     *  revealed and it resolves on the current screen). */
    private int visibleCount() {
        int n = 0;
        for (SlotElement s : slots) if (s.isVisible()) n++;
        return n;
    }

    /** Hug width: the visible slots laid into at most {@code columns()} per row,
     *  hugging the content when fewer than a full row are shown. */
    @Override
    public int getWidth() {
        int visible = visibleCount();
        if (visible == 0) return 0;
        int cols = Math.min(visible, columns());
        return cols * PITCH;
    }

    /** Hug height: one row per {@code columns()} visible slots, growing downward. */
    @Override
    public int getHeight() {
        int visible = visibleCount();
        if (visible == 0) return 0;
        int cols = columns();
        int rows = (visible + cols - 1) / cols;
        return rows * PITCH;
    }

    /** Visible only while at least one child slot is visible — so the owning
     *  panel reserves no space (and draws no frame) when every group is hidden. */
    @Override
    public boolean isVisible() {
        for (SlotElement s : slots) if (s.isVisible()) return true;
        return false;
    }

    /**
     * A slot is a click-through hole owned by vanilla's slot machinery (see
     * {@link SlotElement}); the flow that hosts them is too, so clicks reach the
     * slots vanilla resolves under the cursor rather than being eaten here.
     */
    @Override public boolean isElementOpaque() { return false; }

    @Override
    public void render(RenderContext ctx) {
        int cols = columns();
        int originX = ctx.originX() + childX;
        int originY = ctx.originY() + childY;
        int k = 0;
        for (SlotElement slot : slots) {
            if (!slot.isVisible()) continue;
            int col = k % cols;
            int row = k / cols;
            // Each child renders itself (and writes its real slot's render
            // position, sets its tooltip, draws hover) at a per-cell shifted
            // origin. The child's own childX/childY are 0, so the shifted
            // origin IS its on-screen cell — the flow owns positioning.
            RenderContext cell = new RenderContext(
                    ctx.graphics(),
                    originX + col * PITCH,
                    originY + row * PITCH,
                    ctx.mouseX(), ctx.mouseY());
            slot.render(cell);
            k++;
        }
    }

    // ── Registry lifecycle — forward to children so each registered slot joins
    //    the active-slot registry the screen hook resolves hover/click through ──

    @Override
    public void onAttach(Screen screen) {
        for (SlotElement s : slots) s.onAttach(screen);
    }

    @Override
    public void onDetach(Screen screen) {
        for (SlotElement s : slots) s.onDetach(screen);
    }
}
