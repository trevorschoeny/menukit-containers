package com.trevorschoeny.menukit.core;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Storage backed by a player-owned item list. Items follow the player
 * everywhere because the storage is persisted to player NBT.
 *
 * <p>Unlike vanilla's {@code Inventory}, this is a standalone item list
 * that doesn't share indices with the player's main inventory. It's
 * a separate Storage that happens to be saved alongside the player.
 *
 * <p>Replaces the old {@code MKContainerDef.BindingType.PLAYER} pattern.
 */
public class PlayerStorage implements PersistentStorage {

    private final NonNullList<ItemStack> items;
    private boolean dirty;

    /** Creates a player storage with the given number of slots. */
    public PlayerStorage(int size) {
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    /** Factory method for readability. */
    public static PlayerStorage of(int size) {
        return new PlayerStorage(size);
    }

    @Override
    public ItemStack getStack(int localIndex) {
        return items.get(localIndex);
    }

    @Override
    public void setStack(int localIndex, ItemStack stack) {
        items.set(localIndex, stack);
        markDirty();
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    /** Returns true if contents have changed since last save. */
    public boolean isDirty() {
        return dirty;
    }

    /** Clears the dirty flag after saving. */
    public void clearDirty() {
        dirty = false;
    }

    @Override
    public void save(ValueOutput output) {
        ValueOutput.ValueOutputList list = output.childrenList("Items");
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                ValueOutput entry = list.addChild();
                entry.putInt("Slot", i);
                entry.store("Item", ItemStack.CODEC, stack.copy());
            }
        }
        output.putInt("Size", items.size());
        dirty = false;
    }

    @Override
    public void load(ValueInput input) {
        // Clear existing contents
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }

        for (ValueInput entry : input.childrenListOrEmpty("Items")) {
            int slot = entry.getInt("Slot").orElse(-1);
            if (slot >= 0 && slot < items.size()) {
                entry.read("Item", ItemStack.CODEC).ifPresent(stack -> {
                    if (!stack.isEmpty()) {
                        items.set(slot, stack);
                    }
                });
            }
        }
        dirty = false;
    }
}
