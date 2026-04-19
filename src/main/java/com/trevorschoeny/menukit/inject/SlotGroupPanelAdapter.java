package com.trevorschoeny.menukit.inject;

import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.PanelElement;
import com.trevorschoeny.menukit.core.PanelRendering;
import com.trevorschoeny.menukit.core.PanelStyle;
import com.trevorschoeny.menukit.core.RegionMath;
import com.trevorschoeny.menukit.core.RenderContext;
import com.trevorschoeny.menukit.core.SlotGroupCategory;
import com.trevorschoeny.menukit.core.SlotGroupRegion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Adapter that anchors a {@link Panel} to a slot group's bounding box.
 * Parallel to {@link ScreenPanelAdapter} in shape — same background-render +
 * content-padding + origin + render + click machinery — but the bounds
 * input is a {@link SlotGroupBounds} (the bounding box of a category's
 * slots within a screen) rather than a screen frame.
 *
 * <p>See {@code Design Docs/Phase 12.5/M8_FOUR_CONTEXT_MODEL.md} §5 for
 * design and §7.2 for targeting semantics.
 *
 * <h3>Targeting</h3>
 *
 * Adapters declare target categories via {@code .on(SlotGroupCategory...)}.
 * Exact-category match, not inheritance — categories are flat tags. A single
 * adapter can target multiple categories; the panel renders once per
 * category that resolves in the current screen (so an adapter targeting
 * both {@code PLAYER_INVENTORY} and {@code FURNACE_INPUT} renders twice in
 * a furnace screen: once anchored to the player inventory, once to the
 * furnace input).
 *
 * <p>No {@code .onAny()} — SlotGroupContext targeting is always explicit
 * category enumeration; "any slot group" isn't a meaningful consumer mental
 * model (see M8 §5.6). Construction without a {@code .on(...)} call leaves
 * the adapter in {@link ScreenPanelRegistry}'s pending set; the boot
 * checkpoint fails with {@link IllegalStateException} naming the panel ID.
 */
public final class SlotGroupPanelAdapter {

    /** Default content padding — matches {@link ScreenPanelAdapter#DEFAULT_PADDING}. */
    public static final int DEFAULT_PADDING = ScreenPanelAdapter.DEFAULT_PADDING;

    private final Panel panel;
    private final SlotGroupRegion region;
    private final int padding;

    /** Declared targets; null until {@link #on} is called. */
    private @Nullable List<SlotGroupCategory> targets = null;

    // ── Constructors ────────────────────────────────────────────────────

    /**
     * Constructs an adapter with default content padding. Registration into
     * {@link RegionRegistry}'s per-(category, region) slot-group map happens
     * lazily in {@link #on} — at construction we don't yet know which
     * categories this adapter targets.
     */
    public SlotGroupPanelAdapter(Panel panel, SlotGroupRegion region) {
        this(panel, region, DEFAULT_PADDING);
    }

    /** Constructor with explicit content padding. */
    public SlotGroupPanelAdapter(Panel panel, SlotGroupRegion region, int padding) {
        this.panel = panel;
        this.region = region;
        this.padding = padding;
        ScreenPanelRegistry.trackPendingSlotGroup(this);
    }

    // ── Targeting API ───────────────────────────────────────────────────

    /**
     * Declares the slot-group categories this adapter applies to. Resolution
     * is exact-match — each category named here triggers one render pass per
     * frame on any screen where the category resolves to a non-empty slot
     * list. See M8 §5.2 for why categories are flat tags (no inheritance).
     *
     * <p>Registers the adapter's panel under each (category, region) pair in
     * {@link RegionRegistry}'s slot-group stacking map so multi-adapter
     * stacking within the same (category, region) pair works consistently.
     *
     * <p>Call exactly once per adapter. Duplicate declarations throw
     * {@link IllegalStateException}.
     */
    public SlotGroupPanelAdapter on(SlotGroupCategory... categories) {
        if (targets != null) {
            throw new IllegalStateException(
                    "SlotGroupPanelAdapter for panel '" + panel.getId() +
                    "' already declared targeting. Call .on(...) exactly once.");
        }
        if (categories.length == 0) {
            throw new IllegalArgumentException(
                    "SlotGroupPanelAdapter for panel '" + panel.getId() +
                    "': .on() requires at least one category. SlotGroupContext " +
                    "has no .onAny() — 'any slot group' isn't a meaningful target.");
        }
        this.targets = List.of(categories);
        for (SlotGroupCategory category : this.targets) {
            RegionRegistry.registerSlotGroup(panel, category, region, padding);
        }
        ScreenPanelRegistry.markSlotGroupTargetingDeclared(this);
        return this;
    }

