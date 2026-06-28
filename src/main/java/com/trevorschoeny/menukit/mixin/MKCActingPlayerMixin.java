package com.trevorschoeny.menukit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.trevorschoeny.menukit.core.GatingContext;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Captures the acting player for the duration of a click transaction, so a deep
 * gating seam (vanilla's {@code moveItemStackTo}, which has no player) can read it
 * via {@link GatingContext}. Set on entry, cleared in {@code finally} (never
 * leaks). Generalizes Inventory Max's lock click-capture.
 */
@Mixin(AbstractContainerMenu.class)
public class MKCActingPlayerMixin {

    @WrapMethod(method = "clicked")
    private void mkc$captureActingPlayer(int slotId, int button, ClickType clickType, Player player,
                                         Operation<Void> original) {
        GatingContext.setActingPlayer(player);
        try {
            original.call(slotId, button, clickType, player);
        } finally {
            GatingContext.clearActingPlayer();
        }
    }
}
