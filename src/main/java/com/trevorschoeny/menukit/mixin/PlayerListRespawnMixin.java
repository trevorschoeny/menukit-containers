package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.state.SlotStateHooks;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fires the player-join-style snapshot after {@link PlayerList#respawn}
 * completes. Respawn reconstructs {@code InventoryMenu} on the new
 * {@code ServerPlayer}; without this hook the player's inventory locks (F1)
 * would show empty until the next menu open.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnMixin {

    @Inject(method = "respawn", at = @At("RETURN"))
    private void menuKit$sendJoinSnapshotOnRespawn(
            ServerPlayer serverPlayer, boolean removed, Entity.RemovalReason reason,
            CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer respawned = cir.getReturnValue();
        if (respawned == null) return;
        SlotStateHooks.sendPlayerJoinSnapshot(respawned);
    }
}
