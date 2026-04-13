package com.trevorschoeny.menukit.core;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/**
 * In-memory storage that is not persisted. Contents are dropped via
 * the standard {@code dropInventory} pattern when the screen closes.
 *
 * <p>Implements plain {@link Storage}, not {@link PersistentStorage},
 * because there's nothing to save.
 *
 * <p>Replaces the old {@code MKContainerDef.BindingType.EPHEMERAL} +
 * {@code SimpleContainer} pattern.
 */
public class EphemeralStorage implements Storage {

    private final NonNullList<ItemStack> items;

    /** Creates an ephemeral storage with the given number of slots. */
    public EphemeralStorage(int size) {
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    /** Factory method for readability in builder API. */
    public static EphemeralStorage of(int size) {
        return new EphemeralStorage(size);
    }

    @Override
    public ItemStack getStack(int localIndex) {
        return items.get(localIndex);
    }

    @Override
    public void setStack(int localIndex, ItemStack stack) {
        items.set(localIndex, stack);
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public void markDirty() {
        // No persistence — nothing to mark.
    }

    /**
     * Atomically reads all items and clears the storage. Returns the
     * items for the caller to drop into the world or transfer elsewhere.
     */
    public NonNullList<ItemStack> drainContents() {
        NonNullList<ItemStack> collected = NonNullList.withSize(items.size(), ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) {
            collected.set(i, items.get(i).copy());
            items.set(i, ItemStack.EMPTY);
        }
        return collected;
    }
}
