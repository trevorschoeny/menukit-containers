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
    // Each group knows its flat index range. The handler also keeps a
    // lookup map for panel-level queries.
    private final Map<String, Panel> panelById = new LinkedHashMap<>();

    // ── Container Adapters ──────────────────────────────────────────────
    // One per SlotGroup, wrapping Storage → vanilla Container.
    // Kept here so they don't get GC'd while slots reference them.
    private final List<StorageContainerAdapter> adapters = new ArrayList<>();

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Constructs a handler from a pre-built panel tree.
     *
     * <p>Allocates MenuKitSlots in declaration order: for each panel,
     * for each group, for each slot index. Flat indices are sequential.
     */
    protected MenuKitScreenHandler(MenuType<?> type, int syncId, List<Panel> panels) {
        super(type, syncId);
        this.panels = List.copyOf(panels);

        // Wire owner references and build lookup maps
        for (Panel panel : this.panels) {
            panel.setOwner(this);
            panelById.put(panel.getId(), panel);
        }

        // Allocate slots in declaration order
        int flatIndex = 0;
        for (Panel panel : this.panels) {
            for (SlotGroup group : panel.getGroups()) {
                // Create the Container adapter for this group's Storage
                StorageContainerAdapter adapter = new StorageContainerAdapter(group.getStorage());
                adapters.add(adapter);

                // Record the flat index range on the group
                int groupStart = flatIndex;

                for (int local = 0; local < group.getStorage().size(); local++) {
                    // Temporary grid layout for testing — Phase 4a owns
                    // positioning properly via MenuKitHandledScreen.
                    int x = 8 + (flatIndex % 9) * 18;
                    int y = 18 + (flatIndex / 9) * 18;

                    MenuKitSlot slot = new MenuKitSlot(
                            adapter, local, x, y,
                            group, panel.getId(), group.getId(), local
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
            for (SlotGroup group : panel.getGroups()) {
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
            for (PanelConfig pc : panelConfigs) {
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
                panels.add(new Panel(pc.id, groups, pc.visible,
                        pc.style, pc.position, pc.toggleKey));
            }

            // Apply directional pairings (by ID reference)
            Map<String, SlotGroup> groupById = new HashMap<>();
            for (Panel panel : panels) {
                for (SlotGroup group : panel.getGroups()) {
                    groupById.put(panel.getId() + "." + group.getId(), group);
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

            return new MenuKitScreenHandler(menuType, syncId, panels);
        }
    }

    public static class PanelBuilder {
        private final String id;
        private final List<GroupConfig> groups = new ArrayList<>();
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

        /** Marks this panel as hidden initially. */
        public PanelBuilder hidden() {
            this.visible = false;
            return this;
        }

        PanelConfig build() {
            return new PanelConfig(id, groups, visible, style, position, toggleKey);
        }
    }

    // ── Builder Config Records ──────────────────────────────────────────

    private record PanelConfig(String id, List<GroupConfig> groups, boolean visible,
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
