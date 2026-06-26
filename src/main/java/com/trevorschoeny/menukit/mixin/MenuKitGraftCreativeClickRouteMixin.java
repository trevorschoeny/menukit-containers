package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.GraftSlots;
import com.trevorschoeny.menukit.core.MenuKitSlot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creative non-inventory-tab graft <em>click</em> routing — the sync half of the
 * all-tabs parity fix ({@code MenuKitGraftCreativeItemPickerMixin} is the
 * visibility half).
 *
 * <h3>Why routing is needed</h3>
 *
 * Vanilla's {@code slotClicked} sends a non-inventory-tab click into
 * {@code ItemPickerMenu.clicked(slot.index)} — a client-only edit on the throwaway
 * picker menu that never reaches the graft's backing {@code Storage} on the server,
 * so an item placed in a pocket from the combat/tools tab would desync. The
 * <em>inventory</em> tab instead routes graft clicks to
 * {@code player.inventoryMenu.clicked(target.index)} + {@code broadcastChanges()},
 * and the {@code CreativeInventoryListener} vanilla keeps on {@code inventoryMenu}
 * for the whole creative screen publishes every changed slot via
 * {@code ServerboundSetCreativeModeSlotPacket} — whose out-of-range graft index the
 * §0051 server bridge ({@code CreativeSetSlotGraftMixin}) writes.
 *
 * <h3>What it does</h3>
 *
 * For a click on a grafted slot while <em>not</em> on the inventory tab, it
 * replicates that proven inventory-tab path and cancels vanilla's client-only one.
 * Because the listener fires per changed slot, every gesture — place, pick up,
 * clone, number-swap, shift-move — syncs identically to the inventory tab with no
 * per-gesture special-casing, except the throw (Q), whose dropped entity is its own
 * server message exactly as vanilla's inventory-tab throw special-cases it.
 *
 * <p>The inventory tab is left entirely to vanilla (it already handles grafts
 * there). Non-graft slots are never touched. Client-only.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class MenuKitGraftCreativeClickRouteMixin {

    @Shadow private static CreativeModeTab selectedTab;

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void menukit$routeGraftClick(Slot slot, int slotId, int button,
                                         ClickType clickType, CallbackInfo ci) {
        if (slot == null) return;
        MenuKitSlot graft = GraftSlots.asGraft(slot);
        if (graft == null) return;
        // Vanilla already routes inventory-tab graft clicks correctly; only the
        // other tabs (client-only base-set click path) need re-routing.
        if (selectedTab.getType() == CreativeModeTab.Type.INVENTORY) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !player.hasInfiniteMaterials() || mc.gameMode == null) return;

        if (clickType == ClickType.THROW && graft.hasItem()) {
            // Drop (Q): mirror vanilla's inventory-tab throw — the dropped entity is
            // its own creative-drop server message; the slot's remainder syncs below.
            ItemStack dropped = graft.remove(button == 0 ? 1 : graft.getItem().getMaxStackSize());
            player.drop(dropped, true);
            mc.gameMode.handleCreativeModeItemDrop(dropped);
        } else {
            // Place / pick up / clone / swap / shift-move: act on the real inventory
            // menu by the graft's own index, exactly as the inventory tab does.
            player.inventoryMenu.clicked(graft.index, button, clickType, player);
        }
        // The persistent CreativeInventoryListener publishes every changed slot.
        player.inventoryMenu.broadcastChanges();
        ci.cancel();
    }
}
