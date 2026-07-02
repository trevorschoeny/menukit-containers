package com.trevorschoeny.menukit.screen;

import com.trevorschoeny.menukit.core.*;
import com.trevorschoeny.menukit.mixin.SlotPositionAccessor;
import com.trevorschoeny.menukit.core.PanelRendering;
import com.trevorschoeny.menukit.window.ClientWindowVisibility;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Client-side partner to {@link MKCScreenHandler}. Owns layout computation,
 * panel background rendering, slot positioning, and hover detection.
 *
 * <p>This is MenuKit's own screen base class for screens it builds.
 * All rendering and layout state is per-screen-instance — no global statics.
 *
 * <p>Renders in vanilla's pipeline:
 * <ol>
 *   <li>{@code renderBg()} — panel backgrounds + slot backgrounds (screen space)</li>
 *   <li>Vanilla's {@code renderSlotHighlightBack()} — hover highlight back layer</li>
 *   <li>Vanilla's {@code renderSlots()} — item rendering (container-relative)</li>
 *   <li>Vanilla's {@code renderSlotHighlightFront()} — hover highlight front layer</li>
 *   <li>{@code renderLabels()} — title and inventory label text</li>
 *   <li>{@code renderTooltip()} — item tooltip at mouse position</li>
 * </ol>
 */
public class MKCHandledScreen extends AbstractContainerScreen<MKCScreenHandler> {

    private static final Logger LOGGER = LoggerFactory.getLogger("MenuKit");

    // ── Layout Constants ───────────────────────────────────────────────
    /**
     * Padding inside each styled panel (pixels from panel edge to slot
     * backgrounds / elements). Phase 18r — actual padding applied is
     * style-conditional via {@link Panel#interiorPadding()}: this value for
     * styled panels (RAISED / DARK / INSET), {@code 0} for
     * {@link PanelStyle#NONE}. Constant retained for documentation only.
     */
    private static final int PANEL_PADDING = 7;
    /** Size of one slot cell (18x18: 16px item + 1px border each side). */
    private static final int SLOT_SIZE = 18;
    /** Vertical gap between body panels. Matches vanilla container-to-inventory gap. */
    private static final int BODY_GAP = 14;
    /** Gap between a relative panel and its anchor. */
    private static final int RELATIVE_GAP = 4;
    /** Vertical space reserved above the first panel for the title label. */
    private static final int TITLE_HEIGHT = 14;

    // ── Per-Instance Layout State ──────────────────────────────────────
    // Recomputed each frame from the panel tree. No global statics.

    /** Panel ID → computed layout-local bounds. Rebuilt each frame. */
    private Map<String, PanelBounds> panelBounds = new LinkedHashMap<>();

    // ── Phase 16h layout origin for centering correction ───────────────
    // The leftmost / topmost layout-local coordinate across all visible
    // panels. Captured during computeLayout, consumed by the
    // post-super.init() leftPos/topPos correction in {@link #recenter}
    // — which adjusts the screen origin so the layout's geometric center
    // lands at screen center, even when relative panels (leftOf/above
    // anchors) produce negative layout-local coords.
    //
    // Mirrors MKScreen's centering formula:
    //   leftPos = (width - imageWidth) / 2 - layoutOriginX
    //   topPos  = (height - imageHeight) / 2 - layoutOriginY
    //
    // Pre-16h MKC used vanilla AbstractContainerScreen's centering, which
    // assumes layout starts at (0,0) — broke for V5's leftOf chain. The
    // 16h fix is symmetric on both axes, matches MK's existing pattern,
    // and doesn't mutate PanelLayout's output (the prior bounds-shift
    // workaround did, which coupled layout-output to centering math).
    private int layoutOriginX = 0;
    private int layoutOriginY = 0;

    // ── Hover Tracking ─────────────────────────────────────────────────
    // Tracks the previously hovered MKCSlot to fire enter/exit events.
    // Also exposes current hover state as a queryable property for consumers.
    private @Nullable MKCSlot previouslyHoveredMkSlot = null;

    /**
     * Returns the currently hovered MKCSlot, or null if the mouse
     * isn't over a MenuKit slot. Synchronous query — consumers like HUD
     * overlays can read this each frame without subscribing to events.
     */
    public @Nullable MKCSlot getHoveredMKCSlot() {
        return previouslyHoveredMkSlot;
    }

    /**
     * Returns the SlotGroup of the currently hovered MKCSlot, or
     * null. Convenience for consumers that care about group identity.
     */
    public @Nullable SlotGroup getHoveredGroup() {
        return previouslyHoveredMkSlot != null
                ? previouslyHoveredMkSlot.getGroup() : null;
    }

