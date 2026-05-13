package com.trevorschoeny.menukit;

import net.fabricmc.api.ClientModInitializer;

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
public class MenuKitContainersClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Delegate to MenuKit: Containers' client-state init.
        MenuKitContainers.initClient();
    }
}
