package com.trevorschoeny.menukit.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client-side partner to {@link MenuKitScreenHandler}. Owns hover detection,
 * keybind dispatch, panel visibility rendering, and scoped drag modes.
 *
 * <p>This is MenuKit's own screen base class for screens it builds.
 * MenuKit does NOT mixin into vanilla's {@link AbstractContainerScreen} —
 * this class is where all MenuKit-specific screen behavior lives.
 *
 * <p>Phase 1 shell — wired up in Phase 4a.
 */
public class MenuKitHandledScreen extends AbstractContainerScreen<MenuKitScreenHandler> {

    public MenuKitHandledScreen(MenuKitScreenHandler handler, Inventory inventory,
                                Component title) {
        super(handler, inventory, title);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        // Phase 4a: panel backgrounds, slot backgrounds, overlays
    }
}
