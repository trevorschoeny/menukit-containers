package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerMenuInvoker;
import com.trevorschoeny.menukit.window.WindowEngine;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Library-owned shift-click (quick-move) routing for registered slots on a
 * <b>foreign</b> menu — a chest/furnace/donkey menu MenuKit didn't build, that a
 * slot has been projected onto ({@link MKCSlotProjection}). The generalization of
 * {@code MKCScreenHandler.quickMoveStack}'s declarative routing to a menu
 * whose own vanilla {@code quickMoveStack} knows nothing about the appended slot
 * tail block.
 *
 * <h3>Why it's needed</h3>
 *
 * Foreign menus compute their quick-move index ranges against the
 * <em>unprojected</em> slot count, so appended slots sit outside every vanilla
 * destination range: a shift-click <em>into</em> the menu never targets a slot,
 * and a shift-click <em>out of</em> a slot hits vanilla code that has no case for
 * the source index. This routes both directions through vanilla's own
 * {@code moveItemStackTo} (the absorbed {@link AbstractContainerMenuInvoker}
 * invoker), so the merge/place math and the tail contract stay vanilla-exact — the
 * library moves the consumer's own slot, it invents no placement behavior.
 *
 * <h3>§0019 — opt-in, consumer-triggered</h3>
 *
 * The consumer keeps the one-line {@code @Inject(HEAD, cancellable)} on each
 * foreign menu's {@code quickMoveStack} and delegates here (MK/MKC never install
 * the interception). Whether a slot <em>vacuums</em> shift-clicks on a foreign
 * screen is opt-in per slot via {@link QuickMoveParticipation#imports()} +
 * {@link SlotGroup#canAccept} — a slot created with {@code imports() == false}
 * for its projected copy stays passive on chests. {@link #route} returns
 * {@link ItemStack#EMPTY} when no slot claims the move, which the consumer's HEAD
 * inject treats as "not handled — let vanilla run."
 */
public final class MKCSlotQuickMove {

    private MKCSlotQuickMove() {}

    /**
     * Routes a shift-click at {@code index} considering the slots on {@code menu}.
     * Returns the moved stack (vanilla's {@code quickMoveStack} contract: the
     * original stack when something moved so the caller loops, {@link ItemStack#EMPTY}
     * when nothing moved). EMPTY also means "not a slot concern — let vanilla
     * handle it"; the consumer's HEAD inject only cancels on a non-EMPTY return.
     */
    public static ItemStack route(AbstractContainerMenu menu, Player player, int index) {
        if (index < 0 || index >= menu.slots.size()) return ItemStack.EMPTY;

        // Discover the slot groups present on this menu, one representative live
        // MKCSlot per group (its address is how QUICK_MOVE/GATING are resolved from
        // the engine), + where the slot tail block begins (everything before it is
        // the menu's own vanilla slots).
        Map<SlotGroup, MKCSlot> groupReps = new LinkedHashMap<>();
        int firstSlotIndex = menu.slots.size();
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i) instanceof MKCSlot mk) {
                groupReps.putIfAbsent(mk.getGroup(), mk);
                if (i < firstSlotIndex) firstSlotIndex = i;
            }
        }
        if (groupReps.isEmpty()) return ItemStack.EMPTY; // no slots here — vanilla's job

        Slot source = menu.slots.get(index);
        if (source instanceof MKCSlot mkSource) {
            return routeOutOfSlot(menu, player, mkSource, groupReps, firstSlotIndex);
        }
        return routeIntoSlots(menu, player, source, groupReps);
    }

    /** A created slot's quick-move participation, resolved from the engine by its address. */
    private static QuickMoveParticipation qmpOf(MKCSlot slot) {
        return WindowEngine.resolve(slot.address(), MKCBehaviorKeys.QUICK_MOVE);
    }

    /**
     * Shift-click out of a registered slot: route its item to other importing slot
     * groups (declarative priority) first, then the menu's vanilla slots.
     */
    private static ItemStack routeOutOfSlot(AbstractContainerMenu menu, Player player,
                                             MKCSlot source, Map<SlotGroup, MKCSlot> groupReps,
                                             int firstSlotIndex) {
        SlotGroup sourceGroup = source.getGroup();
        if (source.isInert() || !source.hasItem()) return ItemStack.EMPTY;
        if (!qmpOf(source).exports()) return ItemStack.EMPTY;

        ItemStack original = source.getItem().copy();
        ItemStack working = source.getItem();
        AbstractContainerMenuInvoker mover = (AbstractContainerMenuInvoker) menu;

        // 1. Other slot groups that import + accept, highest priority first.
        for (SlotGroup candidate : sortedImporters(groupReps, sourceGroup, working)) {
            if (working.isEmpty()) break;
            mover.mk$moveItemStackTo(working,
                    candidate.getFlatIndexStart(), candidate.getFlatIndexEnd(), false);
        }
        // 2. The menu's own vanilla slots (chest storage + player inventory), in
        //    menu order. Slots sit in the tail block [firstSlotIndex, size); the
        //    vanilla region is everything before it.
        if (!working.isEmpty() && firstSlotIndex > 0) {
            mover.mk$moveItemStackTo(working, 0, firstSlotIndex, false);
        }

        return finishSource(player, source, original, working);
    }

    /**
     * Shift-click a vanilla slot into the slots: route into importing slot
     * groups. Returns EMPTY (let vanilla handle the normal chest↔inventory move)
     * when no slot imports/accepts the stack or nothing actually moved.
     */
    private static ItemStack routeIntoSlots(AbstractContainerMenu menu, Player player,
                                             Slot source, Map<SlotGroup, MKCSlot> groupReps) {
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;

        ItemStack working = source.getItem();
        List<SlotGroup> importers = sortedImporters(groupReps, null, working);
        if (importers.isEmpty()) return ItemStack.EMPTY; // nothing wants it → vanilla's job

        ItemStack original = working.copy();
        AbstractContainerMenuInvoker mover = (AbstractContainerMenuInvoker) menu;
        for (SlotGroup candidate : importers) {
            if (working.isEmpty()) break;
            mover.mk$moveItemStackTo(working,
                    candidate.getFlatIndexStart(), candidate.getFlatIndexEnd(), false);
        }
        if (working.getCount() == original.getCount()) return ItemStack.EMPTY; // slots took nothing

        return finishSource(player, source, original, working);
    }

    /**
     * Candidate slot groups that import + can accept the stack, sorted by priority
     * desc. Participation ({@code imports}) and acceptance are resolved from the
     * engine via each group's representative slot — the slot's own
     * {@link MKCSlot#mayPlace} (engine GATING + inertness + vanilla) is the accept
     * test, so the pre-filter agrees with what the actual move will allow. Foreign-
     * menu participation is group-granular (the representative); per-slot GATING is
     * still enforced slot-by-slot inside the vanilla move.
     */
    private static List<SlotGroup> sortedImporters(Map<SlotGroup, MKCSlot> groupReps, SlotGroup exclude,
                                                   ItemStack stack) {
        List<SlotGroup> out = new ArrayList<>();
        for (Map.Entry<SlotGroup, MKCSlot> e : groupReps.entrySet()) {
            SlotGroup group = e.getKey();
            MKCSlot rep = e.getValue();
            if (group == exclude) continue;
            if (!qmpOf(rep).imports()) continue;
            if (!rep.mayPlace(stack)) continue;
            out.add(group);
        }
        out.sort((a, b) -> Integer.compare(b.getShiftClickPriority(), a.getShiftClickPriority()));
        return out;
    }

    /** Vanilla's quick-move tail contract: settle the source slot + return. */
    private static ItemStack finishSource(Player player, Slot source,
                                          ItemStack original, ItemStack working) {
        if (working.getCount() == original.getCount()) return ItemStack.EMPTY; // nothing moved
        if (working.isEmpty()) {
            source.setByPlayer(ItemStack.EMPTY);
        } else {
            source.setChanged();
        }
        source.onTake(player, working);
        return original;
    }
}
