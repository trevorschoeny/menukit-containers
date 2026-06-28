package com.trevorschoeny.menukit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.trevorschoeny.menukit.core.WindowGating;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THE ONE WINDOW server gating — hopper automation. A gate that denies extract /
 * place blocks a hopper from taking from or moving into a gated slot. Automation
 * has no acting player, so a player-exempting gate (a lock) still enforces here.
 * Generalizes Inventory Max's hopper lock enforcement.
 */
@Mixin(HopperBlockEntity.class)
public class MKCHopperGatingMixin {

    @Inject(method = "tryTakeInItemFromSlot", at = @At("HEAD"), cancellable = true)
    private static void mkc$gateExtract(Hopper hopper, Container container, int slot, Direction direction,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!WindowGating.mayExtractFrom(container, slot)) cir.setReturnValue(false);
    }

    @Inject(method = "tryMoveInItem", at = @At("HEAD"), cancellable = true)
    private static void mkc$gateInsert(Container source, Container destination, ItemStack stack, int slot,
                                       Direction direction, CallbackInfoReturnable<ItemStack> cir) {
        if (!WindowGating.mayPlaceInto(destination, slot, stack)) cir.setReturnValue(stack);
    }

    /** A hopper ejecting its OWN gated slot: treat that slot as empty so it's skipped. */
    @WrapOperation(
            method = "ejectItems",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack mkc$gateHopperEject(HopperBlockEntity hopper, int slot, Operation<ItemStack> original) {
        if (!WindowGating.mayExtractFrom(hopper, slot)) return ItemStack.EMPTY;
        return original.call(hopper, slot);
    }
}