    /**
     * Captures a "return to this container" action for the currently-displayed
     * MKC container screen, or {@code null} if the current screen is not one.
     *
     * <p>Use when opening a transient client {@link net.minecraft.client.gui.screens.Screen}
     * OVER a live MKC container: pass the captured action to
     * {@code MKScreen.setReturnAction} so the transient screen's Back/Escape
     * RE-OPENS the container. Re-open (not a client-only screen restore) is
     * required because vanilla closes the server-synced menu when you
     * {@code setScreen} away from a container — so coming back means re-issuing
     * the open request, which is exactly what the returned {@link Runnable}
     * does (it resolves this menu's {@link MKCMenu} handle and calls
     * {@link MKCMenu#requestOpen()}).
     *
     * <p>Returns {@code null} when there is nothing to return to (not in an MKC
     * container, or the menu's type doesn't resolve to a registered handle), so
     * callers fall back to their default navigation. Honest limit: this only
     * restores MKC-backed containers — a vanilla container can't be cleanly
     * re-opened client-side after the close packet.
     */
    public static @Nullable Runnable captureReturnAction() {
        net.minecraft.client.gui.screens.Screen current =
                net.minecraft.client.Minecraft.getInstance().gui.screen();
        if (!(current instanceof MKCHandledScreen mkc)) return null;
        net.minecraft.world.inventory.MenuType<?> type;
        try {
            type = mkc.getMenu().getType();
        } catch (UnsupportedOperationException e) {
            return null;  // a menu with no registered type (e.g. the inventory menu)
        }
        net.minecraft.resources.Identifier id =
                net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(type);
        if (id == null) return null;
        MKCMenu handle = MKCMenu.byId(id);
        if (handle == null) return null;
        return handle::requestOpen;
    }

    // ── Construction ───────────────────────────────────────────────────

    // ── MKC-owned image dimensions (26.2 migration) ───────────────────
    // Vanilla made imageWidth/imageHeight FINAL at 26.x (dims are a
    // construction-time contract now). MKC's reactive layout reassigns
    // them per-frame, so MKC keeps its own pair. This is safe because
    // MKC never relied on vanilla's consumers of those fields anyway:
    // recenter() computes leftPos/topPos itself (overwriting
    // super.init()'s centering), and hasClickedOutside is overridden.
    // Every layout read/write below goes through these fields.
    private int mkcImageWidth  = 176;
    private int mkcImageHeight = 100;

    public MKCHandledScreen(MKCScreenHandler handler, Inventory inventory,
                                Component title) {
        super(handler, inventory, title);
        // Compute initial layout to set mkcImageWidth/mkcImageHeight before
        // init() runs (recenter() centers from them; super.init()'s own
        // centering from the final vanilla dims is overwritten there).
        computeLayout();
    }

    @Override
    protected void init() {
        // Recompute layout — screen may have resized, or it's the
        // first init after construction.
        computeLayout();
        positionSlots();
        super.init(); // sets leftPos = (width - imageWidth) / 2, etc.
        recenter();   // Phase 16h — apply -layoutOriginX/Y correction

        // Build the key dispatch table from the panel tree.
        // Cleared and rebuilt on each init (handles screen resize re-init).
        keyRegistry.clear();
        registerPanelToggleKeys();

        // Phase 14d-3 — fire onAttach on each panel element so widget-
        // wrapping elements (TextField etc.) can register vanilla
        // widgets via addRenderableWidget.
        for (Panel panel : menu.getPanels()) {
            for (PanelElement element : panel.getElements()) {
                element.onAttach(this);
            }
        }
    }

    @Override
    public void removed() {
        // Phase 14d-3 — fire onDetach so widget-wrapping elements can
        // unregister via screen.removeWidget.
        for (Panel panel : menu.getPanels()) {
            for (PanelElement element : panel.getElements()) {
                element.onDetach(this);
            }
        }
        super.removed();
    }

    // ── Layout Computation ─────────────────────────────────────────────
    // Runs each frame. Cheap (a few additions per panel). Handles
    // visibility toggles instantly without a separate invalidation step.

    /**
     * Computes the pixel size of a panel, factoring in both its slot groups
     * and its elements so the visible background fully contains everything
     * the panel renders.
     *
     * <p>Width = max(widest slot group * SLOT_SIZE, rightmost element edge) + 2 * padding.
     * Height = max(total slot rows * SLOT_SIZE + row gaps, bottommost element edge) + 2 * padding.
     *
     * <p>Element bounds contribute so that panels with only elements (no slot
     * groups) render at a meaningful size — not just the 2×padding default
     * that the slot-only computation would produce.
     *
     * <p><b>Phase 16h pinned-dim fix:</b> if the panel declares a pinned
     * width via {@link Panel#size(int,int)} / {@link Panel#pinnedWidth(int)},
     * that pinned content extent overrides the slot+element max for that
     * axis (consumer set a hard budget). Same for pinned height. This
     * mirrors the MK-side {@code MKScreen.computePanelSize} fix from
     * 16g and is what makes Panel auto-wrap + auto-scroll fire on MKC
     * panels too — without it, MKC panels with pinned dims would still
     * be sized by the slot+element math, ignoring the consumer's pin.
     */
    private int[] computePanelSize(Panel panel) {
        // PURE MEASURE — the reactive-sizing budget is fed by the layout DRIVER
        // per the panel's ROLE (MainRegionLayout: centred for the MAIN frame,
        // anchor-aware for region siblings via the shared engine; the legacy path:
        // computePanelSizeCentered). Feeding a flat budget here would clobber the
        // driver's anchor-aware feed (last write wins) — the resize-engine split
        // that made region siblings overlap. NOTE: slot grids (maxCols*SLOT_SIZE)
        // are author-fixed and are NOT reflowed by the width budget — wrapping a
        // slot grid is the separate SlotFlowElement primitive; custom-screen slot
        // grids are authored to fit.
        int maxCols = 0;
        int totalSlotHeight = 0;

        for (SlotGroup group : menu.getGroupsFor(panel.getId())) {
            int cols = group.getColumns();
            maxCols = Math.max(maxCols, cols);

            int rows = (group.getStorage().size() + cols - 1) / cols; // ceiling division
            totalSlotHeight += rows * SLOT_SIZE;

            // Add row gap if specified and the gap row exists
            if (group.getRowGapAfter() >= 0 && group.getRowGapAfter() < rows - 1) {
                totalSlotHeight += group.getRowGapSize();
            }
        }

        int slotContentWidth = maxCols * SLOT_SIZE;
        int slotContentHeight = totalSlotHeight;

        // Factor in elements so the panel grows to contain them. Element
        // child-coordinates are relative to the content area (inside the
        // padding), so the rightmost / bottommost edge is childX + width
        // and childY + height respectively.
        int elementContentWidth = 0;
        int elementContentHeight = 0;
        for (PanelElement element : panel.getElements()) {
            elementContentWidth  = Math.max(elementContentWidth,  element.getChildX() + element.getWidth());
            elementContentHeight = Math.max(elementContentHeight, element.getChildY() + element.getHeight());
        }

        // Phase 16h — honor pinned dims when set; fall back to slot+element
        // max otherwise. Consumer's explicit pin wins over the auto-sized
        // computation (matches MK-side behavior + the existing M5 contract).
        int autoContentWidth  = Math.max(slotContentWidth,  elementContentWidth);
        int autoContentHeight = Math.max(slotContentHeight, elementContentHeight);

        int pinnedW = panel.getPinnedWidth();
        int pinnedH = panel.getPinnedHeight();
        int contentWidth  = (pinnedW >= 0) ? pinnedW : autoContentWidth;
        int contentHeight = (pinnedH >= 0) ? pinnedH : autoContentHeight;

        // Phase 18r — padding is style-conditional via Panel.interiorPadding()
        // (0 for PanelStyle.NONE, PANEL_PADDING otherwise) so NONE panels
        // report element edge = panel edge.
        int padding = panel.interiorPadding();
        int width  = contentWidth  + 2 * padding;
        int height = contentHeight + 2 * padding;
        return new int[]{width, height};
    }

