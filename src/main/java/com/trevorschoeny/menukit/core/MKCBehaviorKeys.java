package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.BehaviorKey;
import com.trevorschoeny.menukit.window.KindTag;
import com.trevorschoeny.menukit.window.Tier;

import net.minecraft.resources.Identifier;

/**
 * MKC's server-tier {@link BehaviorKey}s — the behaviors whose value types are
 * MKC types (so the constant lives here, not in MK's {@code BehaviorKeys}). The
 * engine is generic, so these register the same way client keys do; MKC's
 * server seam (Phase 4) resolves and enforces them.
 *
 * <p><b>Phase 4</b> defines {@link #GATING} (the first server behavior — what a
 * slot accepts/releases). Still owed, added with their sub-phases: {@code
 * QUICK_MOVE}, {@code DROP_RULE}, {@code BINDING}, {@code MENDING},
 * {@code ON_INSERT}/{@code ON_TAKE} (server-authoritative).
 */
public final class MKCBehaviorKeys {

    private MKCBehaviorKeys() {}

    /**
     * What a slot accepts / releases. Server-tier, slot kinds. Default
     * {@link SlotGate#OPEN} (vanilla — an un-gated slot behaves exactly as
     * vanilla, so "the slots nobody touches stay exactly vanilla").
     */
    public static final BehaviorKey<SlotGate> GATING = BehaviorKey.of(
            Identifier.fromNamespaceAndPath("menukit", "gating"),
            SlotGate.class, SlotGate.OPEN, Tier.SERVER,
            KindTag.VANILLA_SLOT, KindTag.CREATED_SLOT);
}
