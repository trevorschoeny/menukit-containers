package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.ParitySlotRegistry;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The library-owned seam that makes container-parity slots appear on the
 * player's own {@code InventoryMenu} — the §0019 line container parity
 * deliberately crosses, and the reason a consumer using
 * {@link com.trevorschoeny.menukit.core.MKCContainerPanel} writes <b>no mixin</b>.
 *
 * <h3>Why the library owns this one</h3>
 *
 * Foreign menus (chests/furnaces/modded) have no consumer-ownable per-menu seam,
 * so {@link com.trevorschoeny.menukit.core.MKCSlotProjection} already appended to
 * them from a library-owned player-lifecycle seam. The player's <em>own</em>
 * inventory menu is different: it's built in the {@code Player} constructor
 * (never via {@code openMenu}), so the projection seam can't reach it — and its
 * constructor <em>does</em> carry the {@code Player}, so a consumer normally had
 * to write an {@code InventoryMenu.<init>} mixin themselves. Rather than make
 * every consumer copy that boilerplate, the library owns the seam once and drives
 * it from {@link ParitySlotRegistry}. (A consumer who wants bespoke
 * {@code InventoryMenu} slots that are <em>not</em> container-parity can still
 * write their own mixin + {@code MKCSlots} call; this only services
 * parity-registered recipes.)
 *
 * <h3>Sync safety</h3>
 *
 * Mirrors the established consumer pattern: append at constructor TAIL so the
 * slots re-appear on every menu reconstruction (login, respawn, dimension
 * change) on both sides. {@link ParitySlotRegistry#applyTo} iterates the same
 * registry in the same order on both sides and binds the same per-player storage,
 * so server and client build a byte-identical slot block before the first
 * content sync. No-op when no parity recipe is registered (the common
 * MenuKit-only / consumer-without-parity case).
 */
@Mixin(InventoryMenu.class)
public abstract class MKCInventoryMenuMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mkc$applyParitySlots(Inventory inv, boolean active,
                                      Player player, CallbackInfo ci) {
        if (ParitySlotRegistry.isEmpty()) return;
        ParitySlotRegistry.applyTo((AbstractContainerMenu) (Object) this, player);
    }
}
