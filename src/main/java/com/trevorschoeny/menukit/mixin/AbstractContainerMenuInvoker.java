package com.trevorschoeny.menukit.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * MenuKit-Containers internal invoker — exposes vanilla's {@code protected}
 * {@link AbstractContainerMenu#addSlot(Slot)} so the consumer-invoked
 * slot-graft helper ({@link com.trevorschoeny.menukit.core.MenuKitGraft})
 * can append grafted slots to a vanilla menu it does not subclass.
 *
 * <p><b>§0019 / §0045 note.</b> This is a pure access shim: it exposes an
 * existing vanilla method, injects no behavior, registers no hook, and owns
 * no code path. The graft itself is performed by the <em>consumer's</em>
 * mixin (which calls the helper); this invoker only lets the helper reach a
 * {@code protected} method from outside the menu's class hierarchy. It
 * mirrors MK's {@code SlotPositionAccessor} ({@code @Accessor} on vanilla
 * {@code Slot}) — the established access-shim pattern §0019 permits, distinct
 * from the "decorate every screen" hooks and registration surfaces §0019
 * forbids.
 *
 * <p>Applies on both sides (the vanilla menu is constructed client- and
 * server-side; the graft runs in both), so it lives in the general mixin
 * block, not the client block.
 */
@ApiStatus.Internal
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuInvoker {

    /**
     * Calls vanilla's {@code addSlot} — appends the slot to {@code this.slots}
     * (assigning {@code slot.index}) and grows the parallel sync-tracking
     * lists ({@code lastSlots} / {@code remoteSlots}) so {@code broadcastChanges}
     * stays consistent. Replicating this by hand would desync those lists, so
     * the invoker reuses vanilla's exact bookkeeping.
     */
    @Invoker("addSlot")
    Slot menukit$addSlot(Slot slot);
}
