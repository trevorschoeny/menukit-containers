package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.WindowGating;

import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THE ONE WINDOW server gating — dispenser/dropper. A gate that denies extract
 * skips a gated slot when the dispenser picks a random slot to fire. Generalizes
 * Inventory Max's dispenser lock enforcement.
 */
@Mixin(DispenserBlockEntity.class)
public class MKCDispenserGatingMixin {

    @Inject(method = "getRandomSlot", at = @At("RETURN"), cancellable = true)
    private void mkc$gateDispense(RandomSource random, CallbackInfoReturnable<Integer> cir) {
        int slot = cir.getReturnValueI();
        if (slot >= 0 && !WindowGating.mayExtractFrom((Container) (Object) this, slot)) {
            cir.setReturnValue(-1);
        }
    }
}