    /**
     * The legacy BODY-stack size function: feed the centred-screen width budget
     * BEFORE the pure {@link #computePanelSize} measure, so a body-stacked panel's
     * element text wraps to the screen edge. The main path
     * ({@link MainRegionLayout}) feeds per role itself (centred for the MAIN frame,
     * anchor-aware for region siblings), so it calls the pure measure directly;
     * only the legacy path needs this centred wrapper.
     */
    private int[] computePanelSizeCentered(Panel panel) {
        panel.setAvailableContentWidth(
                this.width - 2 * RegionConstants.SCREEN_EDGE_MARGIN
                        - 2 * panel.interiorPadding());
        return computePanelSize(panel);
    }

    /**
     * Computes panel positions and updates imageWidth/imageHeight.
     *
     * <p>Two-tier layout:
     * <ol>
     *   <li>Body panels stack vertically in the main column.</li>
     *   <li>Relative panels offset from their named anchor panel.</li>
     * </ol>
     *
     * <p>imageWidth/imageHeight cover the body column only. Relative
     * panels may extend beyond those bounds — {@code hasClickedOutside}
     * is overridden to include them.
     */
    private void computeLayout() {
        java.util.List<Panel> panels = menu.getPanels();
        if (MainRegionLayout.hasMain(panels)) {
            // Movement ③ — the screen names a MAIN panel = its frame; every other
            // panel anchors to it via MenuRegion through the SAME RegionMath path
            // vanilla-injected panels take against the menu frame. The bounds are
            // leftPos-relative (main at 0,0); imageWidth/Height = the main frame,
            // and recenter() recomputes the SAME leftPos/topPos from them (origin
            // 0), so the screen centres on the main panel and slots/labels add
            // leftPos/topPos exactly as a vanilla container does.
            // Auto-fit the MAIN frame's height ONLY when it holds no slots. A
            // slot-bearing main can't scroll — its vanilla slots are positioned in
            // absolute coords by positionSlots() and have no scroll hook, so
            // scrolling the element layer would desync them. A pure-element container
            // main (no slot groups) is safe to auto-scroll, exactly like a standalone
            // MKScreen main.
            boolean autoFitMain = mainPanelHoldsNoSlots(panels);
            var layout = MainRegionLayout.resolve(
                    panels, this::computePanelSize, this.width, this.height,
                    /*reserveTitle=*/ true, autoFitMain);
            panelBounds = layout.bounds();
            mkcImageWidth  = layout.mainW();
            mkcImageHeight = layout.mainH();
            this.layoutOriginX = 0;
            this.layoutOriginY = 0;
        } else {
            // Legacy BODY-stack layout (no designated main panel). Min image dims
            // (176×100) are vanilla's container-screen minimums; PanelTreeLayout
            // clamps totalWidth/Height to those so vanilla's centering math doesn't
            // underflow. layoutOriginX/Y feed the post-super.init recenter().
            var layout = PanelTreeLayout.resolve(
                    panels, this::computePanelSizeCentered,
                    BODY_GAP, RELATIVE_GAP, TITLE_HEIGHT,
                    /*minImageWidth=*/ 176, /*minImageHeight=*/ 100);
            panelBounds = layout.bounds();
            this.layoutOriginX = layout.layoutOriginX();
            this.layoutOriginY = layout.layoutOriginY();
            mkcImageWidth  = layout.totalWidth();
            mkcImageHeight = layout.totalHeight();
        }

        // Position the "Inventory" label relative to the player panel.
        // Vanilla renders inventoryLabel at screen-coords (leftPos +
        // inventoryLabelX, topPos + inventoryLabelY). After recenter()
        // applies the -layoutOriginX/Y correction, the screen position
        // of any layout-local point (lx, ly) is (leftPos + lx, topPos +
        // ly). So to land the label at "8px right of player panel left
        // edge" and "11px above player panel top edge" — the standard
        // vanilla offsets — set the label coords to the layout-local
        // coords of those positions:
        //
        //   inventoryLabelX = playerBounds.x() + 8
        //   inventoryLabelY = playerBounds.y() - 11
        //
        // Phase 16h bug fix: previously this formula incorrectly
        // subtracted layoutOriginY from inventoryLabelY (double-applied
        // the topPos correction since recenter() already accounts for
        // it via the topPos shift). That bug pushed the label upward by
        // |layoutOriginY| pixels — for V5 with layoutOriginY=14, the
        // label landed 14px too high, overlapping the BODY panel above
        // the player panel.
        //
        // Phase 16h follow-up: with PanelLayout now center-aligning BODY
        // panels around x=0, playerBounds.x() is no longer 0 — it's
        // -playerWidth/2. So inventoryLabelX must reflect that to keep
        // the label aligned with the player panel's left edge (vanilla's
        // hardcoded 8 would point past the panel). Both axes use the
        // same playerBounds-relative formula now.
        PanelBounds playerBounds = panelBounds.get("player");
        if (playerBounds != null) {
            this.inventoryLabelX = playerBounds.x() + 8;
            this.inventoryLabelY = playerBounds.y() - 11;
        }
    }

