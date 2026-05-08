package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.inject.SlotGroupPanelRegistry;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Library-private mixin that dispatches SlotGroupContext panel rendering
 * into the correct render stratum of {@link AbstractContainerScreen#render}.
 *
 * <p>Post-§0042 split companion to MenuKit's
 * {@code com.trevorschoeny.menukit.mixin.MenuKitPanelRenderMixin}. Both
 * mixins inject at the same point ({@code INVOKE renderCarriedItem}); both
 * fire per render. MenuKit's mixin renders MenuContext panels first; this
 * one renders SlotGroupContext panels second (last-registered z-order).
 *
 * <p>See MenuKit's {@code MenuKitPanelRenderMixin} for the rationale on
 * why this injection point (vs {@code ScreenEvents.afterRender}) — tooltip
 * layering per Principle 9.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotGroupPanelRenderMixin {

    /**
     * Fires before {@code renderCarriedItem} is invoked, after slot
     * rendering and after MenuKit's MenuContext render pass. Slot-group
     * panels render on top of MenuContext panels in this stratum (same
     * stratum, later in the call sequence).
     *
     * <p>{@code require = 1} so the mixin fails loudly if a vanilla
     * refactor removes or renames the target method.
     */
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderCarriedItem(Lnet/minecraft/client/gui/GuiGraphics;II)V"
            ),
            require = 1
    )
    private void menuKitContainers$renderSlotGroupPanels(GuiGraphics graphics,
                                                          int mouseX, int mouseY,
                                                          float partialTick,
                                                          CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        SlotGroupPanelRegistry.renderMatchingPanels(self, graphics, mouseX, mouseY);
    }
}
