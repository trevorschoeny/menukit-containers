package com.trevorschoeny.menukit.core;

import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;
import java.util.function.IntFunction;

/**
 * The escape hatch. Backed by a supplier (read) and a mutator (write),
 * so consumers can bridge any external data source into MenuKit's
 * Storage interface.
 *
 * <p>Implements plain {@link Storage}, not {@link PersistentStorage}.
 * Persistence is the external source's responsibility, not ours.
 *
 * <p>Absorbs the old {@code MKContainerSource} / {@code LiveContainerSource}
 * pattern where consumers provided arbitrary read/write callbacks.
 */
public class VirtualStorage implements Storage {

    private final int size;
    private final IntFunction<ItemStack> getter;
    private final BiConsumer<Integer, ItemStack> setter;
    private final Runnable dirtyCallback;

    /**
     * @param size          number of slots
     * @param getter        (localIndex) -> stack at that index
     * @param setter        (localIndex, stack) -> write stack at that index
     * @param dirtyCallback called when markDirty() is invoked
     */
    public VirtualStorage(int size, IntFunction<ItemStack> getter,
                          BiConsumer<Integer, ItemStack> setter,
                          Runnable dirtyCallback) {
        this.size = size;
        this.getter = getter;
        this.setter = setter;
        this.dirtyCallback = dirtyCallback;
    }

    /** Convenience: no-op dirty callback. */
    public VirtualStorage(int size, IntFunction<ItemStack> getter,
                          BiConsumer<Integer, ItemStack> setter) {
        this(size, getter, setter, () -> {});
    }

    @Override
    public ItemStack getStack(int localIndex) {
        return getter.apply(localIndex);
    }

    @Override
    public void setStack(int localIndex, ItemStack stack) {
        setter.accept(localIndex, stack);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void markDirty() {
        dirtyCallback.run();
    }
}
