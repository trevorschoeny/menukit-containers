package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.attachment.PlayerDeathDropHandler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops player-scoped MKC slot content on death — the player-death twin of
 * {@link M7BlockDropMixin} (block drop-on-break).
 *
 * <p>Targets {@link Player#dropEquipment(net.minecraft.server.level.ServerLevel)}
 * at TAIL. In 1.21.11 this method is exactly where vanilla, gated on
 * {@code keepInventory}, runs {@code destroyVanishingCursedItems()} +
 * {@code inventory.dropAll()} (JAR-confirmed). Running the grafted-attachment
 * drop at TAIL means MKC content drops alongside the vanilla inventory — same
 * death, same frame, same gamerule timing — for vanilla-identical parity. This
 * is the §0051 lineage: absorb the vanilla mechanic at the faithful upstream
 * seam, not a per-feature workaround.
 *
 * <p>TAIL is observational (no cancel). {@code dropEquipment} runs inside
 * {@code die()} <em>after</em> the totem check, so this fires only on a committed
 * death (totem-safe). The {@code keepInventory} read and the per-stack
 * {@link com.trevorschoeny.menukit.core.DropRule} resolution live in
 * {@link PlayerDeathDropHandler}; server-side only (the method takes a
 * {@code ServerLevel}).
 */
@ApiStatus.Internal
@Mixin(Player.class)
public abstract class PlayerDeathDropMixin {

    @Inject(method = "dropEquipment", at = @At("TAIL"))
    private void menukit$dropPlayerSlotsOnDeath(ServerLevel level, CallbackInfo ci) {
        PlayerDeathDropHandler.dropAllOnDeath((Player) (Object) this, level);
    }
}
