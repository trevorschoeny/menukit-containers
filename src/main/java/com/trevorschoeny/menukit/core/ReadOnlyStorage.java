package com.trevorschoeny.menukit.core;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Read-only {@link Storage} adapter for observed (non-MenuKit) screens.
 * Reads live from the wrapped vanilla {@link Slot}s' backing container.
 *
 * <p><b>Writes are no-ops.</b> MenuKit doesn't mutate handlers it doesn't
 * own — this is the library-not-platform boundary. Consumers wanting to
 * mutate vanilla slots do so through vanilla's own APIs (the handler's
 * {@code slotClick}, for example), not through MenuKit's Storage interface.
 *
 * <p>{@code markDirty()} delegates to the backing container's
 * {@code setChanged()} for consumers that read then want to signal
 * a change they made through vanilla's API.
 *
 * @see VirtualSlotGroup
 */
public class ReadOnlyStorage implements Storage {

    private final List<Slot> slots;
    private final Container container;

    /**
     * @param slots     the vanilla slots this storage wraps (in group order)
     * @param container the backing container (for markDirty delegation)
     */
    ReadOnlyStorage(List<Slot> slots, Container container) {
        this.slots = List.copyOf(slots);
        this.container = container;
    }

    @Override
    public ItemStack getStack(int localIndex) {
        return slots.get(localIndex).getItem();
    }

    /**
     * No-op. MenuKit doesn't mutate handlers it doesn't own.
     *
     * <p>If you need to modify items in a vanilla screen, use the
     * handler's {@code slotClick} method or vanilla's Container API
     * directly — not MenuKit's Storage interface.
     */
    @Override
    public void setStack(int localIndex, ItemStack stack) {
        // Read-only — intentional no-op.
    }

    @Override
    public int size() {
        return slots.size();
    }

    /**
     * Delegates to the backing container's {@code setChanged()}.
     * Call this after modifying items through vanilla's own API to
     * signal that the container's contents have changed.
     */
    @Override
    public void markDirty() {
        container.setChanged();
    }
}
