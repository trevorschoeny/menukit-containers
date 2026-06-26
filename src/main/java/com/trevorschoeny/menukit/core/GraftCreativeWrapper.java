package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.SlotWrapperAccessor;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A library-built stand-in for vanilla's package-private
 * {@code CreativeModeInventoryScreen$SlotWrapper}, used to carry a grafted
 * {@link MenuKitSlot} into the creative <em>non-inventory</em> tabs so it draws
 * and routes there exactly as it does on the inventory tab (§0051 creative parity,
 * extended to all tabs).
 *
 * <h3>Why a custom wrapper</h3>
 *
 * Vanilla only wraps {@code player.inventoryMenu}'s slots — grafts included — into
 * the {@code ItemPickerMenu} on the <b>inventory</b> tab; the non-inventory tabs
 * carry a fixed base slot set (the item grid + hotbar) that never sees the graft,
 * so a graft is invisible and unclickable there. We append one of these wrappers
 * per graft to that base set ({@code MenuKitGraftCreativeItemPickerMixin}) so the
 * existing graft dispatch — which walks {@code menu.slots} and unwraps via
 * {@link com.trevorschoeny.menukit.inject.Slots#target} — discovers the graft on
 * every tab. We can't construct vanilla's {@code SlotWrapper} (package-private),
 * so this mirrors its delegation and, crucially, implements the same
 * {@link SlotWrapperAccessor} unwrap seam MenuKit applies to vanilla's wrapper —
 * so {@code Slots.target}/{@code GraftSlots.asGraft} resolve it to the graft with
 * no special case.
 *
 * <h3>Parked, like the inventory-tab wrappers</h3>
 *
 * Constructed off-screen (the screen dispatcher draws the graft at its live
 * {@code graftX/graftY} instead), and its placement clicks are re-routed to the
 * proven inventory-tab path by {@code MenuKitGraftCreativeClickRouteMixin}. The
 * delegation below keeps vanilla's own reads/writes (item, may-place, take, etc.)
 * faithful for anything that does touch the wrapper directly.
 *
 * <p>No client types — universal-safe — though only the client creative path ever
 * constructs it.
 */
public final class GraftCreativeWrapper extends Slot implements SlotWrapperAccessor {

    private final MenuKitSlot target;

    /**
     * @param target the grafted slot on {@code player.inventoryMenu} this stands in for
     * @param x      parked screen x (the dispatcher draws the graft at {@code graftX} instead)
     * @param y      parked screen y
     */
    public GraftCreativeWrapper(MenuKitSlot target, int x, int y) {
        super(target.container, target.getContainerSlot(), x, y);
        this.target = target;
    }

    /** The unwrap seam — resolves this wrapper back to its graft (see {@link SlotWrapperAccessor}). */
    @Override
    public Slot menuKit$getTarget() {
        return this.target;
    }

    // ── Faithful delegation to the wrapped graft (mirrors vanilla SlotWrapper) ──

    @Override
    public void onTake(Player player, ItemStack stack) {
        this.target.onTake(player, stack);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return this.target.mayPlace(stack);
    }

    @Override
    public ItemStack getItem() {
        return this.target.getItem();
    }

    @Override
    public boolean hasItem() {
        return this.target.hasItem();
    }

    @Override
    public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
        this.target.setByPlayer(newStack, oldStack);
    }

    @Override
    public void set(ItemStack stack) {
        this.target.set(stack);
    }

    @Override
    public void setChanged() {
        this.target.setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return this.target.getMaxStackSize();
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return this.target.getMaxStackSize(stack);
    }

    @Override
    public ItemStack remove(int amount) {
        return this.target.remove(amount);
    }

    @Override
    public boolean isActive() {
        return this.target.isActive();
    }

    @Override
    public boolean mayPickup(Player player) {
        return this.target.mayPickup(player);
    }
}
