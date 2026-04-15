package com.trevorschoeny.menukit.screen;

import com.trevorschoeny.menukit.core.*;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * The structural heart of MenuKit. Holds the Panel tree, owns the flat slot
 * list, owns the bidirectional coordinate mapping, and handles shift-click
 * routing and visibility transitions.
 *
 * <p>Proper subclass of {@link AbstractContainerMenu} — vanilla's sync
 * machinery traverses {@code this.slots} and anything that breaks
 * {@code instanceof Slot} breaks the world.
 *
 * <p>Implements {@link PanelOwner} so Panel can notify on visibility changes
 * without depending on this class directly.
 */
public class MenuKitScreenHandler extends AbstractContainerMenu implements PanelOwner {

    private static final Logger LOGGER = LoggerFactory.getLogger("MenuKit");

    // ── Panel Tree ──────────────────────────────────────────────────────
    private final List<Panel> panels;

    // ── Coordinate Map ──────────────────────────────────────────────────
    // Panel-id → Panel lookup for panel-level queries. Slot groups live
    // in a separate map below (context-specific machinery; Panel itself
    // is context-neutral and does not hold slot groups).
    private final Map<String, Panel> panelById = new LinkedHashMap<>();

    // ── Slot Groups (inventory-menu machinery) ──────────────────────────
    // Map from panel id to the list of slot groups attached to that panel.
    // Populated during handler construction, frozen thereafter. Slot groups
    // are inventory-menu-specific; they live on the handler rather than on
    // Panel so Panel stays context-neutral across all three rendering contexts.
    private final Map<String, List<SlotGroup>> groupsByPanel = new LinkedHashMap<>();

