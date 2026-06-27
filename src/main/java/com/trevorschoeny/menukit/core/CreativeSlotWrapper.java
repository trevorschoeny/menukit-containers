package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.SlotWrapperAccessor;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A library-built stand-in for vanilla's package-private
 * {@code CreativeModeInventoryScreen$SlotWrapper}, used to carry a registered
 * {@link MKCSlot} into the creative <em>non-inventory</em> tabs so it draws
 * and routes there exactly as it does on the inventory tab (§0051 creative parity,
 * extended to all tabs).
 *
 * <h3>Why a custom wrapper</h3>
 *
 * Vanilla only wraps {@code player.inventoryMenu}'s slots — slots included — into
 * the {@code ItemPickerMenu} on the <b>inventory</b> tab; the non-inventory tabs
 * carry a fixed base slot set (the item grid + hotbar) that never sees the slot,
 * so a slot is invisible and unclickable there. We append one of these wrappers
 * per slot to that base set ({@code MKCCreativeSlotItemPickerMixin}) so the
 * existing slot dispatch — which walks {@code menu.slots} and unwraps via
 * {@link com.trevorschoeny.menukit.inject.Slots#target} — discovers the slot on
 * every tab. We can't construct vanilla's {@code SlotWrapper} (package-private),
 * so this mirrors its delegation and, crucially, implements the same
 * {@link SlotWrapperAccessor} unwrap seam MenuKit applies to vanilla's wrapper —
 * so {@code Slots.target}/{@code MKCSlotAccess.asMKCSlot} resolve it to the slot with
 * no special case.
 *
 * <h3>Parked, like the inventory-tab wrappers</h3>
 *
 * Constructed off-screen (the screen dispatcher draws the slot at its live
 * {@code renderX/renderY} instead), and its placement clicks are re-routed to the
 * proven inventory-tab path by {@code MKCCreativeSlotClickRouteMixin}. The
 * delegation below keeps vanilla's own reads/writes (item, may-place, take, etc.)
 * faithful for anything that does touch the wrapper directly.
 *
 * <p>No client types — universal-safe — though only the client creative path ever
 * constructs it.
 */
public final class CreativeSlotWrapper extends Slot implements SlotWrapperAccessor {

    private final MKCSlot target;

    /**
     * @param target the registered slot on {@code player.inventoryMenu} this stands in for
     * @param x      parked screen x (the dispatcher draws the slot at {@code renderX} instead)
     * @param y      parked screen y
     */
    public CreativeSlotWrapper(MKCSlot target, int x, int y) {
        super(target.container, target.getContainerSlot(), x, y);
        this.target = target;
    }

    /** The unwrap seam — resolves this wrapper back to its slot (see {@link SlotWrapperAccessor}). */
    @Override
    public Slot mk$getTarget() {
        return this.target;
    }

    // ── Faithful delegation to the wrapped slot (mirrors vanilla SlotWrapper) ──

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
