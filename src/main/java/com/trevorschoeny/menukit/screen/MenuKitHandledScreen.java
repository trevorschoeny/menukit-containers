package com.trevorschoeny.menukit.screen;

import com.trevorschoeny.menukit.core.*;
import com.trevorschoeny.menukit.mixin.SlotPositionAccessor;
import com.trevorschoeny.menukit.panel.MKPanel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side partner to {@link MenuKitScreenHandler}. Owns layout computation,
 * panel background rendering, slot positioning, and hover detection.
 *
 * <p>This is MenuKit's own screen base class for screens it builds.
 * All rendering and layout state is per-screen-instance — no global statics.
 *
 * <p>Renders in vanilla's pipeline:
 * <ol>
 *   <li>{@code renderBg()} — panel backgrounds + slot backgrounds (screen space)</li>
 *   <li>Vanilla's {@code renderSlotHighlightBack()} — hover highlight back layer</li>
 *   <li>Vanilla's {@code renderSlots()} — item rendering (container-relative)</li>
 *   <li>Vanilla's {@code renderSlotHighlightFront()} — hover highlight front layer</li>
 *   <li>{@code renderLabels()} — title and inventory label text</li>
 *   <li>{@code renderTooltip()} — item tooltip at mouse position</li>
 * </ol>
 */
public class MenuKitHandledScreen extends AbstractContainerScreen<MenuKitScreenHandler> {

    private static final Logger LOGGER = LoggerFactory.getLogger("MenuKit");

    // ── Layout Constants ───────────────────────────────────────────────
    /** Padding inside each panel (pixels from panel edge to slot backgrounds). */
    private static final int PANEL_PADDING = 7;
    /** Size of one slot cell (18x18: 16px item + 1px border each side). */
    private static final int SLOT_SIZE = 18;
    /** Vertical gap between body panels. Matches vanilla container-to-inventory gap. */
    private static final int BODY_GAP = 14;
    /** Gap between a relative panel and its anchor. */
    private static final int RELATIVE_GAP = 4;
    /** Vertical space reserved above the first panel for the title label. */
    private static final int TITLE_HEIGHT = 14;

    // ── Per-Instance Layout State ──────────────────────────────────────
    // Recomputed each frame from the panel tree. No global statics.

    private record PanelBounds(int x, int y, int width, int height) {}

    /** Panel ID → computed screen-relative bounds. Rebuilt each frame. */
    private final Map<String, PanelBounds> panelBounds = new LinkedHashMap<>();

    // ── Construction ───────────────────────────────────────────────────

    public MenuKitHandledScreen(MenuKitScreenHandler handler, Inventory inventory,
                                Component title) {
        super(handler, inventory, title);
        // Compute initial layout to set imageWidth/imageHeight before
        // init() runs (which uses them for leftPos/topPos centering).
        computeLayout();
    }

    @Override
    protected void init() {
        // Recompute layout — screen may have resized, or it's the
        // first init after construction.
        computeLayout();
        positionSlots();
        super.init(); // sets leftPos = (width - imageWidth) / 2, etc.
    }

    // ── Layout Computation ─────────────────────────────────────────────
    // Runs each frame. Cheap (a few additions per panel). Handles
    // visibility toggles instantly without a separate invalidation step.

    /**
     * Computes the pixel size of a panel from its groups' slot grids.
     * Width = widest group * SLOT_SIZE + padding.
     * Height = sum of all group rows * SLOT_SIZE + row gaps + padding.
     */
    private int[] computePanelSize(Panel panel) {
        int maxCols = 0;
        int totalSlotHeight = 0;

        for (SlotGroup group : panel.getGroups()) {
            int cols = group.getColumns();
            maxCols = Math.max(maxCols, cols);

            int rows = (group.getStorage().size() + cols - 1) / cols; // ceiling division
            totalSlotHeight += rows * SLOT_SIZE;

            // Add row gap if specified and the gap row exists
            if (group.getRowGapAfter() >= 0 && group.getRowGapAfter() < rows - 1) {
                totalSlotHeight += group.getRowGapSize();
            }
        }

        int width = maxCols * SLOT_SIZE + 2 * PANEL_PADDING;
        int height = totalSlotHeight + 2 * PANEL_PADDING;
        return new int[]{width, height};
    }

