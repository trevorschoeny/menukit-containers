package com.trevorschoeny.menukit.core;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Adapts a MenuKit {@link Storage} to vanilla's {@link Container} interface.
 * Used during slot construction — {@link net.minecraft.world.inventory.Slot}'s
 * constructor takes a Container, but MenuKit consumers declare storage via
 * the narrower {@link Storage} interface.
 *
 * <p>Used by {@link com.trevorschoeny.menukit.screen.MenuKitScreenHandler}
 * for MenuKit-native handlers and by {@link SlotInjector} for vanilla-handler
 * grafting. Consumers never interact with this directly.
 */
public class StorageContainerAdapter implements Container {

    private final Storage storage;

    public StorageContainerAdapter(Storage storage) {
        this.storage = storage;
    }

    @Override public int getContainerSize() { return storage.size(); }

    @Override
    public int getMaxStackSize() {
        return 99; // vanilla SimpleContainer default; items cap themselves
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < storage.size(); i++) {
            if (!storage.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    @Override public ItemStack getItem(int slot) { return storage.getStack(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack current = storage.getStack(slot);
        if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack removed = current.split(amount);
        if (current.isEmpty()) storage.setStack(slot, ItemStack.EMPTY);
        else storage.setStack(slot, current);
        storage.markDirty();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = storage.getStack(slot);
        storage.setStack(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) { storage.setStack(slot, stack); }

    @Override public void setChanged() { storage.markDirty(); }
    @Override public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {
        for (int i = 0; i < storage.size(); i++) {
            storage.setStack(i, ItemStack.EMPTY);
        }
    }
}
