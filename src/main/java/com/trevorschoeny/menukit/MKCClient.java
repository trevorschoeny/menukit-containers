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

        // Inventory-screen parity: plug the registered-slot input resolution into
        // MenuKit's library-owned screen dispatch (§0042 — MK exposes the neutral
        // hook + the per-screen mixins; MKC implements the registered-slot half
        // here). After this, a panel-hosted registered slot (a SlotElement) resolves
        // hover/click on every matching inventory-bearing screen, creative included,
        // with no per-screen consumer mixin. Drawing the slot is the panel pipeline's
        // job; this hook is the input limb only.
        SlotScreenDispatcher.setHook(new MKCSlotScreenHook());

        // THE ONE WINDOW — created-slot resolution port (§0042). MK resolves
        // vanilla/panel/element addresses itself; created slots are MKCSlots whose
        // position lives on that MKC type, so MKC installs the resolver here.
        com.trevorschoeny.menukit.window.SlotWindowResolver.setCreatedSlotResolver(
                com.trevorschoeny.menukit.core.CreatedSlotAdapter.INSTANCE);

        // THE ONE WINDOW — kind-aware addressing for the shared client slot→address
        // rule, so a created slot resolves to its CREATED address (not a vanilla-style
        // one) for BOTH observed reactions and hover/click signals. MK-alone falls
        // back to vanilla addressing.
        com.trevorschoeny.menukit.window.ClientSlotAddressing.install(
                com.trevorschoeny.menukit.core.SlotAddresses::of);

        // Container-parity chrome. Build each MKCContainerPanel's display panel
        // (chrome + slot presentation) and wire its ScreenPanelAdapter, scoped by
        // the registered parity matcher. Runs now (in the library's client init,
        // after every consumer's common-init register() has populated the
        // definitions — Fabric runs all main entrypoints before any client one),
        // so no GUI object was ever constructed on a dedicated server.
        MKCContainerPanel.wireRegisteredChrome();

        // MKCMenu turnkey screens. Register each defined custom menu's screen with
        // MenuScreens (default MKCHandledScreen, or the consumer's .screen(...) factory).
        // Same client-only, definitions-already-populated timing as the chrome above.
        com.trevorschoeny.menukit.screen.MKCMenu.registerScreens();

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
