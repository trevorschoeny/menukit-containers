package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.CreativeSlotWrapper;
import com.trevorschoeny.menukit.core.MKCSlot;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creative <em>non-inventory</em>-tab slot parity (the other half of §0051) —
 * makes registered slots (pockets / equipment) appear and work on the combat / tools
 * / search / etc. tabs, not just the inventory tab.
 *
 * <h3>Why the inventory tab alone showed slots</h3>
 *
 * The creative menu ({@code ItemPickerMenu}) carries a fixed base slot set — the
 * 5×9 item grid + the player hotbar — built once here in the constructor. Only on
 * the <b>inventory</b> tab does {@code selectTab} swap that base for wrappers of
 * every {@code player.inventoryMenu} slot (slots included, then parked off-screen
 * by {@code MKCCreativeSlotParkMixin}); leaving the inventory tab restores the
 * base. So the slot dispatch — which walks the active {@code menu.slots} and
 * unwraps each via {@code Slots.target} — finds the slot on the inventory tab but
 * never on the base set the other tabs show. That is the exact boundary behind
 * "creative pockets work in the inventory but not the tabs."
 *
 * <h3>The fix: append the slots to the base set, once</h3>
 *
 * At constructor TAIL we append one {@link CreativeSlotWrapper} per registered
 * {@code inventoryMenu} slot to the base {@code menu.slots}. Vanilla's own
 * {@code originalSlots} save/restore then carries them correctly: on entering the
 * inventory tab vanilla snapshots the base (our wrappers included) and rebuilds
 * from {@code inventoryMenu}; on leaving it restores the snapshot — so the slot
 * is represented by vanilla's parked wrapper on the inventory tab and by ours on
 * every other tab, never both at once, and the dispatcher renders exactly one copy
 * at {@code renderX/renderY} throughout.
 *
 * <p>Placement clicks on these wrappers are re-routed to the proven inventory-tab
 * path by {@code MKCCreativeSlotClickRouteMixin} (the base-set click path is
 * client-only and would not reach the slot's backing storage).
 *
 * <p>Generic — detects a slot by {@code instanceof MKCSlot}, no per-consumer
 * knowledge. Client-only (the creative screen is a client type).
 */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
public abstract class MKCCreativeSlotItemPickerMixin {

    /** Far off-screen — the same parking coordinate the inventory-tab wrappers use. */
    private static final int SLOT_OFFSCREEN = -10000;

    /** The player's real inventory menu (where the slots live). */
    @Shadow @Final private AbstractContainerMenu inventoryMenu;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mk$appendSlotWrappers(Player player, CallbackInfo ci) {
        // The slot set on inventoryMenu is structural and stable (slots are
        // appended once at registration; reveal/hide is inertness, not add/remove),
        // so a fixed wrapper per slot is correct for the menu's lifetime.
        //
        // addSlot is declared on AbstractContainerMenu (the superclass), not on
        // ItemPickerMenu — a local @Invoker on this subclass mixin can't see the
        // inherited method (Mixin searches the target class only) and fails at
        // apply time. Reach it through the established AbstractContainerMenuInvoker
        // shim, which targets the superclass and so applies to this subclass too.
        AbstractContainerMenuInvoker self = (AbstractContainerMenuInvoker) (Object) this;
        for (Slot slot : this.inventoryMenu.slots) {
            if (slot instanceof MKCSlot mk) {
                self.mk$addSlot(new CreativeSlotWrapper(mk, SLOT_OFFSCREEN, SLOT_OFFSCREEN));
            }
        }
    }
}
