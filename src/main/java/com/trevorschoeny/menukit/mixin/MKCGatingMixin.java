package com.trevorschoeny.menukit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
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
 * <p>Two passes, two seams. {@code moveItemStackTo} runs a <b>merge pass</b>
 * (fold the moving stack into matching non-empty slots — checks {@code getItem},
 * never {@code mayPlace}) then an <b>empty pass</b> (drop into the first empty
 * placeable slot — checks {@code mayPlace}). The {@code mayPlace} wrap below
 * guards the empty pass; the {@code getItem} wrap guards the merge pass by
 * treating a gate-denied slot as empty, or a shift-click would merge into a slot
 * a click can't place into. Both consult the same {@link WindowGating} decision.
 *
 * <p>One of the library's server seams; the rest (pick-all, hopper x3, dispenser)
 * are the same wrapper over {@link WindowGating}.
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

    /**
     * Merge-pass refinement. The merge pass folds the moving stack into matching
     * non-empty slots without ever consulting {@code mayPlace}, so without this a
     * shift-click would top off a stack sitting in a slot the gate forbids. We
     * return {@code EMPTY} when the gate denies the moving stack — vanilla then
     * sees nothing to merge into and skips the slot; the empty pass that follows
     * hits the {@code mayPlace} wrap above, which denies too. Stack-specific
     * (unlike Inventory Max's all-or-nothing lock): a gate may allow some items,
     * so we evaluate the actual moving stack, captured from the method args.
     */
    @WrapOperation(
            method = "moveItemStackTo",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack mkc$gateShiftClickMerge(Slot slot, Operation<ItemStack> original,
                                              @Local(argsOnly = true) ItemStack movingStack) {
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (!WindowGating.mayPlace(self, slot, movingStack)) return ItemStack.EMPTY;
        return original.call(slot);
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

