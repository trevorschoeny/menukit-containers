package com.trevorschoeny.menukit.verification;

import com.trevorschoeny.menukit.screen.MenuKitHandledScreen;
import com.trevorschoeny.menukit.screen.MenuKitScreenHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client-side screen for {@link ElementDemoHandler}. Thin subclass of
 * {@link MenuKitHandledScreen} — the base class handles all rendering,
 * layout, hover, and events.
 *
 * <p>Part of the visual-verification harness. See {@link ContractVerification}.
 */
public class ElementDemoScreen extends MenuKitHandledScreen {

    public ElementDemoScreen(MenuKitScreenHandler handler,
                             Inventory inventory, Component title) {
        super(handler, inventory, title);
    }
}
