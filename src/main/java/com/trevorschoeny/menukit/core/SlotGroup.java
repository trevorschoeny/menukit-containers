package com.trevorschoeny.menukit.core;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

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
 * <p>Implements {@link SlotGroupLike} so consumer code can program against
 * a uniform interface shared with {@link VirtualSlotGroup} (observed screens).
 *
 * <p>Part of the canonical MenuKit hierarchy:
 * Screen → Panel → SlotGroup → MenuKitSlot
 */
public class SlotGroup implements SlotGroupLike {

    private final String id;
    private final Storage storage;
    private final InteractionPolicy policy;
    private final QuickMoveParticipation qmp;
    private final int shiftClickPriority;

    // ── Layout metadata (declarative, read by the screen) ──────────────
    private final int columns;      // grid columns for slot layout
    private final int rowGapAfter;  // 0-indexed row after which to insert a gap (-1 = none)
    private final int rowGapSize;   // gap size in pixels

    // ── Right-click handler (group-level capability) ───────────────────
    // Optional handler invoked when a slot in this group is right-clicked.
    // Lives on the group per the canonical story — right-click is a
    // group-level capability, not a slot-level one.
    private @Nullable BiConsumer<Player, MenuKitSlot> rightClickHandler;

    // Flat index range in the handler's slot list — set once during
    // handler construction, then frozen. Used by shift-click routing.
    private int flatIndexStart = -1;
    private int flatIndexEnd = -1;

    // Directional pairing for shift-click routing
    private final List<SlotGroup> pairedWith = new ArrayList<>();

    /**
     * Full constructor with all axes including layout metadata.
     *
     * @param id                  unique identifier within the panel
     * @param storage             where items live
     * @param policy              what operations are allowed
     * @param qmp                 how this group participates in shift-click
     * @param shiftClickPriority  numeric priority (higher = tried first)
     * @param columns             grid columns for slot layout (-1 = auto)
     * @param rowGapAfter         0-indexed row after which to insert a gap (-1 = none)
     * @param rowGapSize          gap size in pixels (only used if rowGapAfter >= 0)
     */
    public SlotGroup(String id, Storage storage, InteractionPolicy policy,
                     QuickMoveParticipation qmp, int shiftClickPriority,
                     int columns, int rowGapAfter, int rowGapSize) {
        this.id = id;
        this.storage = storage;
        this.policy = policy;
        this.qmp = qmp;
        this.shiftClickPriority = shiftClickPriority;
        // Auto-compute columns: min(9, storage size) if not specified
        this.columns = columns > 0 ? columns : Math.min(9, storage.size());
        this.rowGapAfter = rowGapAfter;
        this.rowGapSize = rowGapSize;
    }

    /**
     * @param id                  unique identifier within the panel
     * @param storage             where items live
     * @param policy              what operations are allowed
     * @param qmp                 how this group participates in shift-click
     * @param shiftClickPriority  numeric priority (higher = tried first)
     */
    public SlotGroup(String id, Storage storage, InteractionPolicy policy,
                     QuickMoveParticipation qmp, int shiftClickPriority) {
        this(id, storage, policy, qmp, shiftClickPriority, -1, -1, 0);
    }

    /** Convenience: BOTH participation, default priority (100). */
    public SlotGroup(String id, Storage storage, InteractionPolicy policy) {
        this(id, storage, policy, QuickMoveParticipation.BOTH, 100);
    }

    // ── Identity ────────────────────────────────────────────────────────

    /** Returns this group's unique identifier within its panel. */
    @Override public String getId() { return id; }

    // ── Axes ────────────────────────────────────────────────────────────

    /** Returns where items live. */
    @Override public Storage getStorage() { return storage; }

    /** Returns what operations are allowed. */
    @Override public InteractionPolicy getPolicy() { return policy; }

    /** Returns how this group participates in shift-click routing. */
    @Override public QuickMoveParticipation getQmp() { return qmp; }

    /** Returns the numeric shift-click priority (higher = tried first). */
    @Override public int getShiftClickPriority() { return shiftClickPriority; }

    // ── Layout Metadata ────────────────────────────────────────────────

    /** Grid columns for slot layout. Always > 0 (auto-computed if not specified). */
    public int getColumns() { return columns; }

    /** 0-indexed row after which to insert a visual gap, or -1 for none. */
    public int getRowGapAfter() { return rowGapAfter; }

    /** Gap size in pixels (only meaningful if rowGapAfter >= 0). */
    public int getRowGapSize() { return rowGapSize; }

    // ── Right-Click Handler ────────────────────────────────────────────

    /** Returns the right-click handler for this group, or null. */
    public @Nullable BiConsumer<Player, MenuKitSlot> getRightClickHandler() {
        return rightClickHandler;
    }

    /** Sets the right-click handler. Called during builder construction. */
    public void setRightClickHandler(@Nullable BiConsumer<Player, MenuKitSlot> handler) {
        this.rightClickHandler = handler;
    }

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
    @Override
    public boolean canAccept(ItemStack stack) {
        return policy.canAccept().test(stack);
    }

    /**
     * Can items be removed from this group? Delegates to the policy's
     * {@code canRemove} predicate.
     */
    @Override
    public boolean canRemove(ItemStack stack) {
        return policy.canRemove().test(stack);
    }

    /**
     * Maximum stack size for the given item in this group.
     * Delegates to the policy's {@code maxStackSize} function.
     */
    @Override
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

    /**
     * Returns this group's slots as a list, extracted from the handler's
     * flat slot list. Convenience for consumers that need to iterate
     * a group's slots without walking the flat index range manually.
     *
     * <p>Returns {@code List<MenuKitSlot>} — a valid covariant override of
     * {@link SlotGroupLike#getSlots}'s {@code List<? extends Slot>}.
     *
     * @param handler the handler whose slot list contains this group's slots
     * @return unmodifiable list of MenuKitSlots in this group
     */
    @Override
    public List<MenuKitSlot> getSlots(AbstractContainerMenu handler) {
        if (flatIndexStart < 0 || flatIndexEnd < 0) return List.of();
        List<MenuKitSlot> result = new ArrayList<>();
        for (int i = flatIndexStart; i < flatIndexEnd; i++) {
            Slot slot = handler.slots.get(i);
            if (slot instanceof MenuKitSlot mk) {
                result.add(mk);
            }
        }
        return Collections.unmodifiableList(result);
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