    /**
     * Computes panel positions and updates imageWidth/imageHeight.
     *
     * <p>Two-tier layout:
     * <ol>
     *   <li>Body panels stack vertically in the main column.</li>
     *   <li>Relative panels offset from their named anchor panel.</li>
     * </ol>
     *
     * <p>imageWidth/imageHeight cover the body column only. Relative
     * panels may extend beyond those bounds — {@code hasClickedOutside}
     * is overridden to include them.
     */
    private void computeLayout() {
        panelBounds.clear();

        // Phase 1: Compute each panel's size (even hidden ones — we
        // need sizes available in case they become visible mid-frame).
        Map<String, int[]> sizes = new LinkedHashMap<>();
        for (Panel panel : menu.getPanels()) {
            sizes.put(panel.getId(), computePanelSize(panel));
        }

        // Phase 2: Position body panels — vertical stack with BODY_GAP
        int bodyWidth = 0;
        int bodyY = TITLE_HEIGHT; // room for the title label
        for (Panel panel : menu.getPanels()) {
            if (!panel.isVisible()) continue;
            if (panel.getPosition().mode() != PanelPosition.Mode.BODY) continue;

            int[] size = sizes.get(panel.getId());
            panelBounds.put(panel.getId(),
                    new PanelBounds(0, bodyY, size[0], size[1]));
            bodyWidth = Math.max(bodyWidth, size[0]);
            bodyY += size[1] + BODY_GAP;
        }

        // Phase 3: Position relative panels — offset from anchor
        for (Panel panel : menu.getPanels()) {
            if (!panel.isVisible()) continue;
            if (panel.getPosition().mode() == PanelPosition.Mode.BODY) continue;

            String anchorId = panel.getPosition().anchorPanelId();
            PanelBounds anchor = panelBounds.get(anchorId);
            if (anchor == null) continue; // anchor not visible — skip

            int[] size = sizes.get(panel.getId());
            PanelBounds bounds = switch (panel.getPosition().mode()) {
                case RIGHT_OF -> new PanelBounds(
                        anchor.x() + anchor.width() + RELATIVE_GAP,
                        anchor.y(), size[0], size[1]);
                case LEFT_OF -> new PanelBounds(
                        anchor.x() - size[0] - RELATIVE_GAP,
                        anchor.y(), size[0], size[1]);
                case ABOVE -> new PanelBounds(
                        anchor.x(),
                        anchor.y() - size[1] - RELATIVE_GAP,
                        size[0], size[1]);
                case BELOW -> new PanelBounds(
                        anchor.x(),
                        anchor.y() + anchor.height() + RELATIVE_GAP,
                        size[0], size[1]);
                default -> null; // BODY handled above
            };
            if (bounds != null) {
                panelBounds.put(panel.getId(), bounds);
            }
        }

        // imageWidth/imageHeight cover the body column only
        imageWidth = Math.max(bodyWidth, 176);       // minimum vanilla width
        imageHeight = Math.max(bodyY - BODY_GAP, 100); // remove trailing gap

        // Position the "Inventory" label relative to the player panel
        PanelBounds playerBounds = panelBounds.get("player");
        if (playerBounds != null) {
            // 11px above the panel: 9px font + 2px gap
            this.inventoryLabelY = playerBounds.y() - 11;
        }
    }

