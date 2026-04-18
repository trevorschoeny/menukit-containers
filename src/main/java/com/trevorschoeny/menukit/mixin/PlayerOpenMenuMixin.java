package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.state.SlotStateHooks;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Server-side post-openMenu hook: after {@link ServerPlayer#openMenu} returns
 * successfully, fire M1's menu-open snapshot so the client receives the
 * per-slot state for the newly-opened menu.
 *
 * <p>Skipped when {@code openMenu} returns empty (menu open rejected).
 * Player-inventory / ender-chest channels are delivered by the parallel
 * player-join handler (see {@code SlotStateHooks.registerServer}) — this
 * mixin covers every other container (chests, furnaces, donkeys, etc.).
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerOpenMenuMixin {

    @Inject(method = "openMenu", at = @At("RETURN"))
    private void menuKit$sendSlotStateSnapshot(MenuProvider menuProvider,
                                                 CallbackInfoReturnable<OptionalInt> cir) {
        OptionalInt result = cir.getReturnValue();
        if (result == null || result.isEmpty()) return;
        SlotStateHooks.sendSnapshotForOpenedMenu((ServerPlayer) (Object) this);
    }
}
