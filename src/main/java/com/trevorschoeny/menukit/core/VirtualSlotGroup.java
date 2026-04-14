package com.trevorschoeny.menukit.core;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * The observed-screen equivalent of {@link SlotGroup}. Wraps vanilla
 * {@link Slot}s that MenuKit doesn't own, providing the same query
 * surface via {@link SlotGroupLike}.
 *
 * <p>Created by {@link HandlerRecognizerRegistry} when analyzing
 * non-MenuKit handlers (chests, furnaces, brewing stands, etc.).
 *
 * <p>Key differences from {@link SlotGroup}:
 * <ul>
 *   <li><b>Storage is read-only.</b> {@link ReadOnlyStorage} reads live
 *       from the wrapped slots but ignores writes. MenuKit doesn't mutate
 *       handlers it doesn't own.</li>
 *   <li><b>Policy is best-effort.</b> Inferred from slot behavior during
 *       recognition, not declared by the mod author.</li>
 *   <li><b>QMP is descriptive, not prescriptive.</b> It predicts where
 *       shift-click would route, but doesn't control it — MenuKit doesn't
 *       override {@code quickMoveStack} on handlers it didn't build.</li>
 *   <li><b>No layout metadata.</b> Columns, row gaps, panel references
 *       don't apply — MenuKit doesn't lay out observed screens.</li>
 * </ul>
 *
 * @see SlotGroupLike      The uniform abstraction consumers program against
 * @see SlotGroup           MenuKit-native implementation
 * @see HandlerRecognizerRegistry  Where these are created
 */
public class VirtualSlotGroup implements SlotGroupLike {

    private final String id;
    private final List<Slot> slots;
    private final ReadOnlyStorage storage;
    private final InteractionPolicy policy;
    private final QuickMoveParticipation qmp;
    private final int shiftClickPriority;

    /**
     * @param id                  group identifier (e.g., "container", "input", "player_inventory")
     * @param slots               the vanilla slots this group wraps (in handler order)
     * @param policy              inferred interaction policy
     * @param qmp                 descriptive shift-click participation
     * @param shiftClickPriority  numeric priority (higher = tried first)
     */
    public VirtualSlotGroup(String id, List<Slot> slots, InteractionPolicy policy,
                            QuickMoveParticipation qmp, int shiftClickPriority) {
        this.id = id;
        this.slots = List.copyOf(slots);
        // ReadOnlyStorage needs the backing Container — use the first slot's container.
        // All slots in a group share the same Container (that's how grouping works).
        this.storage = new ReadOnlyStorage(this.slots,
                this.slots.isEmpty() ? null : this.slots.get(0).container);
        this.policy = policy;
        this.qmp = qmp;
        this.shiftClickPriority = shiftClickPriority;
    }

    /** Convenience: BOTH participation, default priority (100). */
    public VirtualSlotGroup(String id, List<Slot> slots, InteractionPolicy policy) {
        this(id, slots, policy, QuickMoveParticipation.BOTH, 100);
    }

    // ── SlotGroupLike Implementation ───────────────────────────────────

    @Override
    public String getId() { return id; }

    @Override
    public Storage getStorage() { return storage; }

    @Override
    public InteractionPolicy getPolicy() { return policy; }

    @Override
    public QuickMoveParticipation getQmp() { return qmp; }

    @Override
    public int getShiftClickPriority() { return shiftClickPriority; }

    @Override
    public boolean canAccept(ItemStack stack) {
        return policy.canAccept().test(stack);
    }

    @Override
    public boolean canRemove(ItemStack stack) {
        return policy.canRemove().test(stack);
    }

    @Override
    public int maxStackSize(ItemStack stack) {
        return policy.maxStackSize().applyAsInt(stack);
    }

    /**
     * Returns this group's vanilla slots. The handler parameter is
     * ignored — virtual groups already hold their slot references.
     */
    @Override
    public List<? extends Slot> getSlots(AbstractContainerMenu handler) {
        return slots;
    }

    // ── Reverse Lookup ─────────────────────────────────────────────────

    /**
     * Returns true if the given slot belongs to this group.
     * Checks by reference identity — the same Slot object must be
     * in this group's slot list.
     */
    public boolean containsSlot(Slot slot) {
        for (Slot s : slots) {
            if (s == slot) return true;
        }
        return false;
    }

    /** Returns the number of slots in this group. */
    public int size() {
        return slots.size();
    }
}