    /**
     * True when the screen's MAIN panel (the frame) holds no slot groups — the gate
     * for auto-fitting the main's height. A slot-bearing main must keep its natural
     * height because its vanilla slots are positioned in absolute coords by
     * {@link #positionSlots} and have no scroll hook; auto-scrolling it would desync
     * the slots from the scrolled element layer. A pure-element container main (no
     * slot groups) is safe to auto-scroll, exactly like a standalone MKScreen main.
     * Returns false when there is no MAIN panel (the legacy BODY-stack path, which
     * never auto-fits the main anyway).
     */
    private boolean mainPanelHoldsNoSlots(java.util.List<Panel> panels) {
        for (Panel p : panels) {
            if (p.getPosition().mode() == PanelPosition.Mode.MAIN) {
                return menu.getGroupsFor(p.getId()).isEmpty();
            }
        }
        return false;
    }

    /**
     * Applies the centering correction to {@code leftPos}/{@code topPos}
     * so the layout's geometric center lands at screen center even when
     * relative panels produce negative layout-local coords.
     *
     * <p>Formula matches MKScreen.computeLayout:
     * <pre>
     *   leftPos = (width - imageWidth)  / 2 - layoutOriginX
     *   topPos  = (height - imageHeight) / 2 - layoutOriginY
     * </pre>
     *
     * <p>Vanilla AbstractContainerScreen.init sets the unadjusted form;
     * we apply the {@code -layoutOriginX/Y} term after vanilla's init
     * and re-apply per-frame in renderBg (since computeLayout may change
     * imageWidth/Height + layoutOrigin if panel visibility toggles
     * mid-game).
     */
    private void recenter() {
        this.leftPos = (this.width  - this.mkcImageWidth)  / 2 - this.layoutOriginX;
        this.topPos  = (this.height - this.mkcImageHeight) / 2 - this.layoutOriginY;
    }

