package com.trevorschoeny.menukit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.trevorschoeny.menukit.core.GatingContext;
import com.trevorschoeny.menukit.core.WindowGating;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THE ONE WINDOW server gating seam — shift-click placement. Wraps the
 * {@code Slot.mayPlace} call inside {@code AbstractContainerMenu.moveItemStackTo}
 * to also consult the window's {@code GATING} behavior, so a gate can deny a
 * shift-click into any slot (vanilla or created) by {@link com.trevorschoeny
 * .menukit.window.Address}. Generalizes Inventory Max's container-lock destination
 * block (a lock is a gate that denies).
 *
 * <p>One of the library's server seams; the rest (pick-all, hopper x3, dispenser,
 * and the merge-pass treat-as-empty refinement) are the same wrapper over
 * {@link WindowGating}.
 */
@Mixin(AbstractContainerMenu.class)
public class MKCGatingMixin {

    @WrapOperation(
            method = "moveItemStackTo",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;mayPlace(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean mkc$gateShiftClickPlace(Slot slot, ItemStack stack, Operation<Boolean> original) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (!WindowGating.mayPlace(self, slot, stack)) return false;
        return original.call(slot, stack);
    }

    /** Pick-all (double-click gather): a gate that denies pickup blocks gathering. */
    @Inject(method = "canTakeItemForPickAll", at = @At("HEAD"), cancellable = true)
    private void mkc$gatePickAll(ItemStack stack, Slot slot, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (!WindowGating.mayPickup(self, slot, GatingContext.current().actingPlayer())) {
            cir.setReturnValue(false);
        }
    }
}

