package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.GraftCreativeWrapper;
import com.trevorschoeny.menukit.core.MenuKitSlot;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creative <em>non-inventory</em>-tab graft parity (the other half of §0051) —
 * makes grafted slots (pockets / equipment) appear and work on the combat / tools
 * / search / etc. tabs, not just the inventory tab.
 *
 * <h3>Why the inventory tab alone showed grafts</h3>
 *
 * The creative menu ({@code ItemPickerMenu}) carries a fixed base slot set — the
 * 5×9 item grid + the player hotbar — built once here in the constructor. Only on
 * the <b>inventory</b> tab does {@code selectTab} swap that base for wrappers of
 * every {@code player.inventoryMenu} slot (grafts included, then parked off-screen
 * by {@code MenuKitGraftCreativeParkMixin}); leaving the inventory tab restores the
 * base. So the graft dispatch — which walks the active {@code menu.slots} and
 * unwraps each via {@code Slots.target} — finds the graft on the inventory tab but
 * never on the base set the other tabs show. That is the exact boundary behind
 * "creative pockets work in the inventory but not the tabs."
 *
 * <h3>The fix: append the grafts to the base set, once</h3>
 *
 * At constructor TAIL we append one {@link GraftCreativeWrapper} per grafted
 * {@code inventoryMenu} slot to the base {@code menu.slots}. Vanilla's own
 * {@code originalSlots} save/restore then carries them correctly: on entering the
 * inventory tab vanilla snapshots the base (our wrappers included) and rebuilds
 * from {@code inventoryMenu}; on leaving it restores the snapshot — so the graft
 * is represented by vanilla's parked wrapper on the inventory tab and by ours on
 * every other tab, never both at once, and the dispatcher renders exactly one copy
 * at {@code graftX/graftY} throughout.
 *
 * <p>Placement clicks on these wrappers are re-routed to the proven inventory-tab
 * path by {@code MenuKitGraftCreativeClickRouteMixin} (the base-set click path is
 * client-only and would not reach the graft's backing storage).
 *
 * <p>Generic — detects a graft by {@code instanceof MenuKitSlot}, no per-consumer
 * knowledge. Client-only (the creative screen is a client type).
 */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
public abstract class MenuKitGraftCreativeItemPickerMixin {

    /** Far off-screen — the same parking coordinate the inventory-tab wrappers use. */
    private static final int GRAFT_OFFSCREEN = -10000;

    /** The player's real inventory menu (where the grafts live). */
    @Shadow @Final private AbstractContainerMenu inventoryMenu;

    /** Inherited {@code AbstractContainerMenu#addSlot}, reached without subclassing. */
    @Invoker("addSlot")
    protected abstract Slot menukit$addSlot(Slot slot);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void menukit$appendGraftWrappers(Player player, CallbackInfo ci) {
        // The graft set on inventoryMenu is structural and stable (grafts are
        // appended once at registration; reveal/hide is inertness, not add/remove),
        // so a fixed wrapper per graft is correct for the menu's lifetime.
        for (Slot slot : this.inventoryMenu.slots) {
            if (slot instanceof MenuKitSlot mk) {
                menukit$addSlot(new GraftCreativeWrapper(mk, GRAFT_OFFSCREEN, GRAFT_OFFSCREEN));
            }
        }
    }
}