    /**
     * Sets each slot's x/y from its panel position and grid layout.
     *
     * <p>Slot.x and Slot.y are in container-relative space (the render
     * system adds leftPos/topPos). Hidden slots go off-screen so vanilla
     * skips them entirely.
     */
    private void positionSlots() {
        for (Panel panel : menu.getPanels()) {
            PanelBounds bounds = panelBounds.get(panel.getId());

            // Track running Y offset for multiple groups within a panel
            int groupOffsetY = 0;

            for (SlotGroup group : menu.getGroupsFor(panel.getId())) {
                int cols = group.getColumns();
                int rows = (group.getStorage().size() + cols - 1) / cols;

                for (int s = group.getFlatIndexStart(); s < group.getFlatIndexEnd(); s++) {
                    int localIdx = s - group.getFlatIndexStart();
                    int col = localIdx % cols;
                    int row = localIdx / cols;

                    // Row gap: extra Y pixels for slots below the gap row
                    int extraY = (group.getRowGapAfter() >= 0
                            && row > group.getRowGapAfter())
                            ? group.getRowGapSize() : 0;

                    Slot slot = menu.slots.get(s);
                    // Client render positioning only — a window-hidden panel parks
                    // its slots offscreen so vanilla doesn't draw them. The slot's
                    // CONTENT keeps syncing server-side (MKCSlot.isInert reads the
                    // panel's raw visibility, not the engine — sidedness preserved).
                    if (bounds != null && ClientWindowVisibility.panelShown(panel)) {
                        // +1 offset: slot.x/y point to the 16x16 item area,
                        // which starts 1px inside the 18x18 slot background.
                        // Phase 18r — padding is style-conditional per
                        // Panel.interiorPadding().
                        int padding = panel.interiorPadding();
                        ((SlotPositionAccessor) slot).mk$setX(
                                bounds.x() + padding + col * SLOT_SIZE + 1);
                        ((SlotPositionAccessor) slot).mk$setY(
                                bounds.y() + padding + groupOffsetY
                                        + row * SLOT_SIZE + extraY + 1);
                    } else {
                        // Hidden — move off screen so vanilla ignores them
                        ((SlotPositionAccessor) slot).mk$setX(-9999);
                        ((SlotPositionAccessor) slot).mk$setY(-9999);
                    }
                }

                // Advance group offset for the next group in this panel
                int groupGapExtra = 0;
                if (group.getRowGapAfter() >= 0 && group.getRowGapAfter() < rows - 1) {
                    groupGapExtra = group.getRowGapSize();
                }
                groupOffsetY += rows * SLOT_SIZE + groupGapExtra;
            }
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────

    /**
     * Renders panel backgrounds and slot backgrounds in screen space.
     *
     * <p>26.2: vanilla removed the {@code renderBg} hook — background
     * drawing has no protected seam anymore. The equivalent ordering seam
     * is the head of {@code extractContents}: vanilla's extractContents
     * runs widgets (Screen.extractRenderState) → labels → slot highlight
     * back → slots → highlight front, so drawing MKC's backgrounds before
     * {@code super.extractContents} reproduces the old renderBg-before-
     * everything order exactly. Recomputes layout each frame so
     * visibility toggles take effect immediately.
     */
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Recompute layout each frame — cheap, handles visibility toggles
        computeLayout();
        positionSlots();
        recenter(); // Phase 16h — re-apply centering after layout extent
                    // may have changed (visibility toggle, panel resize)

        // ── Panel backgrounds (screen space) ───────────────────────────
        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            PanelBounds bounds = panelBounds.get(panel.getId());
            if (bounds == null) continue;

            PanelRendering.renderPanel(graphics,
                    leftPos + bounds.x(), topPos + bounds.y(),
                    bounds.width(), bounds.height(),
                    panel.getStyle());
        }

        // ── Slot backgrounds (screen space) ────────────────────────────
        // 18x18 inset squares drawn at slot.x - 1, slot.y - 1 (the slot
        // background wraps around the 16x16 item area).
        for (Slot slot : menu.slots) {
            if (!slot.isActive()) continue;
            PanelRendering.renderSlotBackground(graphics,
                    leftPos + slot.x - 1, topPos + slot.y - 1);
        }

        // ── Panel elements (screen space) ─────────────────────────────
        // Buttons, text labels, and custom elements positioned within
        // each panel's content area (after PANEL_PADDING).
        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            PanelBounds bounds = panelBounds.get(panel.getId());
            if (bounds == null) continue;

            int padding = panel.interiorPadding();
            int contentX = leftPos + bounds.x() + padding;
            int contentY = topPos + bounds.y() + padding;
            RenderContext ctx = new RenderContext(graphics, contentX, contentY, mouseX, mouseY);
            PanelDispatch.renderElements(panel, ctx);

            // Panel-level tooltip — fires over the panel's outer bounds.
            // Queued AFTER element render (last-call-wins for
            // setTooltipForNextFrame); panel tooltip takes precedence over
            // child tooltips when both are configured + cursor overlaps.
            panel.maybeQueueTooltip(graphics,
                    leftPos + bounds.x(), topPos + bounds.y(),
                    bounds.width(), bounds.height(),
                    mouseX, mouseY, ctx.hasMouseInput());
        }

        // ── Vanilla content pass ───────────────────────────────────────
        // Widgets, labels, slot highlights, slots — after MKC's
        // backgrounds, exactly where the old renderBg → super.render
        // boundary sat.
        super.extractContents(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // ── Hover tracking (fire enter/exit events) ────────────────────
        // After the super pass, hoveredSlot is set by vanilla's pipeline.
        // Compare with previous frame to detect enter/exit transitions.
        MKCSlot currentHovered = (this.hoveredSlot instanceof MKCSlot mk) ? mk : null;
        if (currentHovered != previouslyHoveredMkSlot) {
            if (previouslyHoveredMkSlot != null) {
                fireSlotHoverExit(previouslyHoveredMkSlot);
            }
            if (currentHovered != null) {
                fireSlotHoverEnter(currentHovered);
            }
            previouslyHoveredMkSlot = currentHovered;
        }

        // 26.2: no manual tooltip call — vanilla's extractRenderState
        // already runs extractTooltip (the old manual renderTooltip
        // convention is gone with the extract/draw split).
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 1.21.11 requires explicit ARGB colors — drawString silently
        // discards text when alpha is 0 (ARGB.alpha(color) != 0 guard).
        // White text with shadow for readability on the dark overlay.
        // When labels move into panel backgrounds (panel elements), switch
        // to 0xFF404040 (vanilla's dark gray on light backgrounds).
        graphics.text(this.font, this.title,
                this.titleLabelX, this.titleLabelY, 0xFFFFFFFF, true);
        // Only draw the vanilla "Inventory" label when the handler actually
        // has a player-inventory panel. Screens that deliberately omit the
        // player panel (e.g., the element-demo screen) shouldn't display
        // a floating label for content they don't render.
        if (panelBounds.get("player") != null) {
            graphics.text(this.font, this.playerInventoryTitle,
                    this.inventoryLabelX, this.inventoryLabelY, 0xFFFFFFFF, true);
        }
    }

    // ── Panel Visibility (public API) ──────────────────────────────────
    // Entry point for consumers to toggle panel visibility from anywhere
    // (HUD buttons, chat commands, game events). Routes through the
    // same C2S sync mechanism as the keybind path.

