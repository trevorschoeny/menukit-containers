package com.trevorschoeny.menukit.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.trevorschoeny.menukit.core.MKCSlot;
import com.trevorschoeny.menukit.core.WindowGating;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THE ONE WINDOW gating on PLAIN (non-shift) clicks — the seam that makes a gate set
 * on a vanilla slot actually bite a direct left/right click.
 *
 * <p>Why at the {@link Slot} level and not in {@code doClick}: the real mutation runs
 * through {@code Slot.safeInsert}/{@code safeTake}/{@code tryRemove}, which consult
 * {@code mayPlace}/{@code mayPickup} <em>internally</em> — a wrap on those calls inside
 * {@code doClick} never sees them, and the stack cap is {@code getMaxStackSize}, not a
 * {@code mayPlace} concern at all. Gating the three predicates here funnels every path
 * (direct click, shift-click's {@code moveItemStackTo}, hopper/dispenser go through the
 * container seams) through the window's {@code GATING} — exactly how {@link MKCSlot}
 * already gates created slots.
 *
 * <p>A vanilla slot's gate is found from its CONTAINER address (the same §0050 address
 * the set-time path mints), so a gate set on the player inventory by index is found on
 * the matching slot regardless of which menu shows it (parity-safe). {@link MKCSlot}s
 * are skipped — they self-gate in their own overrides (which call {@code super}, so this
 * would otherwise double-apply). Zero cost when nothing is gated (the empty-bindings
 * fast-path in {@link WindowGating}).
 */
@Mixin(Slot.class)
public class MKCVanillaSlotGatingMixin {

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void mkc$gateMayPlace(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof MKCSlot) return;   // created slots self-gate
        if (!WindowGating.mayPlaceAt((Slot) (Object) this, stack)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void mkc$gateMayPickup(Player player, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof MKCSlot) return;
        Slot self = (Slot) (Object) this;
        // Curse of Binding (BINDING set on this slot): a bound item can't be removed
        // while alive, survival only — the vanilla-slot twin of MKCSlot's binding check.
        if (WindowGating.bindingDeniesPickup(self, player)) {
            cir.setReturnValue(false);
            return;
        }
        if (!WindowGating.mayPickupAt(self, player)) {
            cir.setReturnValue(false);
        }
    }

    @ModifyReturnValue(method = "getMaxStackSize(Lnet/minecraft/world/item/ItemStack;)I", at = @At("RETURN"))
    private int mkc$gateMaxStack(int vanillaMax, @Local(argsOnly = true) ItemStack stack) {
        if ((Object) this instanceof MKCSlot) return vanillaMax;
        return WindowGating.maxStackAt((Slot) (Object) this, stack, vanillaMax);
    }
}
