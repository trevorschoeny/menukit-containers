package com.trevorschoeny.menukit.core;

import net.minecraft.world.item.ItemStack;

/**
 * Where items live. Narrow data interface — get, set, size, markDirty.
 *
 * <p>Storage is deliberately minimal. It does not know who owns it, when
 * to save, or which player it belongs to. Those concerns live on
 * {@link com.trevorschoeny.menukit.screen.MenuKitScreenHandler} or a
 * binding registry — never here.
 *
 * <p>Each Storage is adapted internally to vanilla's {@code Container}
 * interface by the handler during slot construction. Consumers never
 * see {@code Container}; they see Storage.
 *
 * <p>For implementations that need save/load, see {@link PersistentStorage}.
 *
 * @see PersistentStorage
 * @see PlayerStorage
 * @see EphemeralStorage
 * @see VirtualStorage
 */
public interface Storage {

    /** Returns the stack at the given local index. */
    ItemStack getStack(int localIndex);

    /** Sets the stack at the given local index. */
    void setStack(int localIndex, ItemStack stack);

    /** Returns the number of slots in this storage. */
    int size();

    /** Marks this storage as dirty (contents changed). */
    void markDirty();
}
