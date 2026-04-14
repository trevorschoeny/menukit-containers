package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.verification.ContractVerification;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Contract-verification test mixin — logs {@link Slot#getItem} invocations
 * for slot classes whose name contains "MenuKit". Demonstrates that an
 * ecosystem mixin into {@code Slot.getItem} composes with MenuKit's
 * inertness handling (MenuKit overrides {@code getItem} on its own
 * subclass; the vanilla HEAD inject fires before and after MenuKit's
 * override, so both contribute).
 *
 * <p>Gated by {@link ContractVerification#isActive()} — no-op when not
 * running a verification pass.
 *
 * <p>This test observes substitutability: MenuKit slots present as
 * vanilla {@link Slot} to the ecosystem, so {@code @Inject} at
 * {@code Slot.getItem HEAD} fires on them exactly as it would on any
 * other {@link Slot} subclass.
 *
 * <p><b>Phase 5 verification scaffolding — removed after the report.</b>
 */
@Mixin(Slot.class)
public class VerifyGetItemMixin {

    @Inject(method = "getItem", at = @At("RETURN"))
    private void menuKit$verifySubstitutability(CallbackInfoReturnable<ItemStack> cir) {
        if (!ContractVerification.isActive()) return;

        Slot self = (Slot) (Object) this;
        String slotClass = self.getClass().getName();

        // Only log MenuKit-managed slot invocations — the test is
        // "does this ecosystem-style mixin observe MK slots." Logging
        // every vanilla slot would bury the signal.
        if (!slotClass.contains("MenuKit")) return;

        ItemStack result = cir.getReturnValue();
        String resultDesc = result.isEmpty()
                ? "EMPTY"
                : result.getItem().toString() + "x" + result.getCount();

        ContractVerification.LOGGER.info(
                "[Verify.Substitutability] getItem RETURN on MK slot class={} result={}",
                self.getClass().getSimpleName(), resultDesc);
    }
}