    // ── Container Adapters ──────────────────────────────────────────────
    // One per SlotGroup, wrapping Storage → vanilla Container.
    // Kept here so they don't get GC'd while slots reference them.
    private final List<StorageContainerAdapter> adapters = new ArrayList<>();

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Constructs a handler from a pre-built panel list and a map of slot
     * groups keyed by panel id.
     *
     * <p>Allocates MenuKitSlots in declaration order: for each panel,
     * for each of its slot groups (as supplied in {@code groupsByPanel}),
     * for each slot index. Flat indices are sequential.
     *
     * @param type          the menu type
     * @param syncId        the sync id
     * @param panels        the ordered panel list (each Panel holds only elements)
     * @param groupsByPanel map from panel id to the list of slot groups
     *                      attached to that panel, in declaration order
     */
    protected MenuKitScreenHandler(MenuType<?> type, int syncId,
                                   List<Panel> panels,
                                   Map<String, List<SlotGroup>> groupsByPanel) {
        super(type, syncId);
        this.panels = List.copyOf(panels);

        // Wire owner references and build panel lookup map
        for (Panel panel : this.panels) {
            panel.setOwner(this);
            panelById.put(panel.getId(), panel);
        }

        // Freeze the group map (copy the lists so external mutation can't
        // leak into the handler's state)
        for (Map.Entry<String, List<SlotGroup>> entry : groupsByPanel.entrySet()) {
            this.groupsByPanel.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        // Allocate slots in declaration order
        int flatIndex = 0;
        for (Panel panel : this.panels) {
            List<SlotGroup> groups = this.groupsByPanel.get(panel.getId());
            if (groups == null) continue; // panel has no slot groups (element-only panel)

            for (SlotGroup group : groups) {
                // Create the Container adapter for this group's Storage
                StorageContainerAdapter adapter = new StorageContainerAdapter(group.getStorage());
                adapters.add(adapter);

                // Record the flat index range on the group
                int groupStart = flatIndex;

                for (int local = 0; local < group.getStorage().size(); local++) {
                    // Temporary grid layout for testing — MenuKitHandledScreen
                    // positions slots properly during its layout pass.
                    int x = 8 + (flatIndex % 9) * 18;
                    int y = 18 + (flatIndex / 9) * 18;

                    MenuKitSlot slot = new MenuKitSlot(
                            adapter, local, x, y,
                            group, panel, group.getId(), local
                    );
                    this.addSlot(slot);
                    flatIndex++;
                }

                group.setFlatIndexRange(groupStart, flatIndex);
            }
        }

        LOGGER.info("[MenuKitScreenHandler] Constructed: {} panels, {} total slots",
                panels.size(), this.slots.size());
    }

    // ── Panel Access ────────────────────────────────────────────────────

    /** Returns the ordered list of panels (immutable). */
    public List<Panel> getPanels() { return panels; }

    /** Returns the panel with the given ID, or null. */
    public Panel getPanel(String panelId) { return panelById.get(panelId); }

    /**
     * Returns the slot groups attached to the panel with the given id, in
     * declaration order. Returns an empty list if the panel has no slot groups
     * (element-only panel) or the id is unknown.
     *
     * <p>Slot groups are inventory-menu machinery and live on the handler
     * rather than on Panel — Panel is context-neutral across all three
     * rendering contexts. Callers that need "which slot groups belong to
     * this panel" go through this accessor.
     */
    public List<SlotGroup> getGroupsFor(String panelId) {
        List<SlotGroup> groups = groupsByPanel.get(panelId);
        return groups != null ? groups : List.of();
    }

    /**
     * Returns the SlotGroup that owns the given flat slot index, or null
     * if the index is out of range or not a MenuKit slot.
     *
     * <p>Reverse lookup: flat index → group. Useful for consumers that
     * need to identify group membership from a slot index (e.g., shift-click
     * preview, analytics).
     */
    public SlotGroup getGroupContaining(int flatIndex) {
        if (flatIndex < 0 || flatIndex >= this.slots.size()) return null;
        net.minecraft.world.inventory.Slot slot = this.slots.get(flatIndex);
        if (slot instanceof MenuKitSlot mkSlot) {
            return mkSlot.getGroup();
        }
        return null;
    }

    // ── Visibility ──────────────────────────────────────────────────────

    /**
     * Sets a panel's visibility. Triggers a sync pass so the client
     * sees the change: hidden slots become EMPTY, shown slots get their
     * real stacks pushed.
     */
    public void setPanelVisible(String panelId, boolean visible) {
        Panel panel = panelById.get(panelId);
        if (panel == null) return;
        panel.setVisible(visible); // fires onPanelVisibilityChanged via PanelOwner
    }

    /**
     * Toggles a panel's visibility by index. Used with vanilla's
     * {@code clickMenuButton} C2S packet so visibility changes sync
     * between client and server.
     *
     * <p>Button ID maps to the panel index in the declaration order.
     * Returns true if the button was handled.
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (buttonId >= 0 && buttonId < panels.size()) {
            Panel panel = panels.get(buttonId);
            panel.setVisible(!panel.isVisible());
            return true;
        }
        return super.clickMenuButton(player, buttonId);
    }

    /**
     * Returns the button ID for toggling a panel by name. Returns -1 if
     * the panel isn't found. Use with
     * {@code gameMode.handleInventoryButtonClick(containerId, buttonId)}
     * on the client to send a C2S toggle.
     */
    public int getPanelButtonId(String panelId) {
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).getId().equals(panelId)) return i;
        }
        return -1;
    }

    /**
     * Called by Panel when its visibility changes. Triggers a sync pass
     * over the affected slots so the client sees EMPTY (hiding) or real
     * stacks (showing).
     */
    @Override
    public void onPanelVisibilityChanged(Panel panel) {
        // broadcastChanges compares slot.getItem() (which now lies for
        // hidden slots) against remoteSlots and sends differences.
        this.broadcastChanges();
    }

    // ── Shift-Click Routing ─────────────────────────────────────────────

    /**
     * Three-layer declarative shift-click routing.
     *
     * <ol>
     *   <li>Identify source group. If it doesn't export, return EMPTY.</li>
     *   <li>Collect candidates: groups that import, can accept the stack,
     *       and are visible (not inert).</li>
     *   <li>Order by: directional pairing first, then source-aware baseline,
     *       then declared priority descending.</li>
     *   <li>For each candidate: merge into partials, then fill empties.</li>
     *   <li>Stop when stack is consumed.</li>
     * </ol>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot rawSlot = this.slots.get(index);
        if (!(rawSlot instanceof MenuKitSlot sourceSlot)) return ItemStack.EMPTY;
        if (!sourceSlot.hasItem()) return ItemStack.EMPTY;

        SlotGroup sourceGroup = sourceSlot.getGroup();

        // Source must export
        if (!sourceGroup.getQmp().exports()) return ItemStack.EMPTY;

        ItemStack originalStack = sourceSlot.getItem().copy();
        ItemStack workingStack = sourceSlot.getItem();

        // Collect candidate groups
        List<SlotGroup> candidates = new ArrayList<>();
        for (Panel panel : panels) {
            if (!panel.isVisible()) continue; // skip inert panels
            for (SlotGroup group : getGroupsFor(panel.getId())) {
                if (group == sourceGroup) continue; // don't route to self
                if (!group.getQmp().imports()) continue; // must import
                if (!group.canAccept(workingStack)) continue; // policy filter
                candidates.add(group);
            }
        }

        if (candidates.isEmpty()) return ItemStack.EMPTY;

        // Sort candidates by three-layer priority
        boolean sourceIsPlayer = sourceGroup.getStorage() instanceof PlayerStorage;

        candidates.sort((a, b) -> {
            // Layer 1: Directional pairing — paired targets sort first
            boolean aPaired = sourceGroup.getPairedWith().contains(a);
            boolean bPaired = sourceGroup.getPairedWith().contains(b);
            if (aPaired != bPaired) return aPaired ? -1 : 1;

            // Layer 2: Source-aware baseline
            // If source is player-backed, prefer non-player destinations
            // If source is container-backed, prefer player destinations
            boolean aIsPlayer = a.getStorage() instanceof PlayerStorage;
            boolean bIsPlayer = b.getStorage() instanceof PlayerStorage;
            if (aIsPlayer != bIsPlayer) {
                if (sourceIsPlayer) {
                    // Prefer non-player (a non-player sorts before a player)
                    return aIsPlayer ? 1 : -1;
                } else {
                    // Prefer player
                    return aIsPlayer ? -1 : 1;
                }
            }

            // Layer 3: Declared priority (higher = first)
            return Integer.compare(b.getShiftClickPriority(), a.getShiftClickPriority());
        });

        // Try each candidate in order
        for (SlotGroup candidate : candidates) {
            if (workingStack.isEmpty()) break;

            int start = candidate.getFlatIndexStart();
            int end = candidate.getFlatIndexEnd();

            // moveItemStackTo handles merge-into-partials then fill-empties.
            // It modifies workingStack in place, reducing its count.
            this.moveItemStackTo(workingStack, start, end, false);
        }

        // Update the source slot
        if (workingStack.isEmpty()) {
            sourceSlot.setByPlayer(ItemStack.EMPTY);
        } else {
            sourceSlot.setChanged();
        }

        // Return original if something moved, EMPTY if nothing changed
        return workingStack.getCount() < originalStack.getCount() ? originalStack : ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // ── Container Adapter ───────────────────────────────────────────────
    //
    // Wraps a Storage into vanilla's Container interface so Slot's
    // constructor and vanilla's sync machinery work. Lives on the handler,
    // not on Storage, keeping Storage narrow.

    /**
     * Adapts a {@link Storage} to vanilla's {@link Container} interface.
     * Used internally during slot construction — consumers never see this.
     */
    static class StorageContainerAdapter implements Container {

        private final Storage storage;

        StorageContainerAdapter(Storage storage) {
            this.storage = storage;
        }

        @Override
        public int getContainerSize() {
            return storage.size();
        }

        @Override
        public int getMaxStackSize() {
            // Match vanilla SimpleContainer default (99).
            // Individual items cap themselves via ItemStack.getMaxStackSize().
            return 99;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < storage.size(); i++) {
                if (!storage.getStack(i).isEmpty()) return false;
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return storage.getStack(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack current = storage.getStack(slot);
            if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;

            ItemStack removed = current.split(amount);
            if (current.isEmpty()) {
                storage.setStack(slot, ItemStack.EMPTY);
            } else {
                storage.setStack(slot, current);
            }
            storage.markDirty();
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack current = storage.getStack(slot);
            storage.setStack(slot, ItemStack.EMPTY);
            return current;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            storage.setStack(slot, stack);
        }

        @Override
        public void setChanged() {
            storage.markDirty();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < storage.size(); i++) {
                storage.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    // ── Builder API ─────────────────────────────────────────────────────

    /**
     * Creates a builder for declaratively constructing a MenuKitScreenHandler.
     *
     * <pre>{@code
     * MenuKitScreenHandler.builder(MY_MENU_TYPE)
     *     .panel("main", p -> p
     *         .group("container", BlockEntityStorage.of(pos), InteractionPolicy.free())
     *         .group("player", PlayerStorage.of(player), InteractionPolicy.free()))
     *     .panel("upgrades", p -> p
     *         .group("slots", EphemeralStorage.of(4), InteractionPolicy.input(isUpgrade))
     *         .hidden())
     *     .build(syncId)
     * }</pre>
     */
    public static Builder builder(MenuType<?> menuType) {
        return new Builder(menuType);
    }

    public static class Builder {
        private final MenuType<?> menuType;
        private final List<PanelConfig> panelConfigs = new ArrayList<>();

        Builder(MenuType<?> menuType) {
            this.menuType = menuType;
        }

        /** Adds a panel with the given configuration. */
        public Builder panel(String id, Consumer<PanelBuilder> config) {
            PanelBuilder pb = new PanelBuilder(id);
            config.accept(pb);
            panelConfigs.add(pb.build());
            return this;
        }

        /** Produces the configured handler. */
        public MenuKitScreenHandler build(int syncId) {
            List<Panel> panels = new ArrayList<>();
            Map<String, List<SlotGroup>> groupsByPanel = new LinkedHashMap<>();

            for (PanelConfig pc : panelConfigs) {
                // Build the slot groups for this panel and register them in
                // the groupsByPanel map. Panel itself holds only elements.
                List<SlotGroup> groups = new ArrayList<>();
                for (GroupConfig gc : pc.groups) {
                    SlotGroup group = new SlotGroup(
                            gc.id, gc.storage, gc.policy, gc.qmp, gc.priority,
                            gc.columns, gc.rowGapAfter, gc.rowGapSize
                    );
                    if (gc.rightClickHandler != null) {
                        group.setRightClickHandler(gc.rightClickHandler);
                    }
                    groups.add(group);
                }
                groupsByPanel.put(pc.id, groups);
                panels.add(new Panel(pc.id, pc.elements,
                        pc.visible, pc.style, pc.position, pc.toggleKey));
            }

            // Apply directional pairings (by ID reference)
            Map<String, SlotGroup> groupById = new HashMap<>();
            for (Map.Entry<String, List<SlotGroup>> entry : groupsByPanel.entrySet()) {
                for (SlotGroup group : entry.getValue()) {
                    groupById.put(entry.getKey() + "." + group.getId(), group);
                }
            }
            for (PanelConfig pc : panelConfigs) {
                for (GroupConfig gc : pc.groups) {
                    SlotGroup source = groupById.get(pc.id + "." + gc.id);
                    if (source != null) {
                        for (String targetRef : gc.pairingTargets) {
                            SlotGroup target = groupById.get(targetRef);
                            if (target != null) {
                                source.pairsWith(target);
                            }
                        }
                    }
                }
            }

            return new MenuKitScreenHandler(menuType, syncId, panels, groupsByPanel);
        }
    }

    public static class PanelBuilder {
        private final String id;
        private final List<GroupConfig> groups = new ArrayList<>();
        private final List<PanelElement> elements = new ArrayList<>();
        private boolean visible = true;
        private PanelStyle style = PanelStyle.RAISED;
        private PanelPosition position = PanelPosition.BODY;
        private int toggleKey = -1;

        PanelBuilder(String id) {
            this.id = id;
        }

        /** Sets a GLFW key code that toggles this panel's visibility. */
        public PanelBuilder toggleKey(int glfwKey) {
            this.toggleKey = glfwKey;
            return this;
        }

        /** Sets the visual style for this panel's background. Default: RAISED. */
        public PanelBuilder style(PanelStyle style) {
            this.style = style;
            return this;
        }

        /** Positions this panel to the right of the named anchor panel. */
        public PanelBuilder rightOf(String anchorPanelId) {
            this.position = PanelPosition.rightOf(anchorPanelId);
            return this;
        }

        /** Positions this panel to the left of the named anchor panel. */
        public PanelBuilder leftOf(String anchorPanelId) {
            this.position = PanelPosition.leftOf(anchorPanelId);
            return this;
        }

        /** Positions this panel above the named anchor panel. */
        public PanelBuilder above(String anchorPanelId) {
            this.position = PanelPosition.above(anchorPanelId);
            return this;
        }

        /** Positions this panel below the named anchor panel. */
        public PanelBuilder below(String anchorPanelId) {
            this.position = PanelPosition.below(anchorPanelId);
            return this;
        }

        /** Adds a slot group with default QMP (BOTH), priority (100), auto columns. */
        public PanelBuilder group(String id, Storage storage, InteractionPolicy policy) {
            groups.add(new GroupConfig(id, storage, policy,
                    QuickMoveParticipation.BOTH, 100, -1, -1, 0));
            return this;
        }

        /** Adds a slot group with explicit QMP, priority, and auto columns. */
        public PanelBuilder group(String id, Storage storage, InteractionPolicy policy,
                                  QuickMoveParticipation qmp, int priority) {
            groups.add(new GroupConfig(id, storage, policy, qmp, priority,
                    -1, -1, 0));
            return this;
        }

        /** Adds a slot group with explicit QMP, priority, and column count. */
        public PanelBuilder group(String id, Storage storage, InteractionPolicy policy,
                                  QuickMoveParticipation qmp, int priority, int columns) {
            groups.add(new GroupConfig(id, storage, policy, qmp, priority,
                    columns, -1, 0));
            return this;
        }

        /** Adds a slot group with full layout control including row gap. */
        public PanelBuilder group(String id, Storage storage, InteractionPolicy policy,
                                  QuickMoveParticipation qmp, int priority, int columns,
                                  int rowGapAfter, int rowGapSize) {
            groups.add(new GroupConfig(id, storage, policy, qmp, priority,
                    columns, rowGapAfter, rowGapSize));
            return this;
        }

        /**
         * Sets a right-click handler on the last-added group.
         * Invoked when a slot in that group is right-clicked.
         */
        public PanelBuilder rightClick(
                java.util.function.BiConsumer<net.minecraft.world.entity.player.Player, MenuKitSlot> handler) {
            if (!groups.isEmpty()) {
                // Replace last group config with one that includes the handler
                GroupConfig last = groups.remove(groups.size() - 1);
                groups.add(new GroupConfig(last.id, last.storage, last.policy,
                        last.qmp, last.priority, last.columns, last.rowGapAfter,
                        last.rowGapSize, last.pairingTargets, handler));
            }
            return this;
        }

        // ── Panel Elements ──────────────────────────────────────────

        /** Adds a button element at the given position within the panel content area. */
        public PanelBuilder button(int childX, int childY, int width, int height,
                                   net.minecraft.network.chat.Component text,
                                   java.util.function.Consumer<Button> onClick) {
            elements.add(new Button(childX, childY, width, height, text, onClick));
            return this;
        }

        /** Adds a text label at the given position (dark gray, no shadow — vanilla style). */
        public PanelBuilder text(int childX, int childY,
                                 net.minecraft.network.chat.Component text) {
            elements.add(new TextLabel(childX, childY, text));
            return this;
        }

        /** Adds a text label with explicit color and shadow. */
        public PanelBuilder text(int childX, int childY,
                                 net.minecraft.network.chat.Component text,
                                 int color, boolean shadow) {
            elements.add(new TextLabel(childX, childY, text, color, shadow));
            return this;
        }

        /**
         * Adds a text label with supplier-driven content (dark gray, no shadow).
         * For explicit color/shadow with a supplier, use
         * {@code .element(new TextLabel(x, y, supplier, color, shadow))}.
         */
        public PanelBuilder text(int childX, int childY,
                                 java.util.function.Supplier<net.minecraft.network.chat.Component> text) {
            elements.add(new TextLabel(childX, childY, text));
            return this;
        }

        /** Adds an icon element with a fixed sprite. */
        public PanelBuilder icon(int childX, int childY, int width, int height,
                                 net.minecraft.resources.Identifier sprite) {
            elements.add(new Icon(childX, childY, width, height, sprite));
            return this;
        }

        /** Adds an icon element whose sprite is driven by a supplier. */
        public PanelBuilder icon(int childX, int childY, int width, int height,
                                 java.util.function.Supplier<net.minecraft.resources.Identifier> sprite) {
            elements.add(new Icon(childX, childY, width, height, sprite));
            return this;
        }

        /** Adds a horizontal divider with the default color and thickness. */
        public PanelBuilder horizontalDivider(int childX, int childY, int length) {
            elements.add(Divider.horizontal(childX, childY, length));
            return this;
        }

        /** Adds a horizontal divider with explicit color and thickness. */
        public PanelBuilder horizontalDivider(int childX, int childY, int length,
                                              int color, int thickness) {
            elements.add(Divider.horizontal(childX, childY, length, color, thickness));
            return this;
        }

        /** Adds a vertical divider with the default color and thickness. */
        public PanelBuilder verticalDivider(int childX, int childY, int length) {
            elements.add(Divider.vertical(childX, childY, length));
            return this;
        }

        /** Adds a vertical divider with explicit color and thickness. */
        public PanelBuilder verticalDivider(int childX, int childY, int length,
                                            int color, int thickness) {
            elements.add(Divider.vertical(childX, childY, length, color, thickness));
            return this;
        }

        /** Adds an item display with a fixed stack, default 16×16 size, overlays on. */
        public PanelBuilder itemDisplay(int childX, int childY,
                                        net.minecraft.world.item.ItemStack stack) {
            elements.add(new ItemDisplay(childX, childY, stack));
            return this;
        }

        /** Adds an item display with a supplier-driven stack, default size, overlays on. */
        public PanelBuilder itemDisplay(int childX, int childY,
                                        java.util.function.Supplier<net.minecraft.world.item.ItemStack> stack) {
            elements.add(new ItemDisplay(childX, childY, stack));
            return this;
        }

        /** Adds an item display with a fixed stack, explicit size, explicit overlays. */
        public PanelBuilder itemDisplay(int childX, int childY, int size,
                                        net.minecraft.world.item.ItemStack stack,
                                        boolean showCount, boolean showDurability) {
            elements.add(new ItemDisplay(childX, childY, size, stack, showCount, showDurability));
            return this;
        }

        /** Adds an item display with a supplier-driven stack, explicit size, explicit overlays. */
        public PanelBuilder itemDisplay(int childX, int childY, int size,
                                        java.util.function.Supplier<net.minecraft.world.item.ItemStack> stack,
                                        boolean showCount, boolean showDurability) {
            elements.add(new ItemDisplay(childX, childY, size, stack, showCount, showDurability));
            return this;
        }

        /**
         * Adds a progress bar with a fixed value, left-to-right direction,
         * and default colors. For direction, custom colors, or a label, use
         * {@code .element(new ProgressBar(...))} with the full constructor.
         */
        public PanelBuilder progressBar(int childX, int childY, int width, int height, float value) {
            elements.add(new ProgressBar(childX, childY, width, height, value));
            return this;
        }

        /**
         * Adds a progress bar with a supplier-driven value, left-to-right
         * direction, and default colors. For direction, custom colors, or a
         * label, use {@code .element(new ProgressBar(...))} with the full
         * constructor.
         */
        public PanelBuilder progressBar(int childX, int childY, int width, int height,
                                        java.util.function.Supplier<Float> value) {
            elements.add(new ProgressBar(childX, childY, width, height, value));
            return this;
        }

        /**
         * Adds an always-enabled toggle. For a disabled-predicate toggle,
         * use {@code .element(new Toggle(..., disabledWhen))}.
         */
        public PanelBuilder toggle(int childX, int childY, int width, int height,
                                   boolean initialState,
                                   java.util.function.Consumer<Boolean> onToggle) {
            elements.add(new Toggle(childX, childY, width, height, initialState, onToggle));
            return this;
        }

        /**
         * Adds a labeled checkbox with the fixed-label form. For supplier-based
         * labels or a disabled predicate, use {@code .element(new Checkbox(...))}.
         */
        public PanelBuilder checkbox(int childX, int childY, boolean initialState,
                                     net.minecraft.network.chat.Component label,
                                     java.util.function.Consumer<Boolean> onToggle) {
            elements.add(new Checkbox(childX, childY, initialState, label, onToggle));
            return this;
        }

        /**
         * Adds a radio button referencing an externally-constructed
         * {@link RadioGroup}. Multiple Radios sharing the same group form a
         * single-selection set. For supplier-based labels or a disabled
         * predicate, use {@code .element(new Radio<>(...))}.
         */
        public <T> PanelBuilder radio(int childX, int childY, T value,
                                      net.minecraft.network.chat.Component label,
                                      RadioGroup<T> group) {
            elements.add(new Radio<>(childX, childY, value, label, group));
            return this;
        }

        /**
         * Adds a standalone Tooltip element — a persistent info box rendered
         * at the declared position with a RAISED panel background. Distinct
         * from hover-triggered tooltips attached to interactive elements via
         * their {@code .tooltip(...)} setters. For supplier-based text, use
         * {@code .element(new Tooltip(x, y, supplier))}.
         */
        public PanelBuilder tooltip(int childX, int childY,
                                    net.minecraft.network.chat.Component text) {
            elements.add(new Tooltip(childX, childY, text));
            return this;
        }

        /** Adds an arbitrary panel element (for custom element types). */
        public PanelBuilder element(PanelElement element) {
            elements.add(element);
            return this;
        }

        /** Marks this panel as hidden initially. */
        public PanelBuilder hidden() {
            this.visible = false;
            return this;
        }

        PanelConfig build() {
            return new PanelConfig(id, groups, List.copyOf(elements),
                    visible, style, position, toggleKey);
        }
    }

    // ── Builder Config Records ──────────────────────────────────────────

    private record PanelConfig(String id, List<GroupConfig> groups,
                               List<PanelElement> elements, boolean visible,
                               PanelStyle style, PanelPosition position, int toggleKey) {}

    private record GroupConfig(
            String id, Storage storage, InteractionPolicy policy,
            QuickMoveParticipation qmp, int priority,
            int columns, int rowGapAfter, int rowGapSize,
            List<String> pairingTargets,
            java.util.function.BiConsumer<net.minecraft.world.entity.player.Player, MenuKitSlot> rightClickHandler
    ) {
        GroupConfig(String id, Storage storage, InteractionPolicy policy,
                    QuickMoveParticipation qmp, int priority,
                    int columns, int rowGapAfter, int rowGapSize) {
            this(id, storage, policy, qmp, priority, columns, rowGapAfter, rowGapSize, List.of(), null);
        }
    }
}
