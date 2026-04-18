package com.trevorschoeny.menukit.core;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A vanilla {@link Slot} to the outside world. Carries its coordinates
 * in the hierarchy as final fields and delegates behavior to its owning
 * {@link SlotGroup}.
 *
 * <p>This is the substitutability contract in code. Every override either
 * calls {@code super} or delegates in a way that preserves vanilla's
 * behavioral contract:
 * <ul>
 *   <li>{@code mayPlace} — group policy AND super (mixin chain composes)</li>
 *   <li>{@code mayPickup} — group policy AND super</li>
 *   <li>{@code getMaxStackSize} — min of group limit and super</li>
 *   <li>{@code getItem} — returns EMPTY when inert, otherwise super</li>
 *   <li>{@code isActive} — returns false when panel is hidden</li>
 * </ul>
 *
 * <p>A mod that mixins into {@code Slot.mayPlace} to add a global filter
 * sees its filter run on MenuKit slots via the {@code super.mayPlace} call.
 * The most restrictive answer wins. A mod iterating slots sees inert
 * MenuKit slots as empty, which is a valid state for any slot.
 *
 * <p>Part of the canonical MenuKit hierarchy:
 * Screen → Panel → SlotGroup → MenuKitSlot
 */
public class MenuKitSlot extends Slot {

    // ── Coordinates in the hierarchy (final — never desync) ─────────────
    private final String panelId;
    private final String groupId;
    private final int localIndex;

    // ── Owning group (final — behavior delegation target) ───────────────
    private final SlotGroup group;

    // ── Owning panel (final — visibility query target for inertness) ────
    private final Panel panel;

    /**
     * @param container      vanilla Container adapter (from handler construction)
     * @param containerIndex index within the container
     * @param x              screen x position
     * @param y              screen y position
     * @param group          owning SlotGroup (behavior delegation target)
     * @param panel          owning Panel (visibility query target for inertness)
     * @param groupId        group identifier within the panel
     * @param localIndex     slot index within the group (0-based)
     */
    public MenuKitSlot(Container container, int containerIndex, int x, int y,
                       SlotGroup group, Panel panel, String groupId,
                       int localIndex) {
        super(container, containerIndex, x, y);
        this.group = group;
        this.panel = panel;
        this.panelId = panel.getId();
        this.groupId = groupId;
        this.localIndex = localIndex;
    }

    // ── Coordinate Accessors ────────────────────────────────────────────

    /** Panel this slot belongs to. */
    public String getPanelId() { return panelId; }

    /** Group this slot belongs to within its panel. */
    public String getGroupId() { return groupId; }

    /** Index within the group (0-based). */
    public int getLocalIndex() { return localIndex; }

    /** The SlotGroup that owns this slot's behavior. */
    public SlotGroup getGroup() { return group; }

    // ── Inertness ───────────────────────────────────────────────────────

    /**
     * Returns true when this slot's panel is hidden. An inert slot is
     * indistinguishable from a non-existent one to the outside world:
     * getItem returns EMPTY, canInsert returns false, quick-move skips it.
     *
     * <p>Exposed for well-behaved third parties that want to check
     * explicitly rather than relying on the behavioral methods.
     */
    public boolean isInert() {
        return !panel.isVisible();
    }

    // ── Behavioral Overrides (the substitutability contract) ────────────
    //
    // Each override composes with super so that other mods' mixins into
    // Slot run in the chain. MenuKit's additions layer ON TOP of vanilla
    // behavior, never replace it.

    /**
     * Can this stack be placed in this slot?
     *
     * <p>Composes: group policy AND super. A mixin on {@code Slot.mayPlace}
     * runs via the super call and its result is AND-composed with the
     * group's policy. The most restrictive answer wins.
     */
    @Override
    public boolean mayPlace(ItemStack stack) {
        if (isInert()) return false;
        return group.canAccept(stack) && super.mayPlace(stack);
    }

    /**
     * Can items be taken from this slot?
     *
     * <p>Composes: group policy AND super. Same composition pattern
     * as mayPlace.
     */
    @Override
    public boolean mayPickup(Player player) {
        if (isInert()) return false;
        // canRemove checks the current item — get it from storage, not
        // from getItem() which would return EMPTY if inert (but we already
        // checked inertness above).
        return group.canRemove(super.getItem()) && super.mayPickup(player);
    }

    /**
     * Max stack size for this slot, given a specific item.
     *
     * <p>Takes the minimum of the group's policy limit and vanilla's limit
     * (which includes any mixin modifications via super).
     */
    @Override
    public int getMaxStackSize(ItemStack stack) {
        return Math.min(group.maxStackSize(stack), super.getMaxStackSize(stack));
    }

    // No override for getMaxStackSize() (no-arg). The policy's maxStackSize
    // function is item-specific — calling it with ItemStack.EMPTY returns 1,
    // which would break everything. Vanilla's Slot.getMaxStackSize() returns
    // container.getMaxStackSize() (99), which is correct as the slot-level
    // capacity limit. Item-specific limits are handled by the ItemStack overload above.

    // getItem() is NOT overridden. Vanilla's Slot.getItem() flows through
    // the Container (StorageContainerAdapter) directly. Hidden-panel slots
    // are protected by: isActive()=false (UI skips render/interaction),
    // mayPlace()=false (moveItemStackTo skips insertion), mayPickup()=false
    // (safeTake blocks extraction). broadcastChanges syncs real items for
    // hidden slots — the client Container has accurate data, but the UI
    // doesn't render it. When the panel becomes visible, no re-sync is
    // needed because the data was never falsified.

    /**
     * Returns whether this slot is active (visible and interactable).
     *
     * <p>When false, vanilla skips this slot for rendering and hover
     * detection. Combined with getItem returning EMPTY, this makes
     * hidden slots truly invisible to the world.
     */
    @Override
    public boolean isActive() {
        if (isInert()) return false;
        return super.isActive();
    }
}
