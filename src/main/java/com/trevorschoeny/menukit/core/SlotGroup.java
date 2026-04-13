package com.trevorschoeny.menukit.core;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where behavior lives. A SlotGroup is the composition of five orthogonal
 * axes: {@link Storage}, {@link InteractionPolicy}, {@link QuickMoveParticipation},
 * capabilities, and layout metadata.
 *
 * <p>The group owns the behavioral contract for its slots. When a slot
 * asks "can this stack go in me?", it delegates up to its group's policy.
 * This keeps slots thin (identity only) and makes group-level changes
 * (swap policy, flip inertness) take effect across all slots immediately.
 *
 * <p>Part of the canonical MenuKit hierarchy:
 * Screen → Panel → SlotGroup → MenuKitSlot
 */
public class SlotGroup {

    private final String id;
    private final Storage storage;
    private final InteractionPolicy policy;
    private final QuickMoveParticipation qmp;
    private final int shiftClickPriority;

    // Set during Panel construction — the group knows its parent
    private @Nullable Panel panel;

    // Flat index range in the handler's slot list — set once during
    // handler construction, then frozen. Used by shift-click routing.
    private int flatIndexStart = -1;
    private int flatIndexEnd = -1;

    // Directional pairing for shift-click routing
    private final List<SlotGroup> pairedWith = new ArrayList<>();

    /**
     * @param id                  unique identifier within the panel
     * @param storage             where items live
     * @param policy              what operations are allowed
     * @param qmp                 how this group participates in shift-click
     * @param shiftClickPriority  numeric priority (higher = tried first)
     */
    public SlotGroup(String id, Storage storage, InteractionPolicy policy,
                     QuickMoveParticipation qmp, int shiftClickPriority) {
        this.id = id;
        this.storage = storage;
        this.policy = policy;
        this.qmp = qmp;
        this.shiftClickPriority = shiftClickPriority;
    }

    /** Convenience: BOTH participation, default priority (100). */
    public SlotGroup(String id, Storage storage, InteractionPolicy policy) {
        this(id, storage, policy, QuickMoveParticipation.BOTH, 100);
    }

    // ── Identity ────────────────────────────────────────────────────────

    /** Returns this group's unique identifier within its panel. */
    public String getId() { return id; }

    // ── Axes ────────────────────────────────────────────────────────────

    /** Returns where items live. */
    public Storage getStorage() { return storage; }

    /** Returns what operations are allowed. */
    public InteractionPolicy getPolicy() { return policy; }

    /** Returns how this group participates in shift-click routing. */
    public QuickMoveParticipation getQmp() { return qmp; }

    /** Returns the numeric shift-click priority (higher = tried first). */
    public int getShiftClickPriority() { return shiftClickPriority; }

    // ── Panel Reference ─────────────────────────────────────────────────

    /** Returns the owning Panel, or null if not yet attached. */
    public @Nullable Panel getPanel() { return panel; }

    /** Sets the owning Panel. Called during Panel construction. */
    public void setPanel(Panel panel) { this.panel = panel; }

    // ── Behavioral Delegation ───────────────────────────────────────────
    // Slots delegate to these methods. The policy is the source of truth.

    /**
     * Can this group accept the given stack? Delegates to the policy's
     * {@code canAccept} predicate.
     *
     * <p>When a MenuKitSlot calls {@code mayPlace}, it calls this AND
     * {@code super.mayPlace} — the mixin chain composes, and the most
     * restrictive answer wins.
     */
    public boolean canAccept(ItemStack stack) {
        return policy.canAccept().test(stack);
    }

    /**
     * Can items be removed from this group? Delegates to the policy's
     * {@code canRemove} predicate.
     */
    public boolean canRemove(ItemStack stack) {
        return policy.canRemove().test(stack);
    }

    /**
     * Maximum stack size for the given item in this group.
     * Delegates to the policy's {@code maxStackSize} function.
     */
    public int maxStackSize(ItemStack stack) {
        return policy.maxStackSize().applyAsInt(stack);
    }

    // ── Flat Index Range ───────────────────────────────────────────────

    /** Returns the start of this group's flat index range (inclusive). */
    public int getFlatIndexStart() { return flatIndexStart; }

    /** Returns the end of this group's flat index range (exclusive). */
    public int getFlatIndexEnd() { return flatIndexEnd; }

    /** Sets the flat index range. Called once during handler construction. */
    public void setFlatIndexRange(int start, int end) {
        this.flatIndexStart = start;
        this.flatIndexEnd = end;
    }

    // ── Directional Pairing ─────────────────────────────────────────────

    /**
     * Declares that shift-click from this group should prefer the target
     * group over numeric priority. This is how furnace-style affinities
     * work: fuel items target the fuel slot, smeltable items target the
     * input slot.
     */
    public SlotGroup pairsWith(SlotGroup target) {
        pairedWith.add(target);
        return this;
    }

    /** Returns the list of directional pairing targets. */
    public List<SlotGroup> getPairedWith() {
        return Collections.unmodifiableList(pairedWith);
    }
}
