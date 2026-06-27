package com.trevorschoeny.menukit.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The set of {@link SlotElement}s currently attached to an open screen — the
 * bridge that lets MenuKit's library-owned screen hook resolve hover/click for
 * panel-hosted slots, not just slots registered through the old
 * {@link GraftScreenPresence} world.
 *
 * <h3>Why this is needed</h3>
 *
 * The {@code getHoveredSlot} interception fans through {@link MenuKitGraftScreenHook}.
 * That hook historically only knew about slots whose panel had a registered
 * {@code GraftScreenPresence}; a panel-hosted {@link SlotElement} has no presence.
 * This registry tells the hook which panels currently have live SlotElements, so
 * it resolves them through the same creative-aware
 * {@link MenuKitGraftInput#resolveHoveredSlot} path — and a slot in a panel
 * becomes clickable and hover-correct with zero presence boilerplate.
 *
 * <h3>Lifecycle</h3>
 *
 * A {@link SlotElement} adds itself on {@code onAttach} (fired when its panel is
 * matched to an opening screen) and removes itself on {@code onDetach} (screen
 * close). Those are balanced by the panel lifecycle, so only the live screen's
 * SlotElements are ever present — a plain identity set suffices.
 *
 * <p>Client-only — attach/detach and hover resolution all run on the render/input
 * thread; the server never touches it.
 */
public final class SlotElementRegistry {

    // Identity-keyed so two SlotElements around equal-looking slots never collide.
    // Synchronized: rare writes (screen open/close), reads on the input thread.
    private static final Set<SlotElement> ACTIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private SlotElementRegistry() {}

    /** Registers a SlotElement as attached to the current screen. */
    static void add(SlotElement element) {
        ACTIVE.add(element);
    }

    /** Removes a SlotElement on screen detach. Idempotent. */
    static void remove(SlotElement element) {
        ACTIVE.remove(element);
    }

    /** Whether any panel-hosted slot is currently attached. */
    public static boolean hasActive() {
        return !ACTIVE.isEmpty();
    }

    /**
     * The panel ids that currently have at least one attached {@link SlotElement}.
     * The screen hook unions these with its presence panel ids and passes the
     * result as the resolution filter, so panel-hosted slots resolve alongside
     * (or instead of) presence-registered grafts.
     */
    public static Set<String> activePanelIds() {
        synchronized (ACTIVE) {
            if (ACTIVE.isEmpty()) return Set.of();
            Set<String> ids = new HashSet<>(ACTIVE.size() * 2);
            for (SlotElement element : ACTIVE) {
                ids.add(element.slot().getPanelId());
            }
            return ids;
        }
    }
}
