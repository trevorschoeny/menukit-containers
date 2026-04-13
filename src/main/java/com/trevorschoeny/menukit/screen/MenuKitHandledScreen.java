package com.trevorschoeny.menukit.screen;

import com.trevorschoeny.menukit.core.MenuKitSlot;
import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.SlotGroup;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side partner to {@link MenuKitScreenHandler}. Owns hover detection,
 * keybind dispatch, panel visibility rendering, and scoped drag modes.
 *
 * <p>This is MenuKit's own screen base class for screens it builds.
 * MenuKit does NOT mixin into vanilla's {@link AbstractContainerScreen} —
 * this class is where all MenuKit-specific screen behavior lives.
 *
 * <p>Phase 3: minimal rendering + test keybinds. Phase 4a wires up full rendering.
 */
public class MenuKitHandledScreen extends AbstractContainerScreen<MenuKitScreenHandler> {

    private static final Logger LOGGER = LoggerFactory.getLogger("MenuKit");

    public MenuKitHandledScreen(MenuKitScreenHandler handler, Inventory inventory,
                                Component title) {
        super(handler, inventory, title);
        // Size the screen to fit all slots in the temporary grid layout.
        // Phase 4a will compute this from the panel tree properly.
        this.imageWidth = 176;
        this.imageHeight = 200;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        // Minimal: render a dark background panel and slot backgrounds
        // so the test screen is usable. Phase 4a replaces this entirely.
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                0xCC000000);

        // Render slot background squares for active slots
        for (Slot slot : this.menu.slots) {
            if (slot.isActive()) {
                int sx = leftPos + slot.x - 1;
                int sy = topPos + slot.y - 1;
                graphics.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
                graphics.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF373737);
            }
        }

        // Show panel visibility status and keybind hints
        int textY = topPos + 4;
        for (Panel panel : this.menu.getPanels()) {
            String status = panel.getId() + ": " + (panel.isVisible() ? "VISIBLE" : "HIDDEN");
            graphics.drawString(this.font, status, leftPos + 4, textY, 0xFFFFFF);
            textY += 10;
        }
        graphics.drawString(this.font, "[T] toggle  [S] stress test",
                leftPos + 4, topPos + imageHeight - 12, 0xAAAAAA);
    }

    /**
     * Phase 3 test keybinds: T = toggle extras panel, S = stress test.
     * Phase 4a will replace this with proper keybind dispatch.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_T) {
            // Toggle "extras" panel visibility
            Panel extras = this.menu.getPanel("extras");
            if (extras != null) {
                this.menu.setPanelVisible("extras", !extras.isVisible());
                LOGGER.info("[Test] Toggled extras → {}", extras.isVisible());
            }
            return true;
        }

        if (event.key() == GLFW.GLFW_KEY_S) {
            // Stress test: 100 rapid toggles
            LOGGER.info("[Test] Starting 100-toggle stress test...");

            for (int i = 0; i < 100; i++) {
                this.menu.setPanelVisible("extras", i % 2 == 0);
            }

            // After 100 toggles (even count), extras should be visible (last toggle was i=99 → false)
            // Wait, i=99 is odd → setPanelVisible("extras", false). So final state is HIDDEN.
            // Actually: i%2==0 → true when i is even (0,2,4,...,98), false when odd (1,3,...,99)
            // Last iteration i=99: 99%2=1 → setPanelVisible(false). Final state: HIDDEN.

            Panel extras = this.menu.getPanel("extras");
            boolean finalVisible = extras != null && extras.isVisible();

            // Check consistency: all slots in extras should match the visibility state
            boolean consistent = true;
            if (extras != null) {
                for (SlotGroup group : extras.getGroups()) {
                    for (int s = group.getFlatIndexStart(); s < group.getFlatIndexEnd(); s++) {
                        MenuKitSlot slot = (MenuKitSlot) this.menu.slots.get(s);
                        // inert should be the opposite of visible
                        if (slot.isInert() != !finalVisible) {
                            consistent = false;
                            LOGGER.warn("[Test] INCONSISTENT: slot[{}] inert={} but panel visible={}",
                                    s, slot.isInert(), finalVisible);
                        }
                    }
                }
            }

            LOGGER.info("[Test] Stress test complete: final={} consistent={}",
                    finalVisible ? "VISIBLE" : "HIDDEN", consistent);
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY,
                0xFFFFFF, false);
    }
}
