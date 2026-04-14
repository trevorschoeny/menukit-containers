package com.trevorschoeny.menukit.screen;

import com.trevorschoeny.menukit.core.*;
import com.trevorschoeny.menukit.mixin.SlotPositionAccessor;
import com.trevorschoeny.menukit.panel.MKPanel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

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

    // ── Hover Tracking ─────────────────────────────────────────────────
    // Tracks the previously hovered MenuKitSlot to fire enter/exit events.
    // Also exposes current hover state as a queryable property for consumers.
    private @Nullable MenuKitSlot previouslyHoveredMkSlot = null;

    /**
     * Returns the currently hovered MenuKitSlot, or null if the mouse
     * isn't over a MenuKit slot. Synchronous query — consumers like HUD
     * overlays can read this each frame without subscribing to events.
     */
    public @Nullable MenuKitSlot getHoveredMenuKitSlot() {
        return previouslyHoveredMkSlot;
    }

    /**
     * Returns the SlotGroup of the currently hovered MenuKitSlot, or
     * null. Convenience for consumers that care about group identity.
     */
    public @Nullable SlotGroup getHoveredGroup() {
        return previouslyHoveredMkSlot != null
                ? previouslyHoveredMkSlot.getGroup() : null;
    }

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

        // Build the key dispatch table from the panel tree.
        // Cleared and rebuilt on each init (handles screen resize re-init).
        keyRegistry.clear();
        registerPanelToggleKeys();
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

        // ── Panel elements (screen space) ─────────────────────────────
        // Buttons, text labels, and custom elements positioned within
        // each panel's content area (after PANEL_PADDING).
        for (Panel panel : menu.getPanels()) {
            if (!panel.isVisible()) continue;
            PanelBounds bounds = panelBounds.get(panel.getId());
            if (bounds == null) continue;

            int contentX = leftPos + bounds.x() + PANEL_PADDING;
            int contentY = topPos + bounds.y() + PANEL_PADDING;

            for (PanelElement element : panel.getElements()) {
                if (!element.isVisible()) continue;
                element.render(graphics, contentX, contentY, mouseX, mouseY);
            }
        }

    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        // ── Hover tracking (fire enter/exit events) ────────────────────
        // After super.render(), hoveredSlot is set by vanilla's pipeline.
        // Compare with previous frame to detect enter/exit transitions.
        MenuKitSlot currentHovered = (this.hoveredSlot instanceof MenuKitSlot mk) ? mk : null;
        if (currentHovered != previouslyHoveredMkSlot) {
            if (previouslyHoveredMkSlot != null) {
                fireSlotHoverExit(previouslyHoveredMkSlot);
            }
            if (currentHovered != null) {
                fireSlotHoverEnter(currentHovered);
            }
            previouslyHoveredMkSlot = currentHovered;
        }

        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 1.21.11 requires explicit ARGB colors — drawString silently
        // discards text when alpha is 0 (ARGB.alpha(color) != 0 guard).
        // White text with shadow for readability on the dark overlay.
        // When labels move into panel backgrounds (panel elements), switch
        // to 0xFF404040 (vanilla's dark gray on light backgrounds).
        graphics.drawString(this.font, this.title,
                this.titleLabelX, this.titleLabelY, 0xFFFFFFFF, true);
        graphics.drawString(this.font, this.playerInventoryTitle,
                this.inventoryLabelX, this.inventoryLabelY, 0xFFFFFFFF, true);
    }

    // ── Panel Visibility (public API) ──────────────────────────────────
    // Entry point for consumers to toggle panel visibility from anywhere
    // (HUD buttons, chat commands, game events). Routes through the
    // same C2S sync mechanism as the keybind path.

    /**
     * Toggles a panel's visibility with full client+server sync.
     *
     * <p>Consumers can call this from any context (HUD button click,
     * chat command, game event) — not just from keybinds. Routes through
     * the same {@code clickMenuButton} C2S mechanism.
     */
    public void togglePanel(String panelId) {
        int buttonId = this.menu.getPanelButtonId(panelId);
        if (buttonId >= 0 && this.minecraft != null
                && this.minecraft.gameMode != null
                && this.minecraft.player != null) {
            this.menu.clickMenuButton(this.minecraft.player, buttonId);
            this.minecraft.gameMode.handleInventoryButtonClick(
                    this.menu.containerId, buttonId);
            // Fire event
            Panel panel = this.menu.getPanel(panelId);
            if (panel != null) {
                firePanelToggle(panel, panel.isVisible());
            }
        }
    }

    // ── Key Registry ──────────────────────────────────────────────────
    // Lookup-based dispatch — not a hardcoded switch. Panel toggle keys
    // are auto-registered from the panel tree. Custom actions can be
    // added via registerKey(). Task 4's event bus can hook into this.

    /** Action triggered by a key press while this screen is open. */
    @FunctionalInterface
    public interface KeyAction {
        boolean onKey(KeyEvent event, MenuKitHandledScreen screen);
    }

    private final Map<Integer, KeyAction> keyRegistry = new LinkedHashMap<>();

    /** Registers a key action. GLFW key code → action. */
    public void registerKey(int glfwKey, KeyAction action) {
        keyRegistry.put(glfwKey, action);
    }

    /**
     * Auto-registers panel toggle keys from the panel tree.
     * Called during init(). Each panel with a toggleKey gets a
     * registry entry that syncs visibility via the C2S mechanism.
     */
    private void registerPanelToggleKeys() {
        for (Panel panel : menu.getPanels()) {
            if (panel.getToggleKey() < 0) continue;
            String panelId = panel.getId();
            registerKey(panel.getToggleKey(), (event, screen) -> {
                screen.togglePanel(panelId);
                return true;
            });
        }
    }

    // ── Screen Event Bus ─────────────────────────────────────────────
    // Per-screen-instance event system. MenuKit-scoped — fires events
    // about things happening inside this MenuKit screen. Consumers
    // wanting ecosystem-wide slot events use Fabric's event API.
    //
    // Typed callbacks, not generic Object... varargs. Each method has
    // a default no-op so listeners only override what they care about.

    /**
     * Listener for events happening within a MenuKit screen.
     * All methods have default no-ops — override only what you need.
     */
    public interface ScreenEventListener {
        /** A MenuKitSlot was clicked. Button: 0=left, 1=right, 2=middle. */
        default void onSlotClick(MenuKitSlot slot, int button) {}
        /** An empty MenuKitSlot was clicked with an empty cursor. */
        default void onEmptySlotClick(MenuKitSlot slot, int button) {}
        /** Mouse entered a MenuKitSlot's hover area. */
        default void onSlotHoverEnter(MenuKitSlot slot) {}
        /** Mouse left a MenuKitSlot's hover area. */
        default void onSlotHoverExit(MenuKitSlot slot) {}
        /** A panel's visibility changed. */
        default void onPanelToggle(Panel panel, boolean nowVisible) {}
        /**
         * A shift-click (quick move) is about to happen on a MenuKitSlot.
         * Fires BEFORE vanilla routes the items — destination is unknown
         * at this point. The movedStack is a copy of what's in the source.
         *
         * <p>Use for logging, analytics, or pre-transfer checks. For
         * post-routing observation (where items ended up), a future
         * onAfterQuickMove event would fire from quickMoveStack.
         */
        default void onQuickMove(MenuKitSlot sourceSlot,
                                 net.minecraft.world.item.ItemStack movedStack) {}
    }

    private final List<ScreenEventListener> eventListeners = new ArrayList<>();

    /** Registers a screen event listener. Cleared on screen close. */
    public void addEventListener(ScreenEventListener listener) {
        eventListeners.add(listener);
    }

    private void fireSlotClick(MenuKitSlot slot, int button) {
        for (var listener : eventListeners) listener.onSlotClick(slot, button);
    }

    private void fireEmptySlotClick(MenuKitSlot slot, int button) {
        for (var listener : eventListeners) listener.onEmptySlotClick(slot, button);
    }

    private void fireSlotHoverEnter(MenuKitSlot slot) {
        for (var listener : eventListeners) listener.onSlotHoverEnter(slot);
    }

    private void fireSlotHoverExit(MenuKitSlot slot) {
        for (var listener : eventListeners) listener.onSlotHoverExit(slot);
    }

    private void firePanelToggle(Panel panel, boolean nowVisible) {
        for (var listener : eventListeners) listener.onPanelToggle(panel, nowVisible);
    }

    private void fireQuickMove(MenuKitSlot sourceSlot, net.minecraft.world.item.ItemStack movedStack) {
        for (var listener : eventListeners) listener.onQuickMove(sourceSlot, movedStack);
    }

    // ── Drag Modes ────────────────────────────────────────────────────
    // Per-screen drag mode registry. When a mouse drag starts on a
    // MenuKitSlot, the registry is checked for a mode that accepts
    // the drag. The active mode receives drag/end callbacks.

    /**
     * A custom drag behavior for MenuKit slots. The screen dispatches
     * drag events to the active mode. Only one mode is active at a time.
     */
    public interface DragMode {
        /**
         * Called when a drag starts on a MenuKitSlot.
         * Return true to claim the drag (subsequent drag/end go to this mode).
         */
        boolean onDragStart(MenuKitSlot slot, int button);
        /** Called when the mouse moves over a new slot during a drag. */
        void onDrag(MenuKitSlot slot);
        /** Called when the mouse button is released, ending the drag. */
        void onDragEnd();
    }

    private final List<DragMode> dragModes = new ArrayList<>();
    private @Nullable DragMode activeDrag = null;

    /** Registers a drag mode. Checked in order when a drag starts. */
    public void registerDragMode(DragMode mode) {
        dragModes.add(mode);
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
        return super.hasClickedOutside(mouseX, mouseY, leftPos, topPos);
    }

    /**
     * Key dispatch — checks the registry first, then falls through
     * to vanilla's keybind handling.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        KeyAction action = keyRegistry.get(event.key());
        if (action != null && action.onKey(event, this)) {
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * Click dispatch — fires events, checks group handlers, then
     * falls through to vanilla.
     *
     * <p>Order: element click → drag mode → right-click handler → event fire → vanilla.
     * Elements consume clicks first so buttons don't accidentally trigger
     * slot interactions or drag modes underneath them.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean flag) {
        // Element click — buttons and custom elements get first crack.
        // A button returning false from mouseClicked lets the click fall
        // through to drag modes and vanilla handling.
        if (dispatchElementClick(event.x(), event.y(), event.button())) {
            return true;
        }

        // Drag mode start — check if a registered mode claims this drag
        if (this.hoveredSlot instanceof MenuKitSlot mkSlot && activeDrag == null) {
            for (DragMode mode : dragModes) {
                if (mode.onDragStart(mkSlot, event.button())) {
                    activeDrag = mode;
                    return true; // drag claimed — skip vanilla
                }
            }
        }

        // Right-click handler dispatch (group-level capability)
        if (event.button() == 1 && this.hoveredSlot instanceof MenuKitSlot mkSlot) {
            BiConsumer<net.minecraft.world.entity.player.Player, MenuKitSlot> handler =
                    mkSlot.getGroup().getRightClickHandler();
            if (handler != null && this.minecraft != null && this.minecraft.player != null) {
                handler.accept(this.minecraft.player, mkSlot);
                fireSlotClick(mkSlot, event.button());
                return true; // consumed — don't let vanilla place an item
            }
        }

        // Fire slot click event (doesn't consume — vanilla still processes)
        if (this.hoveredSlot instanceof MenuKitSlot mkSlot) {
            fireSlotClick(mkSlot, event.button());

            // Empty slot click: slot has no item AND cursor is empty
            if (!mkSlot.hasItem() && this.menu.getCarried().isEmpty()) {
                fireEmptySlotClick(mkSlot, event.button());
            }

            // Quick move event: shift-click captures the stack before vanilla
            // routes it, so consumers can observe what's about to move.
            if (event.hasShiftDown() && mkSlot.hasItem()) {
                fireQuickMove(mkSlot, mkSlot.getItem().copy());
            }
        }

        return super.mouseClicked(event, flag);
    }

    /**
     * Drag dispatch — if a drag mode is active, route to it.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (activeDrag != null && this.hoveredSlot instanceof MenuKitSlot mkSlot) {
            activeDrag.onDrag(mkSlot);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    /**
     * Drag end — if a drag mode is active, notify and clear it.
     */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (activeDrag != null) {
            activeDrag.onDragEnd();
            activeDrag = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    // ── Element Click Dispatch ─────────────────────────────────────────
    // Checks all visible panels' visible elements for a click hit.
    // Hidden panels' elements are fully inert (like their slots).

    /**
     * Dispatches a click to panel elements. Returns true if any element
     * consumed the click. Checks panel visibility and element visibility
     * before routing — hidden panels' elements never receive clicks.
     */
    private boolean dispatchElementClick(double mouseX, double mouseY, int button) {
        for (Panel panel : menu.getPanels()) {
            if (!panel.isVisible()) continue;
            PanelBounds bounds = panelBounds.get(panel.getId());
            if (bounds == null) continue;

            int contentX = leftPos + bounds.x() + PANEL_PADDING;
            int contentY = topPos + bounds.y() + PANEL_PADDING;

            for (PanelElement element : panel.getElements()) {
                if (!element.isVisible()) continue;

                // Hit test: is the mouse within this element's bounds?
                double relX = mouseX - contentX - element.getChildX();
                double relY = mouseY - contentY - element.getChildY();
                if (relX >= 0 && relX < element.getWidth()
                        && relY >= 0 && relY < element.getHeight()) {
                    if (element.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
