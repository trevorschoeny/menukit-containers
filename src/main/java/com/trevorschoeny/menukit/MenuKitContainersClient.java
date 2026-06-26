package com.trevorschoeny.menukit;

import com.trevorschoeny.menukit.core.GraftProjection;
import com.trevorschoeny.menukit.core.MenuKitGraftScreenHook;
import com.trevorschoeny.menukit.inject.GraftScreenDispatcher;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client-side entry point for the MenuKit: Containers artifact.
 *
 * <p>Per §0043 (Complete-on-Side Feature Ownership): MK initializes its own
 * observation machinery (slot-group resolvers, dispatch registries) — this
 * artifact does not initialize MK-side features. The MKC client init is
 * narrowly scoped to MKC-side concerns:
 *
 * <ul>
 *   <li>M1 client-state init (delegated to
 *       {@link MenuKitContainers#initClient}) — registers client-side
 *       networking handlers + verification client-side test-screen
 *       factory.</li>
 * </ul>
 *
 * <p>Post-§0043: {@code VanillaSlotGroupResolvers.registerAll()} and
 * {@code SlotGroupPanelRegistry.init()} moved to {@code MenuKitClient} —
 * those are observation idioms, complete on MK's side.
 */
@ApiStatus.Internal
public class MenuKitContainersClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Delegate to MenuKit: Containers' client-state init.
        MenuKitContainers.initClient();

        // Inventory-screen parity: plug the grafted-slot draw/input/reveal work
        // into MenuKit's library-owned screen dispatch (§0042 — MK exposes the
        // neutral hook + the per-screen mixins; MKC implements the grafted-slot
        // half here). After this, any GraftScreenPresence a consumer registers
        // manifests on every matching inventory-bearing screen, creative
        // included, with no per-screen consumer mixin.
        GraftScreenDispatcher.setHook(new MenuKitGraftScreenHook());

        // Graft projection — client seam. Append a player's registered projected
        // grafts onto a foreign container menu (chest/furnace/donkey) at screen
        // init, which fires synchronously inside handleOpenScreen BEFORE the
        // initial content packet is processed — mirroring the server's
        // ServerPlayer.initMenu HEAD seam so both menus carry the grafts (same set,
        // same order) before the first sync. No-op for menus with no registered
        // projection source (incl. the player's own InventoryMenu). See
        // GraftProjection for the sync-safety contract.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> acs && client.player != null) {
                GraftProjection.appendProjectedGrafts(acs.getMenu(), client.player);
            }
        });
    }
}
