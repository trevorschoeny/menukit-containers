package com.trevorschoeny.menukit.core;

/**
 * Named regions for positioning decoration panels around the bounding box
 * of a slot group. Parallel to {@link MenuRegion} — same eight values, same
 * semantics, same flow directions — but anchors to a slot group's bounding
 * rectangle rather than a vanilla menu's container frame.
 *
 * <p>See {@code Design Docs/Phase 12.5/M8_FOUR_CONTEXT_MODEL.md} §5 for
 * context model and §5.5 for why this enum is a separate type from
 * {@link MenuRegion} despite having identical members.
 *
 * <p><b>Flow direction</b> — stacking grows away from the anchor end,
 * identical to {@link MenuRegion}:
 * <ul>
 *   <li>{@link #LEFT_ALIGN_TOP} / {@link #RIGHT_ALIGN_TOP} — flow down
 *   <li>{@link #LEFT_ALIGN_BOTTOM} / {@link #RIGHT_ALIGN_BOTTOM} — flow up
 *   <li>{@link #TOP_ALIGN_LEFT} / {@link #BOTTOM_ALIGN_LEFT} — flow right
 *   <li>{@link #TOP_ALIGN_RIGHT} / {@link #BOTTOM_ALIGN_RIGHT} — flow left
 * </ul>
 */
public enum SlotGroupRegion {
    LEFT_ALIGN_TOP,
    LEFT_ALIGN_BOTTOM,
    RIGHT_ALIGN_TOP,
    RIGHT_ALIGN_BOTTOM,
    TOP_ALIGN_LEFT,
    TOP_ALIGN_RIGHT,
    BOTTOM_ALIGN_LEFT,
    BOTTOM_ALIGN_RIGHT;

    /**
     * Returns true if panels in this region stack along the X axis —
     * matches {@link MenuRegion#isHorizontalFlow()} semantics.
     */
    public boolean isHorizontalFlow() {
        return switch (this) {
            case TOP_ALIGN_LEFT, TOP_ALIGN_RIGHT,
                 BOTTOM_ALIGN_LEFT, BOTTOM_ALIGN_RIGHT -> true;
            case LEFT_ALIGN_TOP, LEFT_ALIGN_BOTTOM,
                 RIGHT_ALIGN_TOP, RIGHT_ALIGN_BOTTOM -> false;
        };
    }
}
