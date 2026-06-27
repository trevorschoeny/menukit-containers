package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.MKCSlotProjection;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side projection seam: appends a player's registered projected slots onto
 * a just-opened foreign menu, <em>before</em> its first content sync.
 *
 * <h3>Why {@code initMenu} HEAD, not {@code openMenu} RETURN</h3>
 *
 * {@code ServerPlayer.openMenu} runs {@code createMenu → send OpenScreen →
 * initMenu(menu)}, and {@code initMenu} is what performs the initial
 * {@code sendAllDataToRemote}. Injecting at {@code openMenu} RETURN would append
 * the slots <em>after</em> that content was sent, so the first
 * {@code ContainerSetContent} would omit them and the client menu (which appends
 * slots at {@code AFTER_INIT}) would be one tail block longer than the packet —
 * a guaranteed {@code remoteSlots} desync. Injecting at {@code initMenu} HEAD puts
 * the slots on the menu just before the same method sends content, so the initial
 * packet already carries them. The client's {@code AFTER_INIT} append (also before
 * its content packet) mirrors this exactly. See {@link MKCSlotProjection} for the
 * full sync-safety contract.
 *
 * <p>A no-op for any menu with no registered projection source (the player's own
 * {@code InventoryMenu} is registered by the consumer's {@code <init>} mixin, never
 * registered here). Server-side only ({@code ServerPlayer}); the parallel client
 * seam lives in the MKC client initializer.
 */
@ApiStatus.Internal
@Mixin(ServerPlayer.class)
public abstract class PlayerInitMenuProjectionMixin {

    @Inject(method = "initMenu", at = @At("HEAD"))
    private void mk$projectSlots(AbstractContainerMenu menu, CallbackInfo ci) {
        MKCSlotProjection.appendProjectedSlots(menu, (ServerPlayer) (Object) this);
    }
}
