package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerMenuInvoker;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Library-owned shift-click (quick-move) routing for grafted slots on a
 * <b>foreign</b> menu — a chest/furnace/donkey menu MenuKit didn't build, that a
 * graft has been projected onto ({@link GraftProjection}). The generalization of
 * {@code MenuKitScreenHandler.quickMoveStack}'s declarative routing to a menu
 * whose own vanilla {@code quickMoveStack} knows nothing about the appended graft
 * tail block.
 *
 * <h3>Why it's needed</h3>
 *
 * Foreign menus compute their quick-move index ranges against the
 * <em>unprojected</em> slot count, so appended grafts sit outside every vanilla
 * destination range: a shift-click <em>into</em> the menu never targets a graft,
 * and a shift-click <em>out of</em> a graft hits vanilla code that has no case for
 * the source index. This routes both directions through vanilla's own
 * {@code moveItemStackTo} (the absorbed {@link AbstractContainerMenuInvoker}
 * invoker), so the merge/place math and the tail contract stay vanilla-exact — the
 * library moves the consumer's own slot, it invents no placement behavior.
 *
 * <h3>§0019 — opt-in, consumer-triggered</h3>
 *
 * The consumer keeps the one-line {@code @Inject(HEAD, cancellable)} on each
 * foreign menu's {@code quickMoveStack} and delegates here (MK/MKC never install
 * the interception). Whether a graft <em>vacuums</em> shift-clicks on a foreign
 * screen is opt-in per graft via {@link QuickMoveParticipation#imports()} +
 * {@link SlotGroup#canAccept} — a graft created with {@code imports() == false}
 * for its projected copy stays passive on chests. {@link #route} returns
 * {@link ItemStack#EMPTY} when no graft claims the move, which the consumer's HEAD
 * inject treats as "not handled — let vanilla run."
 */
public final class GraftQuickMove {

    private GraftQuickMove() {}

    /**
     * Routes a shift-click at {@code index} considering the grafts on {@code menu}.
     * Returns the moved stack (vanilla's {@code quickMoveStack} contract: the
     * original stack when something moved so the caller loops, {@link ItemStack#EMPTY}
     * when nothing moved). EMPTY also means "not a graft concern — let vanilla
     * handle it"; the consumer's HEAD inject only cancels on a non-EMPTY return.
     */
    public static ItemStack route(AbstractContainerMenu menu, Player player, int index) {
        if (index < 0 || index >= menu.slots.size()) return ItemStack.EMPTY;

        // Discover the graft groups present on this menu + where the graft tail
        // block begins (everything before it is the menu's own vanilla slots).
        Set<SlotGroup> graftGroups = new LinkedHashSet<>();
        int firstGraftIndex = menu.slots.size();
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i) instanceof MenuKitSlot mk) {
                graftGroups.add(mk.getGroup());
                if (i < firstGraftIndex) firstGraftIndex = i;
            }
        }
        if (graftGroups.isEmpty()) return ItemStack.EMPTY; // no grafts here — vanilla's job

        Slot source = menu.slots.get(index);
        if (source instanceof MenuKitSlot mkSource) {
            return routeOutOfGraft(menu, player, mkSource, graftGroups, firstGraftIndex);
        }
        return routeIntoGrafts(menu, player, source, graftGroups);
    }

    /**
     * Shift-click out of a grafted slot: route its item to other importing graft
     * groups (declarative priority) first, then the menu's vanilla slots.
     */
    private static ItemStack routeOutOfGraft(AbstractContainerMenu menu, Player player,
                                             MenuKitSlot source, Set<SlotGroup> graftGroups,
                                             int firstGraftIndex) {
        SlotGroup sourceGroup = source.getGroup();
        if (source.isInert() || !source.hasItem()) return ItemStack.EMPTY;
        if (!sourceGroup.getQmp().exports()) return ItemStack.EMPTY;

        ItemStack original = source.getItem().copy();
        ItemStack working = source.getItem();
        AbstractContainerMenuInvoker mover = (AbstractContainerMenuInvoker) menu;

        // 1. Other graft groups that import + accept, highest priority first.
        for (SlotGroup candidate : sortedImporters(graftGroups, sourceGroup, working)) {
            if (working.isEmpty()) break;
            mover.menukit$moveItemStackTo(working,
                    candidate.getFlatIndexStart(), candidate.getFlatIndexEnd(), false);
        }
        // 2. The menu's own vanilla slots (chest storage + player inventory), in
        //    menu order. Grafts sit in the tail block [firstGraftIndex, size); the
        //    vanilla region is everything before it.
        if (!working.isEmpty() && firstGraftIndex > 0) {
            mover.menukit$moveItemStackTo(working, 0, firstGraftIndex, false);
        }

        return finishSource(player, source, original, working);
    }

    /**
     * Shift-click a vanilla slot into the grafts: route into importing graft
     * groups. Returns EMPTY (let vanilla handle the normal chest↔inventory move)
     * when no graft imports/accepts the stack or nothing actually moved.
     */
    private static ItemStack routeIntoGrafts(AbstractContainerMenu menu, Player player,
                                             Slot source, Set<SlotGroup> graftGroups) {
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;

        ItemStack working = source.getItem();
        List<SlotGroup> importers = sortedImporters(graftGroups, null, working);
        if (importers.isEmpty()) return ItemStack.EMPTY; // nothing wants it → vanilla's job

        ItemStack original = working.copy();
        AbstractContainerMenuInvoker mover = (AbstractContainerMenuInvoker) menu;
        for (SlotGroup candidate : importers) {
            if (working.isEmpty()) break;
            mover.menukit$moveItemStackTo(working,
                    candidate.getFlatIndexStart(), candidate.getFlatIndexEnd(), false);
        }
        if (working.getCount() == original.getCount()) return ItemStack.EMPTY; // grafts took nothing

        return finishSource(player, source, original, working);
    }

    /** Candidate graft groups that import + can accept the stack, sorted by priority desc. */
    private static List<SlotGroup> sortedImporters(Set<SlotGroup> groups, SlotGroup exclude,
                                                   ItemStack stack) {
        List<SlotGroup> out = new ArrayList<>();
        for (SlotGroup group : groups) {
            if (group == exclude) continue;
            if (!group.getQmp().imports()) continue;
            if (!group.canAccept(stack)) continue;
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
