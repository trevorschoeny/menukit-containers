package com.trevorschoeny.menukit.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * MenuKit-Containers internal accessor — exposes vanilla's two {@code private
 * final} halves of a {@link CompoundContainer} (the double chest's backing
 * pair) so M1's container resolver can split a composite container's global
 * slot index to the owning half's block-entity and its local index (§0050).
 *
 * <p><b>§0019 / §0050 note.</b> This is a pure access shim: it reads two
 * existing vanilla fields, injects no behavior, registers no hook, and owns no
 * code path. The composite-resolution <em>policy</em> (which half owns which
 * slot, where the value is stored) lives in {@code ContainerKeyResolver}; this
 * accessor only lets the resolver reach fields vanilla keeps private. It mirrors
 * {@link AbstractContainerMenuInvoker} (the established access-shim pattern
 * §0019 permits) and MK's {@code SlotPositionAccessor}.
 *
 * <p>Applies on both sides (the class loads client- and server-side), though
 * {@code CompoundContainer} <em>instances</em> back double chests only on the
 * server — the client builds a flat {@code SimpleContainer} for the same menu,
 * so resolution never runs against a compound on the client. It therefore lives
 * in the general mixin block, not the client block.
 */
@ApiStatus.Internal
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {

    /** The first backing half (slots {@code 0 .. container1.size-1} of the global range). */
    @Accessor("container1")
    Container mk$getContainer1();

    /** The second backing half (the remainder of the global range). */
    @Accessor("container2")
    Container mk$getContainer2();
}
