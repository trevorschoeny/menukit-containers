package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.state.PerPlayerSlotStateBag;
import com.trevorschoeny.menukit.state.SlotStateAttachments;
import com.trevorschoeny.menukit.state.SlotStateComponents;

import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * §0048 — the library-owned block-portable bridge for M1 per-slot metadata.
 *
 * <p>v1 owner: the shulker box. Its contents travel block↔item via vanilla's
 * {@code minecraft:container} component, but MenuKit's M1 metadata lives in a
 * Fabric attachment, which does <em>not</em> travel (only declared components
 * do, post-1.20.5). This mixin rides vanilla's own component intermediary — the
 * same path the contents use — to carry the whole per-BlockEntity M1 bag
 * block↔item inside one generic component
 * ({@link SlotStateComponents#PORTABLE_SLOT_STATE}).
 *
 * <p><b>Target.</b> {@code ShulkerBoxBlockEntity} does not itself declare
 * {@code collectImplicitComponents}/{@code applyImplicitComponents} — it
 * inherits them from {@link RandomizableContainerBlockEntity}. So the mixin
 * targets that parent and gates each inject on {@code instanceof
 * ShulkerBoxBlockEntity}: it fires for every randomizable container but acts
 * only for shulkers (the v1 block-portable owner). Chests, barrels, etc. are
 * not block-portable and are skipped. A second block-portable type is a §0048
 * review trigger (confirm it exposes the component intermediary).
 *
 * <p>The M1 attachment stays source-of-truth
 * ({@link SlotStateAttachments#BLOCK_ENTITY}); the component is travel-only,
 * copied defensively in both directions so it never aliases the live bag.
 * Generic over registered channels — no per-feature schema (§0048 / §0019).
 */
@ApiStatus.Internal
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class ShulkerBoxPortableStateMixin {

    /** BE → item: emit the M1 bag as the travel component (snapshot copy). */
    @Inject(method = "collectImplicitComponents", at = @At("TAIL"))
    private void menukit$emitPortableSlotState(DataComponentMap.Builder components, CallbackInfo ci) {
        if (!((Object) this instanceof ShulkerBoxBlockEntity)) return; // block-portable: shulkers only
        BlockEntity be = (BlockEntity) (Object) this;
        if (!be.hasAttached(SlotStateAttachments.BLOCK_ENTITY)) return;
        PerPlayerSlotStateBag bag = be.getAttached(SlotStateAttachments.BLOCK_ENTITY);
        if (bag != null && !bag.isEmpty()) {
            components.set(SlotStateComponents.PORTABLE_SLOT_STATE,
                    new PerPlayerSlotStateBag(bag.backing().copy()));
        }
    }

    /** item → BE: restore the M1 bag from the travel component (fresh copy). */
    @Inject(method = "applyImplicitComponents", at = @At("TAIL"))
    private void menukit$restorePortableSlotState(DataComponentGetter input, CallbackInfo ci) {
        if (!((Object) this instanceof ShulkerBoxBlockEntity)) return; // block-portable: shulkers only
        PerPlayerSlotStateBag bag = input.get(SlotStateComponents.PORTABLE_SLOT_STATE);
        if (bag != null) {
            BlockEntity be = (BlockEntity) (Object) this;
            be.setAttached(SlotStateAttachments.BLOCK_ENTITY,
                    new PerPlayerSlotStateBag(bag.backing().copy()));
            be.setChanged();
        }
    }
}
