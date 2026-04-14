package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.verification.ContractVerification;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Contract-verification test mixin — injects a global filter into vanilla
 * {@link Slot#mayPlace} that rejects cobblestone. Gated by
 * {@link ContractVerification#isActive()} so it's a no-op outside of a
 * {@code /mkverify composability} run.
 *
 * <p>This exists to empirically demonstrate that ecosystem-wide slot
 * enhancements (the pattern used by mods that mixin into {@code Slot}
 * to add global behaviors) fire identically on:
 * <ul>
 *   <li>Vanilla {@code Slot} instances (chests, furnaces, etc.)</li>
 *   <li>MenuKit {@code MenuKitSlot} instances (which extend {@code Slot})</li>
 * </ul>
 *
 * <p>That's the composability guarantee — MenuKit doesn't disrupt the
 * ecosystem; ecosystem mixins apply to MenuKit slots because they
 * substitute for vanilla slots.
 *
 * <p>Part of the contract-verification harness. Lives in the repo so each
 * phase can re-run verification cheaply after refactoring.
 */
@Mixin(Slot.class)
public class VerifyMayPlaceMixin {

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void menuKit$verifyComposability(ItemStack stack,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (!ContractVerification.isActive()) return;

        Slot self = (Slot) (Object) this;
        String slotClass = self.getClass().getSimpleName();
        String stackDesc = stack.isEmpty()
                ? "EMPTY"
                : stack.getItem().toString() + "x" + stack.getCount();

        // Log every invocation during verification so evidence shows the
        // filter is reached uniformly on both vanilla and MenuKit slots.
        ContractVerification.LOGGER.info(
                "[Verify.Composability] mayPlace called on slot class={} stack={}",
                slotClass, stackDesc);

        // The observable filter: reject cobblestone regardless of slot type.
        if (stack.is(Items.COBBLESTONE)) {
            ContractVerification.LOGGER.info(
                    "[Verify.Composability] → REJECTED (cobblestone filter fired)");
            cir.setReturnValue(false);
        }
    }
}
