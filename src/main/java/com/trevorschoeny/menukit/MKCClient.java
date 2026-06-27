package com.trevorschoeny.menukit;

import com.trevorschoeny.menukit.core.MKCContainerPanel;
import com.trevorschoeny.menukit.core.MKCSlotProjection;
import com.trevorschoeny.menukit.core.MKCSlotScreenHook;
import com.trevorschoeny.menukit.inject.SlotScreenDispatcher;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
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
 *       {@link MKC#initClient}) — registers client-side
 *       networking handlers + verification client-side test-screen
 *       factory.</li>
 * </ul>
 *
 * <p>Post-§0043: {@code VanillaSlotGroupResolvers.registerAll()} and
 * {@code SlotGroupPanelRegistry.init()} moved to {@code MKClient} —
 * those are observation idioms, complete on MK's side.
 */
@ApiStatus.Internal
public class MKCClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Delegate to MenuKit: Containers' client-state init.
        MKC.initClient();

        // Inventory-screen parity: plug the registered-slot draw/input/reveal work
        // into MenuKit's library-owned screen dispatch (§0042 — MK exposes the
        // neutral hook + the per-screen mixins; MKC implements the registered-slot
        // half here). After this, any SlotScreenPresence a consumer registers
        // manifests on every matching inventory-bearing screen, creative
        // included, with no per-screen consumer mixin.
        SlotScreenDispatcher.setHook(new MKCSlotScreenHook());

        // THE ONE WINDOW — created-slot resolution port (§0042). MK resolves
        // vanilla/panel/element addresses itself; created slots are MKCSlots whose
        // position lives on that MKC type, so MKC installs the resolver here.
        com.trevorschoeny.menukit.window.SlotWindowResolver.setCreatedSlotResolver(
                com.trevorschoeny.menukit.core.CreatedSlotAdapter.INSTANCE);

        // Container-parity chrome. Build each MKCContainerPanel's display panel
        // (chrome + slot presentation) and wire its ScreenPanelAdapter, scoped by
        // the registered parity matcher. Runs now (in the library's client init,
        // after every consumer's common-init register() has populated the
        // definitions — Fabric runs all main entrypoints before any client one),
        // so no GUI object was ever constructed on a dedicated server.
        MKCContainerPanel.wireRegisteredChrome();

        // Slot projection — client seam. Append a player's registered projected
        // slots onto a foreign container menu (chest/furnace/donkey) at screen
        // init, which fires synchronously inside handleOpenScreen BEFORE the
        // initial content packet is processed — mirroring the server's
        // ServerPlayer.initMenu HEAD seam so both menus carry the slots (same set,
        // same order) before the first sync.
        //
        // Skip the player's own inventory screens. The survival InventoryScreen's
        // menu is the InventoryMenu, already served by MKCInventoryMenuMixin; the
        // CreativeModeInventoryScreen's ItemPickerMenu is served by the creative
        // wrapper mixin. Projecting onto either here would DOUBLE-append the parity
        // slots. The server seam (ServerPlayer.initMenu) never fires for those, so
        // this guard keeps the client identical to the server — the sync invariant.
        // No-op otherwise for menus with no registered projection source.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
                return;
            }
            if (screen instanceof AbstractContainerScreen<?> acs && client.player != null) {
                MKCSlotProjection.appendProjectedSlots(acs.getMenu(), client.player);
            }
        });
    }
}