    /**
     * Toggles a panel's visibility with full client+server sync.
     *
     * <p>Consumers can call this from any context (HUD button click,
     * chat command, game event) — not just from keybinds. Routes through
     * the same {@code clickMenuButton} C2S mechanism.
     */
    public void togglePanel(String panelId) {
        int buttonId = this.menu.getPanelButtonId(panelId);
        if (buttonId != MKCScreenHandler.PANEL_NOT_FOUND && this.minecraft != null
                && this.minecraft.gameMode != null
                && this.minecraft.player != null) {
            this.menu.clickMenuButton(this.minecraft.player, buttonId);
            this.minecraft.gameMode.handleInventoryButtonClick(
                    this.menu.containerId, buttonId);
            // Fire event
            Panel panel = this.menu.getPanel(panelId);
            if (panel != null) {
                firePanelToggle(panel, panel.isVisible());
            }
        }
    }

    // ── Key Registry ──────────────────────────────────────────────────
    // Lookup-based dispatch — not a hardcoded switch. Panel toggle keys
    // are auto-registered from the panel tree. Custom actions can be
    // added via registerKey(). Task 4's event bus can hook into this.

    /** Action triggered by a key press while this screen is open. */
    @FunctionalInterface
    public interface KeyAction {
        boolean onKey(KeyEvent event, MKCHandledScreen screen);
    }

    private final Map<Integer, KeyAction> keyRegistry = new LinkedHashMap<>();

    /** Registers a key action. GLFW key code → action. */
    public void registerKey(int glfwKey, KeyAction action) {
        keyRegistry.put(glfwKey, action);
    }

    /**
     * Auto-registers panel toggle keys from the panel tree.
     * Called during init(). Each panel with a toggleKey gets a
     * registry entry that syncs visibility via the C2S mechanism.
     */
    private void registerPanelToggleKeys() {
        for (Panel panel : menu.getPanels()) {
            if (panel.getToggleKey() < 0) continue;
            String panelId = panel.getId();
            registerKey(panel.getToggleKey(), (event, screen) -> {
                screen.togglePanel(panelId);
                return true;
            });
        }
    }

    // ── Screen Event Bus ─────────────────────────────────────────────
    // Per-screen-instance event system. MenuKit-scoped — fires events
    // about things happening inside this MenuKit screen. Consumers
    // wanting ecosystem-wide slot events use Fabric's event API.
    //
    // Typed callbacks, not generic Object... varargs. Each method has
    // a default no-op so listeners only override what they care about.

    /**
     * Listener for events happening within a MenuKit screen.
     * All methods have default no-ops — override only what you need.
     *
     * <p>These callbacks carry {@link MKCSlot} — the library's own slot handle for
     * a created slot, not a raw vanilla {@code Slot}. It is the right handle for
     * own-slot event callbacks: it carries {@link MKCSlot#address()} (the slot's
     * {@code Address} for the address world) plus {@code getGroupId()} /
     * {@code getLocalIndex()} for display.
     */
    public interface ScreenEventListener {
        /** A MKCSlot was clicked. Button: 0=left, 1=right, 2=middle. */
        default void onSlotClick(MKCSlot slot, int button) {}
        /** An empty MKCSlot was clicked with an empty cursor. */
        default void onEmptySlotClick(MKCSlot slot, int button) {}
        /** Mouse entered a MKCSlot's hover area. */
        default void onSlotHoverEnter(MKCSlot slot) {}
        /** Mouse left a MKCSlot's hover area. */
        default void onSlotHoverExit(MKCSlot slot) {}
        /** A panel's visibility changed. */
        default void onPanelToggle(Panel panel, boolean nowVisible) {}
        /**
         * A shift-click (quick move) is about to happen on a MKCSlot.
         * Fires BEFORE vanilla routes the items — destination is unknown
         * at this point. The movedStack is a copy of what's in the source.
         *
         * <p><b>Scope:</b> shift-click only. Other item-movement paths
         * (drag-collect, double-click collect, hopper insertion, cursor
         * placement, creative middle-click) do NOT fire this event — the
         * name intentionally matches vanilla's {@code quickMoveStack}.
         * For those paths, observe the relevant ecosystem hooks directly.
         *
         * <p>Use for logging, analytics, or pre-transfer checks. For
         * post-routing observation (where items ended up), a future
         * onAfterQuickMove event would fire from quickMoveStack.
         */
        default void onQuickMove(MKCSlot sourceSlot,
                                 net.minecraft.world.item.ItemStack movedStack) {}
    }

    private final List<ScreenEventListener> eventListeners = new ArrayList<>();

    /** Registers a screen event listener. Cleared on screen close. */
    public void addEventListener(ScreenEventListener listener) {
        eventListeners.add(listener);
    }

    private void fireSlotClick(MKCSlot slot, int button) {
        for (var listener : eventListeners) listener.onSlotClick(slot, button);
    }

    private void fireEmptySlotClick(MKCSlot slot, int button) {
        for (var listener : eventListeners) listener.onEmptySlotClick(slot, button);
    }

    private void fireSlotHoverEnter(MKCSlot slot) {
        for (var listener : eventListeners) listener.onSlotHoverEnter(slot);
    }

    private void fireSlotHoverExit(MKCSlot slot) {
        for (var listener : eventListeners) listener.onSlotHoverExit(slot);
    }

    private void firePanelToggle(Panel panel, boolean nowVisible) {
        for (var listener : eventListeners) listener.onPanelToggle(panel, nowVisible);
    }

    private void fireQuickMove(MKCSlot sourceSlot, net.minecraft.world.item.ItemStack movedStack) {
        for (var listener : eventListeners) listener.onQuickMove(sourceSlot, movedStack);
    }

