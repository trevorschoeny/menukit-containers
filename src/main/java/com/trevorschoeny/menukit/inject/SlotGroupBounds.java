package com.trevorschoeny.menukit.inject;

/**
 * The bounding rectangle of a slot group in screen space. Parallel to
 * {@link ScreenBounds} (which describes an
 * {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}
 * frame) — same four fields, but represents a <i>slot group's extent</i>
 * within a screen rather than the screen frame itself.
 *
 * <p>Computed per frame by
 * {@link com.trevorschoeny.menukit.inject.ScreenPanelRegistry} when
 * dispatching SlotGroupContext adapters — walks the slots in a given
 * {@link com.trevorschoeny.menukit.core.SlotGroupCategory}, takes the min/max
 * of their screen-space positions, and returns the enclosing rectangle.
 *
 * <p>Separate type from {@link ScreenBounds} for type-safety at the adapter
 * call-site. See {@code Design Docs/Phase 12.5/M8_FOUR_CONTEXT_MODEL.md} §5.4
 * for design and §5.5 for the separate-types rationale.
 */
public record SlotGroupBounds(int leftPos, int topPos, int imageWidth, int imageHeight) { }
