package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.BehaviorKey;
import com.trevorschoeny.menukit.window.KindTag;
import com.trevorschoeny.menukit.window.Tier;
import com.trevorschoeny.menukit.window.TriBool;

import net.minecraft.resources.Identifier;

/**
 * MKC's server-tier {@link BehaviorKey}s — the behaviors whose value types are
 * MKC types (so the constant lives here, not in MK's {@code BehaviorKeys}). The
 * engine is generic, so these register the same way client keys do; MKC's
 * server seam (Phase 4) resolves and enforces them.
 *
 * <p><b>Phase 4</b> defines {@link #GATING}; <b>Phase 5</b> adds the rest of the
 * server slot behaviors as their creation knobs are retired: {@link #QUICK_MOVE}
 * (5b), {@link #BINDING} (5c). Still owed in later sub-phases: {@code MENDING},
 * {@code DROP_RULE}. ({@code ON_INSERT}/{@code ON_TAKE} live MK-side — their value
 * type {@code ReactiveHook} is an MK type; the reactive declarations ship in MK.)
 *
 * <p>Placement rule: a server slot-behavior key lives here (MKC) regardless of
 * whether its value type is an MK type — {@code BINDING}/{@code MENDING} use the
 * MK {@code TriBool}, but they are MKC-enforced server behaviors, so they group
 * with their kin rather than with the client/reactive keys in MK's BehaviorKeys.
 */
public final class MKCBehaviorKeys {

    private MKCBehaviorKeys() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("menukit", path);
    }

    /**
     * What a slot accepts / releases (and its per-item stack cap). Server-tier,
     * slot kinds. Default {@link SlotGate#OPEN} (vanilla — an un-gated slot behaves
     * exactly as vanilla, so "the slots nobody touches stay exactly vanilla").
     */
    public static final BehaviorKey<SlotGate> GATING = BehaviorKey.of(
            id("gating"), SlotGate.class, SlotGate.OPEN, Tier.SERVER,
            KindTag.VANILLA_SLOT, KindTag.CREATED_SLOT);

    /**
     * How a created slot participates in shift-click (quick-move) routing on a
     * foreign menu — exports, imports, both, or neither. Server-tier, created
     * slots. Default {@link QuickMoveParticipation#BOTH} (a created slot both
     * vacuums and yields shift-clicks unless told otherwise).
     */
    public static final BehaviorKey<QuickMoveParticipation> QUICK_MOVE = BehaviorKey.of(
            id("quick_move"), QuickMoveParticipation.class, QuickMoveParticipation.BOTH,
            Tier.SERVER, KindTag.CREATED_SLOT);

    /**
     * Whether Curse of Binding is enforced on this slot — a bound item
     * ({@code PREVENT_ARMOR_CHANGE}) can't be taken out while the player is alive,
     * survival only (creative bypasses, matching vanilla armor slots; §0051).
     * Server-tier, created slots. Default {@link TriBool#FALSE} (off).
     */
    public static final BehaviorKey<TriBool> BINDING = BehaviorKey.of(
            id("binding"), TriBool.class, TriBool.FALSE, Tier.SERVER, KindTag.CREATED_SLOT);
}
