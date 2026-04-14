package com.trevorschoeny.menukit.verification;

import com.trevorschoeny.menukit.screen.MenuKitHandledScreen;
import com.trevorschoeny.menukit.screen.MenuKitScreenHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client-side screen for {@link TestContractHandler}. Thin subclass of
 * {@link MenuKitHandledScreen} — the base class handles all rendering,
 * layout, hover, drag, and events.
 *
 * <p>Phase 5 verification scaffolding. See {@link ContractVerification}.
 */
public class TestContractScreen extends MenuKitHandledScreen {

    public TestContractScreen(MenuKitScreenHandler handler,
                              Inventory inventory, Component title) {
        super(handler, inventory, title);
    }
}