    /**
     * Sets each slot's x/y from its panel position and grid layout.
     *
     * <p>Slot.x and Slot.y are in container-relative space (the render
     * system adds leftPos/topPos). Hidden slots go off-screen so vanilla
     * skips them entirely.
     */
    private void positionSlots() {
        for (Panel panel : menu.getPanels()) {
            PanelBounds bounds = panelBounds.get(panel.getId());

            // Track running Y offset for multiple groups within a panel
            int groupOffsetY = 0;

            for (SlotGroup group : panel.getGroups()) {
                int cols = group.getColumns();
                int rows = (group.getStorage().size() + cols - 1) / cols;

                for (int s = group.getFlatIndexStart(); s < group.getFlatIndexEnd(); s++) {
                    int localIdx = s - group.getFlatIndexStart();
                    int col = localIdx % cols;
                    int row = localIdx / cols;

                    // Row gap: extra Y pixels for slots below the gap row
                    int extraY = (group.getRowGapAfter() >= 0
                            && row > group.getRowGapAfter())
                            ? group.getRowGapSize() : 0;

                    Slot slot = menu.slots.get(s);
                    if (bounds != null && panel.isVisible()) {
                        // +1 offset: slot.x/y point to the 16x16 item area,
                        // which starts 1px inside the 18x18 slot background
                        ((SlotPositionAccessor) slot).menuKit$setX(
                                bounds.x() + PANEL_PADDING + col * SLOT_SIZE + 1);
                        ((SlotPositionAccessor) slot).menuKit$setY(
                                bounds.y() + PANEL_PADDING + groupOffsetY
                                        + row * SLOT_SIZE + extraY + 1);
                    } else {
                        // Hidden — move off screen so vanilla ignores them
                        ((SlotPositionAccessor) slot).menuKit$setX(-9999);
                        ((SlotPositionAccessor) slot).menuKit$setY(-9999);
                    }
                }

                // Advance group offset for the next group in this panel
                int groupGapExtra = 0;
                if (group.getRowGapAfter() >= 0 && group.getRowGapAfter() < rows - 1) {
                    groupGapExtra = group.getRowGapSize();
                }
                groupOffsetY += rows * SLOT_SIZE + groupGapExtra;
            }
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────

    /**
     * Renders panel backgrounds and slot backgrounds in screen space.
     *
     * <p>Called before vanilla's slot highlight and item rendering.
     * Recomputes layout each frame so visibility toggles take
     * effect immediately.
     */
    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        // Recompute layout each frame — cheap, handles visibility toggles
        computeLayout();
        positionSlots();

        // ── Panel backgrounds (screen space) ───────────────────────────
        for (Panel panel : menu.getPanels()) {
            if (!panel.isVisible()) continue;
            PanelBounds bounds = panelBounds.get(panel.getId());
            if (bounds == null) continue;

            MKPanel.renderPanel(graphics,
                    leftPos + bounds.x(), topPos + bounds.y(),
                    bounds.width(), bounds.height(),
                    mapStyle(panel.getStyle()));
        }

        // ── Slot backgrounds (screen space) ────────────────────────────
        // 18x18 inset squares drawn at slot.x - 1, slot.y - 1 (the slot
        // background wraps around the 16x16 item area).
        for (Slot slot : menu.slots) {
            if (!slot.isActive()) continue;
            MKPanel.renderSlotBackground(graphics,
                    leftPos + slot.x - 1, topPos + slot.y - 1);
        }

    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // No-op: labels are a panel element concern (Phase 4b).
        // Text rendering in 1.21.11's batched pipeline needs a different
        // integration point than geometry fills — the panel element system
        // will handle this when text becomes a declared panel element.
    }

    // ── Input ──────────────────────────────────────────────────────────

    /**
     * Expands "inside" detection to include all visible panel bounds.
     *
     * <p>Vanilla clips to imageWidth×imageHeight, which only covers
     * the body column. Relative panels (positioned to the right, left,
     * etc.) would be treated as "outside" — causing item drops instead
     * of slot interactions. This override checks all panel bounds.
     */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY,
                                        int leftPos, int topPos) {
        for (PanelBounds bounds : panelBounds.values()) {
            double relX = mouseX - leftPos;
            double relY = mouseY - topPos;
            if (relX >= bounds.x() && relX < bounds.x() + bounds.width()
                    && relY >= bounds.y() && relY < bounds.y() + bounds.height()) {
                return false; // inside a panel — not outside
            }
        }
        // Fall through to vanilla's imageWidth×imageHeight check
        return super.hasClickedOutside(mouseX, mouseY, leftPos, topPos);
    }

    /**
     * Phase 3 test keybinds: T = toggle extras panel, S = stress test.
     * These are temporary — Task 3 replaces them with proper keybind dispatch.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_T) {
            // Toggle "extras" panel via C2S packet + local toggle
            int buttonId = this.menu.getPanelButtonId("extras");
            if (buttonId >= 0 && this.minecraft != null && this.minecraft.gameMode != null
                    && this.minecraft.player != null) {
                this.menu.clickMenuButton(this.minecraft.player, buttonId);
                this.minecraft.gameMode.handleInventoryButtonClick(
                        this.menu.containerId, buttonId);
                LOGGER.info("[Test] Toggled extras (buttonId={})", buttonId);
            }
            return true;
        }

        if (event.key() == GLFW.GLFW_KEY_S) {
            // Stress test: 100 rapid toggles
            LOGGER.info("[Test] Starting 100-toggle stress test...");
            for (int i = 0; i < 100; i++) {
                this.menu.setPanelVisible("extras", i % 2 == 0);
            }
            Panel extras = this.menu.getPanel("extras");
            boolean finalVisible = extras != null && extras.isVisible();
            boolean consistent = true;
            if (extras != null) {
                for (SlotGroup group : extras.getGroups()) {
                    for (int s = group.getFlatIndexStart(); s < group.getFlatIndexEnd(); s++) {
                        MenuKitSlot slot = (MenuKitSlot) this.menu.slots.get(s);
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

    // ── Style Mapping ──────────────────────────────────────────────────
    // Maps the core PanelStyle enum to MKPanel.Style for rendering.
    // MKPanel is a static rendering utility — no old-architecture baggage.

    private static MKPanel.Style mapStyle(PanelStyle style) {
        return switch (style) {
            case RAISED -> MKPanel.Style.RAISED;
            case DARK -> MKPanel.Style.DARK;
            case INSET -> MKPanel.Style.INSET;
            case NONE -> MKPanel.Style.NONE;
        };
    }
}
