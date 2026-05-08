package com.trevorschoeny.menukit;

import com.trevorschoeny.menukit.inject.SlotGroupPanelRegistry;
import com.trevorschoeny.menukit.inject.VanillaSlotGroupResolvers;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client-side entry point for the MenuKit: Containers artifact.
 *
 * <p>Owns the slot-related client-side init pieces:
 * <ul>
 *   <li>M1 client-state init (delegated to
 *       {@link MenuKitContainers#initClient}) — registers client-side
 *       networking handlers + verification client-side test-screen
 *       factory.</li>
 *   <li>{@link VanillaSlotGroupResolvers#registerAll} — registers slot-group
 *       resolvers for the 22 vanilla menu classes (M8 §6).</li>
 *   <li>{@link SlotGroupPanelRegistry#init} — registers the
 *       {@code ScreenEvents.AFTER_INIT} listener that wires up
 *       SlotGroupContext panel dispatch (parallel to MenuKit's
 *       {@code ScreenPanelRegistry.init}).</li>
 * </ul>
 *
 * <p>Why these live here (containers) and not in MenuKit's
 * {@code MenuKitClient}: each piece references slot-related types
 * ({@code SlotGroupCategory}, {@code SlotGroupPanelAdapter}, vanilla
 * {@code Slot}, etc.). Per §0042's one-way dependency rule, MenuKit cannot
 * reference MenuKit: Containers — so the client init for slot concerns
 * lives in this artifact's own client entrypoint.
 *
 * <p>The M9 modal-tracking cursor-suppression tick handler stays in
 * {@code MenuKitClient} because it queries
 * {@code ScreenPanelRegistry.hasAnyVisibleModalTracking()} (MenuKit-side
 * after the §0042 split — modal tracking is not slot-related).
 */
public class MenuKitContainersClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Delegate to MenuKit: Containers' client-state init.
        MenuKitContainers.initClient();

        // M8 — vanilla slot-group resolvers for SlotGroupContext dispatch.
        // Must register before SlotGroupPanelRegistry.init so the first
        // screen-open can resolve categories correctly. See M8 §6 for the
        // 22 menu classes covered.
        VanillaSlotGroupResolvers.registerAll();

        // M8 — library-owned ScreenEvents.AFTER_INIT dispatch for
        // SlotGroupContext adapters. Parallel to MenuKit-side
        // ScreenPanelRegistry.init for MenuContext adapters. Each registry
        // handles its own dispatch independently — both registries register
        // their own ScreenEvents.AFTER_INIT listeners; both fire per
        // screen-open.
        SlotGroupPanelRegistry.init();
    }
}
