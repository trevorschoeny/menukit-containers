package com.trevorschoeny.menukit.verification;

import com.trevorschoeny.menukit.screen.MenuKitScreenHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * MenuKit-native demo handler — a visual testbed for element rendering.
 * Deliberately minimal: one panel, no slots, no player inventory panel.
 * Consumers (phase-work verification) place test elements in the demo
 * panel via the factory below.
 *
 * <p>Opened via {@code /mkverify elements}. Exists alongside
 * {@link TestContractHandler} (the contract-verification harness) but
 * serves a different purpose: contract verification uses slots and
 * panels-with-groups to exercise the five canonical guarantees; this
 * demo is purely for looking at elements render.
 *
 * <p>The panel contains a title TextLabel plus whatever per-phase test
 * elements the verification round needs. Edit this file to add/remove
 * test elements during phase work; revert before committing phase
 * deliverables.
 */
public final class ElementDemoHandler {

    private ElementDemoHandler() {}

    /** Factory — wired into the MenuType at registration time. */
    public static MenuKitScreenHandler create(int syncId,
                                              Inventory playerInventory,
                                              MenuType<MenuKitScreenHandler> menuType) {
        return MenuKitScreenHandler.builder(menuType)
                .panel("demo", p -> p
                        .text(4, 4, Component.literal("MenuKit Element Demo"))
                        .text(4, 18, Component.literal("Phase 9 — visual verification surface"))
                        .horizontalDivider(4, 32, 200)
                        // Per-phase test elements go here.
                )
                .build(syncId);
    }
}