    // ── Accessors ──────────────────────────────────────────────────────

    public Panel getPanel() { return panel; }
    public SlotGroupRegion getRegion() { return region; }
    public int getPadding() { return padding; }

    /** Returns declared targets; null before {@link #on} is called. */
    public @Nullable List<SlotGroupCategory> getTargets() { return targets; }

    public boolean isTargetingDeclared() { return targets != null; }

    /** True iff {@code category} is one of this adapter's declared targets. */
    public boolean matches(SlotGroupCategory category) {
        if (targets == null) return false;
        return targets.contains(category);
    }

    /**
     * Returns the panel's screen-space origin for the given slot-group
     * bounds anchored in {@code category}, or empty when the panel is
     * invisible or the region overflows the slot group's extent.
     */
    public Optional<ScreenOrigin> getOrigin(SlotGroupBounds bounds,
                                             SlotGroupCategory category,
                                             AbstractContainerScreen<?> screen) {
        if (!panel.isVisible()) return Optional.empty();
        int pw = panel.getWidth() + 2 * padding;
        int ph = panel.getHeight() + 2 * padding;
        int prefix = RegionRegistry.axialPrefix(panel, category, region);
        Optional<ScreenOrigin> result =
                RegionMath.resolveSlotGroup(region, bounds, pw, ph, prefix);
        if (result.isEmpty()) {
            RegionRegistry.warnSlotGroupOverflowOnce(panel, category, region,
                    pw, ph, prefix, bounds);
        }
        return result;
    }

    // ── Render + input ─────────────────────────────────────────────────

    /**
     * Renders the panel against the given slot-group bounds in
     * {@code category}. Called from
     * {@link ScreenPanelRegistry}'s dispatch — once per matching (adapter,
     * category) pair per frame.
     */
    public void render(GuiGraphics graphics, SlotGroupBounds bounds,
                       SlotGroupCategory category,
                       int mouseX, int mouseY,
                       AbstractContainerScreen<?> screen) {
        Optional<ScreenOrigin> originOpt = getOrigin(bounds, category, screen);
        if (originOpt.isEmpty()) return;
        ScreenOrigin origin = originOpt.get();

        int panelWidth = panel.getWidth() + 2 * padding;
        int panelHeight = panel.getHeight() + 2 * padding;

        if (panel.getStyle() != PanelStyle.NONE) {
            PanelRendering.renderPanel(graphics,
                    origin.x(), origin.y(),
                    panelWidth, panelHeight,
                    panel.getStyle());
        }

        RenderContext ctx = new RenderContext(
                graphics, origin.x() + padding, origin.y() + padding,
                mouseX, mouseY);

        for (PanelElement element : panel.getElements()) {
            if (!element.isVisible()) continue;
            element.render(ctx);
        }
    }

    /**
     * Dispatches a mouse click to visible elements. Same padding-inclusive
     * hit-test logic as {@link ScreenPanelAdapter#mouseClicked}. Returns
     * whether any element consumed the click.
     */
    public boolean mouseClicked(SlotGroupBounds bounds, SlotGroupCategory category,
                                double mouseX, double mouseY, int button,
                                AbstractContainerScreen<?> screen) {
        Optional<ScreenOrigin> originOpt = getOrigin(bounds, category, screen);
        if (originOpt.isEmpty()) return false;
        ScreenOrigin origin = originOpt.get();

        int contentX = origin.x() + padding;
        int contentY = origin.y() + padding;

        for (PanelElement element : panel.getElements()) {
            if (!element.isVisible()) continue;

            int sx = contentX + element.getChildX();
            int sy = contentY + element.getChildY();
            if (mouseX < sx || mouseX >= sx + element.getWidth()) continue;
            if (mouseY < sy || mouseY >= sy + element.getHeight()) continue;

            if (element.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }
}