    // ── Drag Modes ────────────────────────────────────────────────────
    // Per-screen drag mode registry. When a mouse drag starts on a
    // MKCSlot, the registry is checked for a mode that accepts
    // the drag. The active mode receives drag/end callbacks.

    /**
     * A custom drag behavior for MenuKit slots. The screen dispatches
     * drag events to the active mode. Only one mode is active at a time.
     */
    public interface DragMode {
        /**
         * Called when a drag starts on a MKCSlot.
         * Return true to claim the drag (subsequent drag/end go to this mode).
         */
        boolean onDragStart(MKCSlot slot, int button);
        /** Called when the mouse moves over a new slot during a drag. */
        void onDrag(MKCSlot slot);
        /** Called when the mouse button is released, ending the drag. */
        void onDragEnd();
    }

    private final List<DragMode> dragModes = new ArrayList<>();
    private @Nullable DragMode activeDrag = null;

    /** Registers a drag mode. Checked in order when a drag starts. */
    public void registerDragMode(DragMode mode) {
        dragModes.add(mode);
    }

    // ── Input ──────────────────────────────────────────────────────────

    /**
     * Expands "inside" detection to include all visible panel bounds.
     *
     * <p>Vanilla clips to imageWidth×imageHeight, which only covers
     * the body column. Relative panels (positioned to the right, left,
     * etc.) would be treated as "outside" — causing item drops instead
     * of slot interactions. This override checks all panel bounds.
     */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY,
                                        int leftPos, int topPos) {
        for (PanelBounds bounds : panelBounds.values()) {
            double relX = mouseX - leftPos;
            double relY = mouseY - topPos;
            if (relX >= bounds.x() && relX < bounds.x() + bounds.width()
                    && relY >= bounds.y() && relY < bounds.y() + bounds.height()) {
                return false; // inside a panel — not outside
            }
        }
        return super.hasClickedOutside(mouseX, mouseY, leftPos, topPos);
    }

    /**
     * Key dispatch — checks the registry first, then falls through
     * to vanilla's keybind handling.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        KeyAction action = keyRegistry.get(event.key());
        if (action != null && action.onKey(event, this)) {
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * Click dispatch — fires events, checks group handlers, then
     * falls through to vanilla.
     *
     * <p>Order: element click → drag mode → right-click handler → event fire → vanilla.
     * Elements consume clicks first so buttons don't accidentally trigger
     * slot interactions or drag modes underneath them.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean flag) {
        // Dismiss-on-outside-click janitor — notify every visible element whose
        // transient overlay/trigger the click fell OUTSIDE of, so open popovers
        // (Dropdown/DropdownMulti) close when you click away, even if a slot or
        // another element consumes the click. Self-guarding, so safe on every
        // click. Mirrors MKScreen / the vanilla-screen adapter (one rule, every
        // render context).
        notifyOutsideClickDismiss(event.x(), event.y());

        // Element click — buttons and custom elements get first crack.
        // A button returning false from mouseClicked lets the click fall
        // through to drag modes and vanilla handling.
        if (dispatchElementClick(event.x(), event.y(), event.button())) {
            return true;
        }

        // Drag mode start — check if a registered mode claims this drag
        if (this.hoveredSlot instanceof MKCSlot mkSlot && activeDrag == null) {
            for (DragMode mode : dragModes) {
                if (mode.onDragStart(mkSlot, event.button())) {
                    activeDrag = mode;
                    return true; // drag claimed — skip vanilla
                }
            }
        }

        // Right-click handler dispatch (group-level capability)
        if (event.button() == 1 && this.hoveredSlot instanceof MKCSlot mkSlot) {
            BiConsumer<net.minecraft.world.entity.player.Player, MKCSlot> handler =
                    mkSlot.getGroup().getRightClickHandler();
            if (handler != null && this.minecraft != null && this.minecraft.player != null) {
                handler.accept(this.minecraft.player, mkSlot);
                fireSlotClick(mkSlot, event.button());
                return true; // consumed — don't let vanilla place an item
            }
        }

        // Fire slot click event (doesn't consume — vanilla still processes)
        if (this.hoveredSlot instanceof MKCSlot mkSlot) {
            fireSlotClick(mkSlot, event.button());

            // Empty slot click: slot has no item AND cursor is empty
            if (!mkSlot.hasItem() && this.menu.getCarried().isEmpty()) {
                fireEmptySlotClick(mkSlot, event.button());
            }

            // Quick move event: shift-click captures the stack before vanilla
            // routes it, so consumers can observe what's about to move.
            if (event.hasShiftDown() && mkSlot.hasItem()) {
                fireQuickMove(mkSlot, mkSlot.getItem().copy());
            }
        }

        return super.mouseClicked(event, flag);
    }

    /**
     * Drag dispatch — if a drag mode is active, route to it.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (activeDrag != null && this.hoveredSlot instanceof MKCSlot mkSlot) {
            activeDrag.onDrag(mkSlot);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    /**
     * Drag end — if a slot-level drag mode is active, notify and clear
     * it. Then dispatch element-level release (drag-end semantic for
     * ScrollContainer + future draggable elements per 14d-2 plumbing).
     *
     * <p>Phase 14d-2.7 primitive-gap fold-inline: element release was
     * missing from MKCHandledScreen because no consumer surfaced
     * the need until the Test Hub wanted a ScrollContainer inside the
     * Hub screen. Same shape as MKScreen's similar fold-inline.
     * Element release fires for every visible element regardless of
     * cursor position — drag-end detection per 14d-2 ScrollContainer
     * plumbing.
     */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean dragModeWasActive = false;
        if (activeDrag != null) {
            activeDrag.onDragEnd();
            activeDrag = null;
            dragModeWasActive = true;
        }
        // Element release — fires regardless of cursor / drag-mode state.
        dispatchElementRelease(event.x(), event.y(), event.button());
        if (dragModeWasActive) {
            return true; // drag claimed the release
        }
        return super.mouseReleased(event);
    }

    /**
     * Phase 14d-2.7 primitive-gap fold-inline: scroll-wheel dispatch
     * to elements. Mirrors {@link #dispatchElementClick} hit-test
     * pattern. Required for ScrollContainer to work inside a MenuKit-
     * native handler screen (the Test Hub's primary use case).
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double scrollX, double scrollY) {
        if (dispatchElementScroll(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean dispatchElementScroll(double mouseX, double mouseY,
                                           double scrollX, double scrollY) {
        // Phase 14d-5 — two-pass dispatch matching dispatchElementClick.
        //   Pass 1: active-overlay exclusive claims (Dropdown popover when open).
        //   Pass 2: normal hit-test dispatch.

        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            for (PanelElement element : panel.getElements()) {
                if (!ClientWindowVisibility.elementShown(panel, element)) continue;
                int[] overlay = element.getActiveOverlayBounds();
                if (overlay != null
                        && mouseX >= overlay[0] && mouseX < overlay[0] + overlay[2]
                        && mouseY >= overlay[1] && mouseY < overlay[1] + overlay[3]) {
                    element.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
                    return true;     // exclusive
                }
            }
        }

        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            PanelBounds bounds = panelBounds.get(panel.getId());
            if (bounds == null) continue;

            int padding = panel.interiorPadding();
            int contentX = leftPos + bounds.x() + padding;
            int contentY = topPos + bounds.y() + padding;

            for (PanelElement element : panel.getElements()) {
                if (!ClientWindowVisibility.elementShown(panel, element)) continue;

                if (element.hitTest(mouseX, mouseY, contentX, contentY)) {
                    if (element.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Element-level release dispatch — fires for every visible element
     * regardless of cursor position (drag-end semantic per 14d-2).
     */
    private void dispatchElementRelease(double mouseX, double mouseY, int button) {
        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            for (PanelElement element : panel.getElements()) {
                if (!ClientWindowVisibility.elementShown(panel, element)) continue;
                element.mouseReleased(mouseX, mouseY, button);
            }
        }
    }

    // ── Element Click Dispatch ─────────────────────────────────────────
    // Checks all visible panels' visible elements for a click hit.
    // Hidden panels' elements are fully inert (like their slots).

    /**
     * Dispatches a click to panel elements. Returns true if any element
     * consumed the click. Checks panel visibility and element visibility
     * before routing — hidden panels' elements never receive clicks.
     *
     * <p>Phase 14d-5 — two-pass dispatch:
     * <ol>
     *   <li><b>Pass 1: active-overlay claims.</b> Any element with an
     *       {@link PanelElement#getActiveOverlayBounds active overlay}
     *       (e.g., Dropdown's popover when open) gets exclusive dispatch
     *       over its overlay region — the click is dropped or consumed
     *       by that element regardless of {@code mouseClicked}'s return,
     *       so behind elements stay innately inert.</li>
     *   <li><b>Pass 2: normal hit-test.</b> If no active overlay claims,
     *       fall through to standard hit-test dispatch.</li>
     * </ol>
     */
    /**
     * Notifies every visible element of an outside click so popover-like
     * elements (Dropdown/DropdownMulti) dismiss. Each element self-guards via
     * {@link PanelElement#notifyClickOutsideOverlay}, so calling it
     * unconditionally on every element is safe. Runs on every click (before
     * dispatch) so an open popover closes even when a slot or another element
     * consumes the click. The MKC-container twin of MKScreen's dismiss janitor.
     */
    private void notifyOutsideClickDismiss(double mouseX, double mouseY) {
        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            for (PanelElement element : panel.getElements()) {
                if (!ClientWindowVisibility.elementShown(panel, element)) continue;
                element.notifyClickOutsideOverlay(mouseX, mouseY);
            }
        }
    }

    private boolean dispatchElementClick(double mouseX, double mouseY, int button) {
        // Pass 1: active-overlay exclusive claims
        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            for (PanelElement element : panel.getElements()) {
                if (!ClientWindowVisibility.elementShown(panel, element)) continue;
                int[] overlay = element.getActiveOverlayBounds();
                if (overlay != null
                        && mouseX >= overlay[0] && mouseX < overlay[0] + overlay[2]
                        && mouseY >= overlay[1] && mouseY < overlay[1] + overlay[3]) {
                    element.mouseClicked(mouseX, mouseY, button);
                    return true;     // exclusive
                }
            }
        }

        // Pass 2: normal hit-test
        for (Panel panel : menu.getPanels()) {
            if (!ClientWindowVisibility.panelShown(panel)) continue;
            PanelBounds bounds = panelBounds.get(panel.getId());
            if (bounds == null) continue;

            int padding = panel.interiorPadding();
            int contentX = leftPos + bounds.x() + padding;
            int contentY = topPos + bounds.y() + padding;

            for (PanelElement element : panel.getElements()) {
                if (!ClientWindowVisibility.elementShown(panel, element)) continue;

                if (element.hitTest(mouseX, mouseY, contentX, contentY)) {
                    if (element.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
