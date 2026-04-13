package com.trevorschoeny.menukit.core;

/**
 * Declares how a {@link SlotGroup} participates in shift-click routing.
 *
 * <p>On MenuKit-native screens, this is authoritative — the handler's
 * {@code quickMoveStack} uses it to determine routing. On observed
 * (non-MenuKit) screens, this is descriptive — it predicts where
 * shift-click would route, but doesn't control it.
 */
public enum QuickMoveParticipation {

    /** Items can be shift-clicked OUT of this group, but not into it. */
    EXPORTS,

    /** Items can be shift-clicked INTO this group, but not out of it. */
    IMPORTS,

    /** Items can be shift-clicked both into and out of this group. */
    BOTH,

    /** This group does not participate in shift-click routing. */
    NONE;

    /** Returns true if items can be shift-clicked out of this group. */
    public boolean exports() {
        return this == EXPORTS || this == BOTH;
    }

    /** Returns true if items can be shift-clicked into this group. */
    public boolean imports() {
        return this == IMPORTS || this == BOTH;
    }
}
