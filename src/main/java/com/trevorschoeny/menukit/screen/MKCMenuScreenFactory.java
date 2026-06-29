package com.trevorschoeny.menukit.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Builds the client {@link MKCHandledScreen} for a registered {@code MKCMenu}.
 * Shape matches both {@code MenuScreens.ScreenConstructor.create} and the
 * {@link MKCHandledScreen} 3-arg constructor, so the default factory is simply
 * {@code MKCHandledScreen::new} and a consumer subclass (overriding {@code init()}
 * for custom keys/listeners/drag) drops in as {@code MySubclass::new}.
 *
 * <p><b>Client-only.</b> This factory constructs a GUI object; it is invoked
 * exclusively from {@code MKCMenu.registerScreens()} on the client (drained from
 * {@code MKCClient.onInitializeClient}) and is <em>never</em> invoked on a
 * dedicated server — the same never-server-invoked discipline as
 * {@code MKCContainerPanel.chrome}.
 */
@FunctionalInterface
public interface MKCMenuScreenFactory {

    /** Constructs the screen for the given handler / player inventory / title. */
    MKCHandledScreen create(MKCScreenHandler handler, Inventory inventory, Component title);
}
