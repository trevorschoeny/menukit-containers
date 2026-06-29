package com.trevorschoeny.menukit.screen;

import com.trevorschoeny.menukit.core.*;
import com.trevorschoeny.menukit.window.WindowEngine;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
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
public class MKCScreenHandler extends AbstractContainerMenu implements PanelOwner {

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
     * <p>Allocates MKCSlots in declaration order: for each panel,
     * for each of its slot groups (as supplied in {@code groupsByPanel}),
     * for each slot index. Flat indices are sequential.
     *
     * @param type          the menu type
     * @param syncId        the sync id
     * @param panels        the ordered panel list (each Panel holds only elements)
     * @param groupsByPanel map from panel id to the list of slot groups
     *                      attached to that panel, in declaration order
     */
    protected MKCScreenHandler(MenuType<?> type, int syncId,
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
                    // Temporary grid layout for testing — MKCHandledScreen
                    // positions slots properly during its layout pass.
                    int x = 8 + (flatIndex % 9) * 18;
                    int y = 18 + (flatIndex / 9) * 18;

                    MKCSlot slot = new MKCSlot(
                            adapter, local, x, y,
                            group, panel, group.getId(), local
                    );
                    this.addSlot(slot);
                    flatIndex++;
                }

                group.setFlatIndexRange(groupStart, flatIndex);
            }
        }

        LOGGER.info("[MKCScreenHandler] Constructed: {} panels, {} total slots",
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
        if (slot instanceof MKCSlot mkSlot) {
            return mkSlot.getGroup();
        }
        return null;
    }

    /**
     * Finds the {@link SlotGroupLike} that a slot belongs to, fast-pathing
     * MenuKit-owned slots and delegating to MK's recognition for observed
     * vanilla slots.
     *
     * <p>Per §0043 (Complete-on-Side Feature Ownership), this is the MKC-side
     * facade for combined owned + observed lookup. Consumer mods with a
     * {@link MKCScreenHandler} in hand call this for the fast-path;
     * consumer mods working only against vanilla handlers call MK's static
     * {@link HandlerRecognizerRegistry#findGroup(AbstractContainerMenu, Slot)}
     * directly.
     *
     * @param slot the slot to look up
     * @return the owning group (owned fast-path or observed recognition), or
     *         empty if no group contains the slot
     *
     * @implNote <b>Internal plumbing.</b> Takes a raw vanilla {@link Slot}; its
     *           only caller is the library's contract verification. Consumers
     *           reach a group by panel id ({@link #getGroupsFor}) or by the stable
     *           handler-local flat index ({@link #getGroupContaining}), not by
     *           handing back a raw {@code Slot}.
     */
    @ApiStatus.Internal
    public Optional<SlotGroupLike> findGroupForSlot(Slot slot) {
        if (slot instanceof MKCSlot mkSlot) {
            return Optional.of(mkSlot.getGroup());
        }
        return HandlerRecognizerRegistry.findGroup(this, slot);
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
        if (!(rawSlot instanceof MKCSlot sourceSlot)) return ItemStack.EMPTY;
        if (!sourceSlot.hasItem()) return ItemStack.EMPTY;

        SlotGroup sourceGroup = sourceSlot.getGroup();

        // Source must export — QUICK_MOVE resolved from the engine by the source
        // slot's address (no longer the group's retired qmp knob).
        if (!qmpOf(sourceSlot).exports()) return ItemStack.EMPTY;

        ItemStack originalStack = sourceSlot.getItem().copy();
        ItemStack workingStack = sourceSlot.getItem();

        // One representative live MKCSlot per group, for resolving QUICK_MOVE /
        // GATING from the engine by address.
        Map<SlotGroup, MKCSlot> reps = groupRepresentatives();

        // Collect candidate groups
        List<SlotGroup> candidates = new ArrayList<>();
        for (Panel panel : panels) {
            if (!panel.isVisible()) continue; // skip inert panels
            for (SlotGroup group : getGroupsFor(panel.getId())) {
                if (group == sourceGroup) continue; // don't route to self
                MKCSlot rep = reps.get(group);
                if (rep == null) continue;                       // no live slot — can't resolve
                if (!qmpOf(rep).imports()) continue;             // must import (engine)
                if (!rep.mayPlace(workingStack)) continue;       // accepts (engine GATING + inertness)
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

    /** A created slot's quick-move participation, resolved from the engine by its address. */
    private static QuickMoveParticipation qmpOf(MKCSlot slot) {
        return WindowEngine.resolve(slot.address(), MKCBehaviorKeys.QUICK_MOVE);
    }

    /** One representative live MKCSlot per group present in this menu (first wins). */
    private Map<SlotGroup, MKCSlot> groupRepresentatives() {
        Map<SlotGroup, MKCSlot> reps = new HashMap<>();
        for (Slot s : this.slots) {
            if (s instanceof MKCSlot mk) reps.putIfAbsent(mk.getGroup(), mk);
        }
        return reps;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    // ── Container Adapter ───────────────────────────────────────────────
    // Extracted to core/StorageContainerAdapter.java so M4 consumers
    // registering slots onto vanilla handlers can reuse it. The adapter
    // bridges Storage → Container for Slot construction.

    // ── Builder API ─────────────────────────────────────────────────────

    /**
     * Creates a builder for declaratively constructing a MKCScreenHandler.
     *
     * <pre>{@code
     * // Mod-init: declare attachments via M7's StorageAttachment.
     * public static final StorageAttachment<BlockEntity, NonNullList<ItemStack>> CHEST_EXTRA =
     *     StorageAttachment.blockScoped("my-mod", "chest_extra", 9);
     * public static final StorageAttachment<Player, NonNullList<ItemStack>> POCKETS =
     *     StorageAttachment.playerAttached("my-mod", "pockets", 27);
     *
     * // Menu construction: bind attachments to owner instances. Groups are
     * // structure-only; arm any slot behavior (e.g. an upgrade-only gate) by
     * // Address at init via Window.slot(address).set(MKCBehaviorKeys.GATING, gate).
     * MKCScreenHandler.builder(MY_MENU_TYPE)
     *     .panel("main", p -> p
     *         .group("container", CHEST_EXTRA.bind(blockEntity))
     *         .group("player", POCKETS.bind(player)))
     *     .panel("upgrades", p -> p
     *         .group("slots", EphemeralStorage.of(4))
     *         .hidden())
     *     .build(syncId)
     * }</pre>
     *
     * <h3>Namespace your panel ids</h3>
     *
     * A created slot's {@link com.trevorschoeny.menukit.window.Address} is global, keyed
     * by {@code (panelId, groupId, localIndex)} — NOT scoped by the owning
     * {@link com.trevorschoeny.menukit.screen.MKCMenu}'s {@code Identifier}. So two
     * mods (or two menus) that both name a panel {@code "main"} share an address
     * sub-space and would arm each other's slots. <b>Namespace each panel id per-mod</b>
     * — e.g. {@code "mymod:menu:main"} — exactly as container-parity panel ids already
     * are. The same id is used both for layout references ({@code rightOf} / {@code below}
     * / {@code pairsWith}) and for addressing the panel's created slots
     * ({@code CreatedSlotAdapter.addressOf(panelId, groupId, i)}), so one namespaced id
     * covers both concerns.
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
        public MKCScreenHandler build(int syncId) {
            List<Panel> panels = new ArrayList<>();
            Map<String, List<SlotGroup>> groupsByPanel = new LinkedHashMap<>();

            for (PanelConfig pc : panelConfigs) {
                // Build the slot groups for this panel and register them in
                // the groupsByPanel map. Panel itself holds only elements.
                List<SlotGroup> groups = new ArrayList<>();
                for (GroupConfig gc : pc.groups) {
                    SlotGroup group = new SlotGroup(
                            gc.id, gc.storage, gc.priority,
                            gc.columns, gc.rowGapAfter, gc.rowGapSize
                    );
                    if (gc.rightClickHandler != null) {
                        group.setRightClickHandler(gc.rightClickHandler);
                    }
                    groups.add(group);
                }
                groupsByPanel.put(pc.id, groups);
                panels.add(Panel.builder(pc.id)
                        .elements(pc.elements)
                        .visible(pc.visible)
                        .style(pc.style)
                        .position(pc.position)
                        .toggleKey(pc.toggleKey)
                        .build());
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

            return new MKCScreenHandler(menuType, syncId, panels, groupsByPanel);
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

        /**
         * Adds a structure-only slot group (default priority 100, auto columns).
         *
         * <h4>Where each kind of "slot behavior" lives</h4>
         *
         * The group config carries <b>structural</b> behavior — the parts that are a
         * property of the group's place in the menu, not of an individual slot:
         * {@link #rightClick} (group-level right-click handler), {@link #pairsWith}
         * (directional shift-click routing between groups), and the layout knobs
         * (priority / columns / row gap). These stay on the builder because they are
         * group structure.
         *
         * <p>Per-slot <b>gating / binding / mending / quick-move</b> is NOT set here —
         * it is armed in THE ONE WINDOW engine by each built slot's
         * {@link com.trevorschoeny.menukit.core.MKCSlot#address()}, identically to a
         * vanilla slot. On the custom-menu path arm it via
         * {@code Window.slot(CreatedSlotAdapter.addressOf(panelId, groupId, i)).set(KEY, value)}
         * — ideally inside the {@link com.trevorschoeny.menukit.screen.MKCMenu.Builder#arm}
         * hook so it rides the same define chain. (On the container-parity path, the
         * {@link com.trevorschoeny.menukit.core.SlotSpec} inline verbs —
         * {@code .gate(...)}, {@code .binding()}, {@code .mending()}, {@code .quickMove(...)}
         * — arm the same keys for you.) An un-armed slot is pure vanilla (engine
         * defaults: gating OPEN, quick-move BOTH, binding/mending off). See
         * {@code MKCBehaviorKeys}.
         */
        public PanelBuilder group(String id, Storage storage) {
            groups.add(new GroupConfig(id, storage, 100, -1, -1, 0));
            return this;
        }

        /** Adds a slot group with explicit priority, auto columns. (Structure-only — see {@link #group(String, Storage)}.) */
        public PanelBuilder group(String id, Storage storage, int priority) {
            groups.add(new GroupConfig(id, storage, priority, -1, -1, 0));
            return this;
        }

        /** Adds a slot group with explicit priority + column count. (Structure-only — see {@link #group(String, Storage)}.) */
        public PanelBuilder group(String id, Storage storage, int priority, int columns) {
            groups.add(new GroupConfig(id, storage, priority, columns, -1, 0));
            return this;
        }

        /** Adds a slot group with full layout control including row gap. (Structure-only — see {@link #group(String, Storage)}.) */
        public PanelBuilder group(String id, Storage storage, int priority, int columns,
                                  int rowGapAfter, int rowGapSize) {
            groups.add(new GroupConfig(id, storage, priority, columns, rowGapAfter, rowGapSize));
            return this;
        }

        /**
         * Sets a right-click handler on the last-added group.
         * Invoked when a slot in that group is right-clicked.
         */
        public PanelBuilder rightClick(
                java.util.function.BiConsumer<net.minecraft.world.entity.player.Player, MKCSlot> handler) {
            if (!groups.isEmpty()) {
                // Replace last group config with one that includes the handler
                GroupConfig last = groups.remove(groups.size() - 1);
                groups.add(new GroupConfig(last.id, last.storage,
                        last.priority, last.columns, last.rowGapAfter,
                        last.rowGapSize, last.pairingTargets, handler));
            }
            return this;
        }

        /**
         * Declares a directional pairing from the last-added group to a target
         * group, identified by {@code "{panelId}.{groupId}"}. Paired targets
         * sort first in shift-click routing (Layer 1 in
         * {@link MKCScreenHandler#quickMoveStack}), ahead of the
         * source-aware baseline and declared priority.
         *
         * <p>Exposure-only wrapper over the already-shipped routing semantics
         * ({@link SlotGroup#pairsWith} + {@link SlotGroup#getPairedWith}).
         * Modeled on {@link #rightClick}: replaces the last group config with
         * one that appends a target to its pairing-targets list. The build
         * loop resolves the string references to live {@link SlotGroup}
         * instances and wires the pairings before the handler is constructed.
         *
         * <p>Call once per target to declare a pairing; multiple calls stack
         * to declare multi-target pairing from the same source group.
         */
        public PanelBuilder pairsWith(String targetPanelId, String targetGroupId) {
            if (!groups.isEmpty()) {
                GroupConfig last = groups.remove(groups.size() - 1);
                List<String> newTargets = new ArrayList<>(last.pairingTargets);
                newTargets.add(targetPanelId + "." + targetGroupId);
                groups.add(new GroupConfig(last.id, last.storage,
                        last.priority, last.columns, last.rowGapAfter,
                        last.rowGapSize, newTargets, last.rightClickHandler));
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
         * direction, and default colors. {@code value} is a {@code DoubleSupplier}
         * returning a normalized {@code 0.0–1.0} progress — the canonical
         * numeric-supplier shape, so a {@code double}-valued source feeds it with
         * no box-and-cast. For direction, custom colors, or a label, use
         * {@code .element(new ProgressBar(...))} with the full constructor.
         */
        public PanelBuilder progressBar(int childX, int childY, int width, int height,
                                        java.util.function.DoubleSupplier value) {
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
         * Adds a standalone {@link com.trevorschoeny.menukit.core.InfoBox}
         * element — a persistent info box rendered at the declared position
         * with a RAISED panel background. Distinct from hover-triggered
         * tooltips attached to interactive elements via their
         * {@code .tooltip(...)} setters. For supplier-based text, use
         * {@code .element(new InfoBox(x, y, supplier))}.
         */
        public PanelBuilder infoBox(int childX, int childY,
                                    net.minecraft.network.chat.Component text) {
            elements.add(new InfoBox(childX, childY, text));
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
            String id, Storage storage, int priority,
            int columns, int rowGapAfter, int rowGapSize,
            List<String> pairingTargets,
            java.util.function.BiConsumer<net.minecraft.world.entity.player.Player, MKCSlot> rightClickHandler
    ) {
        GroupConfig(String id, Storage storage, int priority,
                    int columns, int rowGapAfter, int rowGapSize) {
            this(id, storage, priority, columns, rowGapAfter, rowGapSize, List.of(), null);
        }
    }
}
