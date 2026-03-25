package com.trevorschoeny.menukit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Central registry and lifecycle manager for the MenuKit framework.
 *
 * <p>Panel definitions are registered at mod init via {@link MKPanel#builder(String)}.
 * MenuKit's internal mixins then handle:
 * <ul>
 *   <li>Creating MKContainers and MKSlots during menu construction (server + client)</li>
 *   <li>Creating MKButtons during screen initialization (client only)</li>
 *   <li>Rendering panel backgrounds (client only)</li>
 *   <li>Saving/loading container items to player NBT (server)</li>
 * </ul>
 *
 * <p>Users never interact with this class directly — everything goes through
 * {@link MKPanel#builder(String)}.
 *
 * <p>Part of the <b>MenuKit</b> framework.
 */
public class MenuKit implements ModInitializer {

    /** MenuKit's own logger — independent of any consuming mod's logger. */
    public static final Logger LOGGER = LoggerFactory.getLogger("menukit");

    // ── Standard container dimensions ──────────────────────────────────────
    // InventoryMenu (survival): 176×166
    // CreativeModeInventoryScreen: 195×136
    public static final int SURVIVAL_WIDTH = 176;
    public static final int SURVIVAL_HEIGHT = 166;
    public static final int CREATIVE_WIDTH = 195;
    public static final int CREATIVE_HEIGHT = 136;

    // ── Panel definitions (registered at mod init, immutable after startup) ──
    private static final Map<String, MKPanelDef> panels = new LinkedHashMap<>();

    // ── Container definitions (registered at mod init, before panels) ────────
    // Containers are first-class storage objects, separate from panels.
    // A panel's slots reference containers by name.
    private static final Map<String, MKContainerDef> containerDefs = new LinkedHashMap<>();

    // ── Per-player live containers (for PLAYER-bound containers) ─────────────
    // Server containers: authoritative, persisted to player NBT.
    // Client containers: synced from server via broadcastChanges, used by HUD.
    // Matches vanilla's pattern: server Inventory + client Inventory, separate objects.
    // Outer key: player UUID. Inner key: container name.
    private static final Map<UUID, Map<String, MKContainer>> playerServerContainers = new HashMap<>();
    private static final Map<UUID, Map<String, MKContainer>> playerClientContainers = new HashMap<>();

    // ── Button groups per screen session (recreated on each screen init) ─────
    // Key: group name (from MKButtonDef.groupName). Recreated per screen init.
    private static final Map<String, MKButtonGroup> activeGroups = new HashMap<>();

    // ── Live computed panel sizes (calculated after buttons are created) ─────
    // Key: panel name. Computed during screen init when button sizes are known.
    private static final Map<String, int[]> livePanelSizes = new HashMap<>();

    // ── Vanilla slot → panel mapping (map-based tracking) ───────────────────
    // Instead of replacing vanilla slots with MKSlotWrappers, we track which
    // vanilla slot indices belong to which MK panels. This is safe — vanilla
    // slots remain untouched, but MenuKit knows their panel association for
    // shift-click routing and feature application.
    // Key: menu identity hash → Map<slotIndex, panelName>
    private static final Map<Integer, Map<Integer, String>> menuSlotPanelMaps = new HashMap<>();

    // ── Per-menu vanilla container wrappers ───────────────────────────────────
    // Auto-created when a menu is constructed. Maps container name → MKContainer
    // wrapping the vanilla Container backing those slots.
    // Key: menu identity hash → Map<containerName, MKContainer>
    private static final Map<Integer, Map<String, MKContainer>> menuContainerMaps = new HashMap<>();

    // ── Collision-avoidance resolved positions ──────────────────────────────
    // Key: panel name. Resolved each frame with collision avoidance applied.
    // Values are container-relative {x, y}.
    private static final Map<String, int[]> resolvedPositions = new LinkedHashMap<>();
    private static int lastResolvedWidth = -1, lastResolvedHeight = -1;
    private static @Nullable MKContext lastResolvedContext = null;

    // ── HUD panel definitions (registered at mod init, client-only) ────────
    private static final Map<String, MKHudPanelDef> hudPanels = new LinkedHashMap<>();

    // ── Notification definitions (registered at mod init) ────────────────
    private static final Map<String, MKHudNotification> notificationDefs = new LinkedHashMap<>();

    // ── Active notifications (runtime state) ─────────────────────────────
    private static final Map<String, ActiveNotification> activeNotifications = new LinkedHashMap<>();

    record ActiveNotification(long triggerTimeMs, @Nullable String textData,
                              @Nullable ItemStack itemData) {}

    // ── Hover tracking (client-only, updated each frame) ──────────────────
    // MenuKit does its own hover detection instead of relying on vanilla's
    // hoveredSlot field. Vanilla only detects hover within the container's
    // imageWidth/imageHeight bounding box, so MKSlots positioned outside
    // (like pocket panels) are invisible to vanilla's hoveredSlot.
    // Our detection runs in renderSlotBackgrounds and works everywhere.
    private static net.minecraft.world.inventory.Slot hoveredMKSlot = null; // nullable
    private static @Nullable String hoveredPanelName = null;


    // Last known good mouse position in screen space.
    // Captured from renderContents() — the single reliable source across all
    // screen subclasses (InventoryScreen and CreativeModeInventoryScreen both
    // override render(), making it unreliable as a capture point).
    // Used by renderSlotBackgrounds for hover detection and by the tooltip
    // renderer for positioning.
    private static int lastMouseX = 0, lastMouseY = 0;

    /** Captures the current screen-space mouse position. Called from renderContents(). */
    public static void captureMousePosition(int mouseX, int mouseY) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    /** Returns the last captured screen-space mouse X. */
    public static int getLastMouseX() { return lastMouseX; }

    /** Returns the last captured screen-space mouse Y. */
    public static int getLastMouseY() { return lastMouseY; }

    // ── Panel visibility ───────────────────────────────────────────────────
    // Tracks which panels are currently hidden. Client-side only.
    private static final Set<String> hiddenPanels = new HashSet<>();

    // ── Standalone persistence handlers ────────────────────────────────────
    // Allows any feature to save/load arbitrary data without creating a panel.
    // Key: unique string identifier. Value: save and load callbacks.
    // Hooked into the same player NBT lifecycle as panel onSave/onLoad.
    // The key is used as the NBT tag name within the player's MenuKit data.
    private static final Map<String, PersistenceHandler> persistenceHandlers = new LinkedHashMap<>();

    /**
     * A pair of save/load callbacks for standalone persistence.
     * Registered via {@link #registerPersistence(String, Consumer, Consumer)}.
     */
    private record PersistenceHandler(Consumer<ValueOutput> save, Consumer<ValueInput> load) {}

    // ── Family registry ─────────────────────────────────────────────────────
    // Families group multiple mods under a shared config screen + keybind
    // category. Each call to family(id) returns the same MKFamily instance.
    private static final Map<String, MKFamily> families = new LinkedHashMap<>();

    // ── Screen navigation (one-level back) ─────────────────────────────────
    // Captured before opening a standalone screen, restored by goBack().
    private static net.minecraft.client.gui.screens.Screen previousScreen; // nullable

    // ── Standalone screen menu type ────────────────────────────────────────
    // One shared MenuType for ALL standalone MenuKit screens. The String
    // payload is the panel name — both server and client use it to know
    // which panel definition to build from.
    private static ExtendedScreenHandlerType<MKMenu, String> mkMenuType;

    // ── Initialization ─────────────────────────────────────────────────────

    @Override
    public void onInitialize() {
        init();
    }

    /**
     * Registers the shared MKMenu type. Must be called during mod
     * initialization (onInitialize), BEFORE any panel declarations.
     * Can also be called directly by consuming mods if needed for ordering.
     */
    public static void init() {
        // Register the generic MKMenu type with Fabric
        mkMenuType = new ExtendedScreenHandlerType<>(
                (syncId, inv, panelName) -> new MKMenu(syncId, inv, panelName),
                ByteBufCodecs.STRING_UTF8
        );
        Registry.register(BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath("menukit", "mk_menu"),
                mkMenuType);

        // Register block break listener for instance-bound container cleanup
        MKBlockBreakListener.register();

        // Register vanilla inventory wrapper panels — these panels have zero
        // slot defs (the slots are wrapped from vanilla, not created by MK).
        // They exist purely for shift-click routing and feature association.
        registerVanillaInventoryPanels();

        LOGGER.info("[MenuKit] Registered MK_MENU_TYPE + vanilla inventory panels");
    }

    /**
     * Registers the MKScreen factory for the client side.
     * Must be called during client initialization (onInitializeClient).
     */
    public static void initClient() {
        MenuScreens.register(mkMenuType, MKScreen::new);
        LOGGER.info("[MenuKit] Registered MKScreen factory");
    }

    /** Returns the shared MKMenu type. Used by MKMenu's constructor. */
    public static ExtendedScreenHandlerType<MKMenu, String> getMKMenuType() {
        return mkMenuType;
    }

    // ── Family API ──────────────────────────────────────────────────────────

    /**
     * Returns the family with the given ID, creating it if it doesn't exist.
     * Multiple mods calling {@code family("trevmods")} get the same instance.
     * Each mod can then set the display name, description, and add config
     * categories — they accumulate additively.
     *
     * @param id unique family identifier (e.g., "trevmods")
     */
    public static MKFamily family(String id) {
        return families.computeIfAbsent(id, MKFamily::new);
    }

    /** Returns the family with the given ID, or null if not registered. */
    public static @Nullable MKFamily getFamily(String id) {
        return families.get(id);
    }

    /** Returns all registered families. */
    public static Collection<MKFamily> getFamilies() {
        return Collections.unmodifiableCollection(families.values());
    }

    // ── Registration ────────────────────────────────────────────────────────

    /**
     * Registers a panel definition. Called by {@link MKPanel.Builder#build()}.
     * Must be called during mod initialization (before any menus are created).
     */
    static void register(MKPanelDef def) {
        if (panels.containsKey(def.name())) {
            LOGGER.warn("[MenuKit] Panel '{}' registered twice — overwriting", def.name());
        }
        panels.put(def.name(), def);
        LOGGER.info("[MenuKit] Registered panel '{}' → {} slots, {} buttons, {} texts, rootGroup={}{}",
                def.name(), def.slotDefs().size(), def.buttonDefs().size(),
                def.textDefs().size(), def.rootGroup() != null,
                def.isStandaloneScreen() ? " (standalone screen)" : "");
    }

    /**
     * Returns true if a panel with this name is already registered.
     * Used by {@link MKFamily#sharedPanel} for deduplication.
     */
    public static boolean hasPanel(String name) {
        return panels.containsKey(name);
    }

    // ── Vanilla Inventory Wrapper Panels ────────────────────────────────────

    /** Panel names for vanilla inventory slot groups. */
    public static final String PANEL_HOTBAR = "mk:hotbar";
    public static final String PANEL_MAIN_INVENTORY = "mk:main_inventory";
    public static final String PANEL_ARMOR = "mk:armor";
    public static final String PANEL_OFFHAND = "mk:offhand";
    public static final String PANEL_CRAFT_2X2 = "mk:craft_2x2";
    public static final String PANEL_CRAFT_RESULT = "mk:craft_result";

    /**
     * Registers invisible panels for vanilla inventory slot groups.
     * These panels have zero slot defs — slots are wrapped from vanilla
     * via {@link MKSlotWrapper}, not created by MenuKit's panel builder.
     *
     * <p>Panels exist purely for shift-click routing flags and feature
     * association (sort, lock, etc.). They don't render anything.
     */
    private static void registerVanillaInventoryPanels() {
        // Hotbar — always shift-clickable in both directions
        MKPanel.builder(PANEL_HOTBAR)
                .showIn(MKContext.ALL_WITH_PLAYER_INVENTORY)
                .style(MKPanel.Style.NONE)
                .pos(-9999, -9999)  // off-screen — no visual rendering
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // Main inventory — always shift-clickable in both directions
        MKPanel.builder(PANEL_MAIN_INVENTORY)
                .showIn(MKContext.ALL_WITH_PLAYER_INVENTORY)
                .style(MKPanel.Style.NONE)
                .pos(-9999, -9999)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // Armor — shift-clickable (vanilla armor shift-click behavior)
        MKPanel.builder(PANEL_ARMOR)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(-9999, -9999)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // Offhand — shift-clickable
        MKPanel.builder(PANEL_OFFHAND)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(-9999, -9999)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // 2x2 crafting grid — transient workspace, shift-clickable
        MKPanel.builder(PANEL_CRAFT_2X2)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(-9999, -9999)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // Crafting result — output only (can take out, can't put in)
        MKPanel.builder(PANEL_CRAFT_RESULT)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(-9999, -9999)
                .shiftClickIn(false)
                .shiftClickOut(true)
                .column().build();
    }

    // ── Container Registration ─────────────────────────────────────────────

    /**
     * Creates a container builder. Containers must be registered BEFORE panels
     * that reference them.
     *
     * <p>Usage:
     * <pre>{@code
     * MenuKit.container("equipment").playerBound().size(2).register();
     * }</pre>
     */
    public static MKContainerDef.Builder container(String name) {
        return new MKContainerDef.Builder(name);
    }

    /**
     * Registers a container definition. Called by {@link MKContainerDef.Builder#register()}.
     */
    static void registerContainer(MKContainerDef def) {
        if (containerDefs.containsKey(def.name())) {
            LOGGER.warn("[MenuKit] Container '{}' registered twice — overwriting", def.name());
        }
        containerDefs.put(def.name(), def);
        LOGGER.info("[MenuKit] Registered container '{}' → {} ({} slots)",
                def.name(), def.binding(), def.size());
    }

    /** Returns a container definition by name, or null if not found. */
    public static @Nullable MKContainerDef getContainerDef(String name) {
        return containerDefs.get(name);
    }

    // ── Standalone Persistence API ──────────────────────────────────────────

    /**
     * Registers a standalone persistence handler — save/load callbacks that
     * hook into the same player NBT lifecycle as panel onSave/onLoad, but
     * without requiring a panel.
     *
     * <p>Use this when a feature needs to persist data (e.g., disabled-slot
     * state, preferences, counters) but has no panel of its own. The key
     * becomes the NBT tag name within the player's MenuKit data.
     *
     * <p>Example:
     * <pre>{@code
     * MenuKit.registerPersistence("pocket_disabled",
     *     output -> {
     *         output.putString("slot_0", "0,2");
     *     },
     *     input -> {
     *         input.getString("slot_0").ifPresent(str -> ...);
     *     }
     * );
     * }</pre>
     *
     * @param key  unique identifier for this data block (used as NBT tag name)
     * @param save receives a ValueOutput to write data into during player save
     * @param load receives a ValueInput to read data from during player load
     */
    public static void registerPersistence(String key, Consumer<ValueOutput> save, Consumer<ValueInput> load) {
        if (persistenceHandlers.containsKey(key)) {
            LOGGER.warn("[MenuKit] Persistence handler '{}' registered twice — overwriting", key);
        }
        persistenceHandlers.put(key, new PersistenceHandler(save, load));
        LOGGER.info("[MenuKit] Registered persistence handler '{}'", key);
    }

    // ── Panel Queries ──────────────────────────────────────────────────────

    /** Returns a panel definition by name, or null if not found. */
    public static MKPanelDef getPanelDef(String name) {
        return panels.get(name);
    }

    // ── Panel Visibility ───────────────────────────────────────────────────

    /** Shows a hidden panel by name. Fires a PANEL_SHOW event. */
    public static void showPanel(String name) {
        hiddenPanels.remove(name);
        // Fire panel visibility event
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            MKContext ctx = resolveCurrentContext();
            MKEventBus.fire(MKUIEvent.panelShow(name, ctx, mc.player));
        }
    }

    /** Hides a panel by name. Hidden panels' slots become inactive and
     *  their backgrounds and buttons are not rendered. Fires a PANEL_HIDE event. */
    public static void hidePanel(String name) {
        hiddenPanels.add(name);
        // Fire panel visibility event
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            MKContext ctx = resolveCurrentContext();
            MKEventBus.fire(MKUIEvent.panelHide(name, ctx, mc.player));
        }
    }

    /**
     * Resets all panels that were registered with {@code .hidden()} back to
     * their hidden state. Called on screen init so toggle-revealed panels
     * don't persist across screen open/close cycles.
     */
    public static void resetStartHiddenPanels() {
        for (MKPanelDef def : panels.values()) {
            if (def.startHidden()) {
                hiddenPanels.add(def.name());
            }
        }
    }

    /** Toggles a panel's visibility. Delegates to show/hide, which fire events. */
    public static void togglePanel(String name) {
        if (hiddenPanels.contains(name)) {
            showPanel(name);
        } else {
            hidePanel(name);
        }
    }

    /** Returns true if the named panel is currently hidden (via imperative toggle). */
    public static boolean isPanelHidden(String name) {
        return hiddenPanels.contains(name);
    }

    /**
     * Returns true if the named panel's {@code disabledWhen} predicate is set
     * and currently returns true. This is the declarative, config-driven mechanism.
     */
    public static boolean isPanelDisabled(String name) {
        MKPanelDef def = panels.get(name);
        return def != null && def.disabledWhen() != null && def.disabledWhen().getAsBoolean();
    }

    // ── Element Visibility API ────────────────────────────────────────────────

    /**
     * Sets an element's visibility within a panel by its element ID.
     * Fires an ELEMENT_SHOW or ELEMENT_HIDE event.
     *
     * @param panelName the panel the element belongs to
     * @param elementId the element's ID (set via {@code .id("name")} in the builder)
     * @param visible   true to show, false to hide
     */
    public static void setElementVisible(String panelName, String elementId, boolean visible) {
        MKPanelStateRegistry.getOrCreate(panelName).setVisible(elementId, visible);
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            MKContext ctx = resolveCurrentContext();
            if (visible) {
                MKEventBus.fire(MKUIEvent.elementShow(panelName, elementId, ctx, mc.player));
            } else {
                MKEventBus.fire(MKUIEvent.elementHide(panelName, elementId, ctx, mc.player));
            }
        }
    }

    /**
     * Clears a visibility override for an element, reverting to its
     * {@code disabledWhen} predicate behavior.
     *
     * @param panelName the panel the element belongs to
     * @param elementId the element's ID
     */
    public static void clearElementOverride(String panelName, String elementId) {
        MKPanelState state = MKPanelStateRegistry.get(panelName);
        if (state != null) state.clearOverride(elementId);
    }

    /**
     * Returns whether an element is currently visible. Checks panel state
     * overrides first, then returns true by default (visible if no override).
     *
     * @param panelName the panel the element belongs to
     * @param elementId the element's ID
     * @return true if visible
     */
    public static boolean isElementVisible(String panelName, String elementId) {
        MKPanelState state = MKPanelStateRegistry.get(panelName);
        if (state != null) {
            Boolean override = state.getVisible(elementId);
            if (override != null) return override;
        }
        return true; // visible by default
    }

    /**
     * Resolves the current MKContext from the active screen.
     * Returns null if no container screen is open.
     */
    private static @Nullable MKContext resolveCurrentContext() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs) {
            return MKContext.fromScreen(acs);
        }
        return null;
    }

    /**
     * Returns true if the named panel should NOT be shown — either because it's
     * hidden (imperative) or disabled (predicate). Use this for all visibility checks.
     */
    public static boolean isPanelInactive(String name) {
        return isPanelHidden(name) || isPanelDisabled(name);
    }

    /**
     * Returns true if the named panel allows items to be shift-clicked INTO it.
     * Unknown panels (no MKPanelDef) return true — they represent external vanilla
     * containers (chests, crafting tables, etc.) that should preserve vanilla behavior
     * by default. Only panels with explicit MKPanelDef registrations can restrict
     * shift-click behavior.
     */
    public static boolean isShiftClickIn(String name) {
        MKPanelDef def = panels.get(name);
        if (def == null) return true; // unknown panel = vanilla behavior (open by default)
        if (isPanelInactive(name)) return false;
        return def.shiftClickIn();
    }

    /**
     * Returns true if the named panel allows items to be shift-clicked OUT OF it.
     * Unknown panels (no MKPanelDef) return true — they represent external vanilla
     * containers (chests, crafting tables, etc.) that should preserve vanilla behavior
     * by default. Only panels with explicit MKPanelDef registrations can restrict
     * shift-click behavior.
     */
    public static boolean isShiftClickOut(String name) {
        MKPanelDef def = panels.get(name);
        if (def == null) return true; // unknown panel = vanilla behavior (open by default)
        if (isPanelInactive(name)) return false;
        return def.shiftClickOut();
    }

    // ── Vanilla Slot Panel Mapping ──────────────────────────────────────────

    /**
     * Registers a range of vanilla slot indices as belonging to an MK panel.
     * Called during menu construction by the mixins. Does NOT modify the
     * vanilla slots — just records the association for shift-click routing.
     */
    public static void registerSlotPanelMapping(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                 int startInclusive, int endExclusive,
                                                 String panelName) {
        int menuId = System.identityHashCode(menu);
        Map<Integer, String> map = menuSlotPanelMaps.computeIfAbsent(menuId, k -> new HashMap<>());
        for (int i = startInclusive; i < endExclusive; i++) {
            map.put(i, panelName);
        }
    }

    /**
     * Returns the panel name for a vanilla slot at the given index in the menu,
     * or null if the slot has no panel association (not mapped).
     */
    public static @Nullable String getSlotPanelName(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                      int slotIndex) {
        int menuId = System.identityHashCode(menu);
        Map<Integer, String> map = menuSlotPanelMaps.get(menuId);
        if (map == null) return null;
        return map.get(slotIndex);
    }

    /**
     * Returns the panel name for a slot — checks MKSlotState first (unified registry),
     * then falls back to the map-based tracking (for vanilla slots without state).
     */
    public static @Nullable String getEffectivePanelName(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                           net.minecraft.world.inventory.Slot slot) {
        MKSlotState state = MKSlotStateRegistry.get(slot);
        if (state != null && state.getPanelName() != null) {
            return state.getPanelName();
        }
        return getSlotPanelName(menu, slot.index);
    }

    /**
     * Checks if a vanilla slot (by index) can receive shift-clicked items.
     * Uses the map-based panel association to look up shift-click flags.
     */
    public static boolean canVanillaSlotShiftClickIn(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                       int slotIndex) {
        String panelName = getSlotPanelName(menu, slotIndex);
        if (panelName == null) return false;  // unmapped vanilla slot — use vanilla logic
        return isShiftClickIn(panelName);
    }

    /**
     * Cleans up slot panel mappings for a menu that's being closed.
     * Should be called when the menu is removed.
     */
    public static void cleanupSlotPanelMapping(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        menuSlotPanelMaps.remove(System.identityHashCode(menu));
    }

    // ── Shift-Click Priority Routes ──────────────────────────────────────────
    // Registered at mod init time. When a player shift-clicks an item, these
    // are checked IN ORDER before the generic tryRouteToCustomSlots logic.
    // If a priority route matches (predicate returns true) AND the target slot
    // is empty + active + accepts the item, the item goes directly there.
    // This bypasses shiftClickIn=false on the target panel — the priority route
    // is an explicit "yes, this item type belongs in this specific slot."

    /**
     * A registered priority route for shift-click. When a shift-clicked item
     * matches the predicate, MenuKit tries to place it in the target slot
     * (identified by panel name + container slot index) before generic routing.
     */
    record ShiftClickPriority(
            Predicate<ItemStack> itemMatcher,
            String targetPanelName,
            int targetContainerSlot
    ) {}

    private static final List<ShiftClickPriority> shiftClickPriorities = new ArrayList<>();

    /**
     * Registers a shift-click priority route. When a player shift-clicks an
     * item that matches {@code itemMatcher}, MenuKit will try to place it in
     * the specified panel's slot BEFORE any generic routing logic runs.
     *
     * <p>This is designed for items that have a "natural home" — like elytras
     * belonging in an equipment slot, or totems belonging in an offhand slot.
     * The priority route bypasses the panel's {@code shiftClickIn} flag, since
     * the registration itself is the explicit opt-in.
     *
     * <p>If the target slot is occupied, disabled, or rejects the item, the
     * priority is skipped and normal routing continues.
     *
     * <p>Priorities are checked in registration order. First match wins.
     *
     * @param itemMatcher        predicate that returns true for items this route handles
     * @param targetPanelName    the panel containing the target slot
     * @param targetContainerSlot the container-relative slot index within that panel
     */
    public static void shiftClickPriority(Predicate<ItemStack> itemMatcher,
                                           String targetPanelName,
                                           int targetContainerSlot) {
        shiftClickPriorities.add(new ShiftClickPriority(itemMatcher, targetPanelName, targetContainerSlot));
    }

    /**
     * Tries to route an item to a priority target slot. Called from the
     * quickMoveStack intercept BEFORE generic routing.
     *
     * <p>Iterates registered priorities in order. For each match:
     * <ol>
     *   <li>Finds the MKSlot in the menu that belongs to the target panel
     *       and has the matching container slot index</li>
     *   <li>Checks that the slot is active, empty, and accepts the item</li>
     *   <li>If all checks pass, moves the item and returns true</li>
     * </ol>
     *
     * @param menu        the container menu
     * @param sourceStack the item being shift-clicked (mutated if moved)
     * @return true if the item was successfully routed to a priority slot
     */
    public static boolean tryPriorityRoute(AbstractContainerMenu menu,
                                            ItemStack sourceStack) {
        if (shiftClickPriorities.isEmpty() || sourceStack.isEmpty()) return false;

        for (ShiftClickPriority priority : shiftClickPriorities) {
            if (!priority.itemMatcher().test(sourceStack)) continue;

            // Find the target MKSlot in this menu that matches the panel + container index
            for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                MKSlotState state = MKSlotStateRegistry.get(slot);
                if (state == null || !state.isMenuKitSlot()) continue;

                // Match by panel name
                String panelName = state.getPanelName();
                if (!priority.targetPanelName().equals(panelName)) continue;

                // Match by container slot index (the slot's position within its container)
                if (slot.getContainerSlot() != priority.targetContainerSlot()) continue;

                // Target found — check if it can accept the item
                if (!slot.isActive()) continue;        // slot is disabled (config toggle)
                if (!slot.mayPlace(sourceStack)) continue; // filter rejects item
                if (slot.hasItem()) continue;           // already occupied

                // All checks pass — move the item into the priority slot
                int toPlace = Math.min(sourceStack.getCount(), slot.getMaxStackSize(sourceStack));
                slot.set(sourceStack.split(toPlace));
                return true;
            }
        }
        return false;
    }

    // ── Shift-Click Routing Helpers ─────────────────────────────────────────

    /**
     * Routes an item from a source slot to slots in other panels with
     * shiftClickIn=true. Unified — handles ALL slots (custom and vanilla)
     * through the MKSlotState registry and panel map.
     *
     * @return true if any items were moved
     */
    public static boolean tryRouteToOtherPanels(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                 net.minecraft.world.inventory.Slot sourceSlot,
                                                 ItemStack sourceStack,
                                                 String sourcePanel) {
        boolean moved = false;

        // Pass 1: Fill partial stacks in slots with shiftClickIn=true
        for (int i = 0; i < menu.slots.size(); i++) {
            if (sourceStack.isEmpty()) break;
            net.minecraft.world.inventory.Slot targetSlot = menu.slots.get(i);
            if (targetSlot == sourceSlot) continue;

            // Get the panel for this slot (from state registry or map)
            String targetPanel = getEffectivePanelName(menu, targetSlot);
            if (targetPanel == null) continue;
            if (sourcePanel != null && sourcePanel.equals(targetPanel)) continue;
            if (!isShiftClickIn(targetPanel)) continue;
            if (!targetSlot.mayPlace(sourceStack)) continue;
            if (!targetSlot.isActive()) continue;

            ItemStack targetItem = targetSlot.getItem();
            if (!targetItem.isEmpty()
                    && ItemStack.isSameItemSameComponents(sourceStack, targetItem)
                    && targetItem.getCount() < targetSlot.getMaxStackSize(sourceStack)) {
                int space = targetSlot.getMaxStackSize(sourceStack) - targetItem.getCount();
                int toAdd = Math.min(sourceStack.getCount(), space);
                sourceStack.shrink(toAdd);
                ItemStack grown = targetItem.copy();
                grown.setCount(targetItem.getCount() + toAdd);
                targetSlot.set(grown);
                moved = true;
            }
        }

        // Pass 2: Place into empty slots with shiftClickIn=true
        for (int i = 0; i < menu.slots.size(); i++) {
            if (sourceStack.isEmpty()) break;
            net.minecraft.world.inventory.Slot targetSlot = menu.slots.get(i);
            if (targetSlot == sourceSlot) continue;

            String targetPanel = getEffectivePanelName(menu, targetSlot);
            if (targetPanel == null) continue;
            if (sourcePanel != null && sourcePanel.equals(targetPanel)) continue;
            if (!isShiftClickIn(targetPanel)) continue;
            if (!targetSlot.mayPlace(sourceStack)) continue;
            if (!targetSlot.isActive()) continue;

            if (targetSlot.getItem().isEmpty()) {
                int toPlace = Math.min(sourceStack.getCount(), targetSlot.getMaxStackSize(sourceStack));
                targetSlot.set(sourceStack.split(toPlace));
                moved = true;
            }
        }

        return moved;
    }

    /**
     * Routes an item from a vanilla slot to MenuKit-managed slots with
     * shiftClickIn=true. Skips vanilla slots (vanilla handles those).
     *
     * @return true if any items were moved
     */
    public static boolean tryRouteToCustomSlots(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                 net.minecraft.world.inventory.Slot sourceSlot,
                                                 ItemStack sourceStack) {
        boolean moved = false;

        // Pass 1: Fill partial stacks in MenuKit-managed slots only
        for (net.minecraft.world.inventory.Slot targetSlot : menu.slots) {
            if (sourceStack.isEmpty()) break;
            MKSlotState state = MKSlotStateRegistry.get(targetSlot);
            if (state == null || !state.isMenuKitSlot()) continue;

            String targetPanel = state.getPanelName();
            if (targetPanel == null || !isShiftClickIn(targetPanel)) continue;
            if (!targetSlot.mayPlace(sourceStack)) continue;
            if (!targetSlot.isActive()) continue;

            ItemStack targetItem = targetSlot.getItem();
            if (!targetItem.isEmpty()
                    && ItemStack.isSameItemSameComponents(sourceStack, targetItem)
                    && targetItem.getCount() < targetSlot.getMaxStackSize(sourceStack)) {
                int space = targetSlot.getMaxStackSize(sourceStack) - targetItem.getCount();
                int toAdd = Math.min(sourceStack.getCount(), space);
                sourceStack.shrink(toAdd);
                ItemStack grown = targetItem.copy();
                grown.setCount(targetItem.getCount() + toAdd);
                targetSlot.set(grown);
                moved = true;
            }
        }

        // Pass 2: Place into empty MenuKit-managed slots
        for (net.minecraft.world.inventory.Slot targetSlot : menu.slots) {
            if (sourceStack.isEmpty()) break;
            MKSlotState state = MKSlotStateRegistry.get(targetSlot);
            if (state == null || !state.isMenuKitSlot()) continue;

            String targetPanel = state.getPanelName();
            if (targetPanel == null || !isShiftClickIn(targetPanel)) continue;
            if (!targetSlot.mayPlace(sourceStack)) continue;
            if (!targetSlot.isActive()) continue;

            if (targetSlot.getItem().isEmpty()) {
                int toPlace = Math.min(sourceStack.getCount(), targetSlot.getMaxStackSize(sourceStack));
                targetSlot.set(sourceStack.split(toPlace));
                moved = true;
            }
        }

        return moved;
    }

    // ── Hover Tracking (client-only) ──────────────────────────────────────

    /**
     * Returns the MKSlot currently under the mouse, or null.
     * Updated each frame by {@link #renderSlotBackgrounds} — our own hover
     * detection that works in all screen contexts (unlike vanilla's hoveredSlot
     * which can miss slots in certain screen subclasses).
     */
    public static net.minecraft.world.inventory.Slot getHoveredMKSlot() { // nullable
        return hoveredMKSlot;
    }

    /**
     * Returns the name of the panel currently under the mouse, or null.
     * Updated each frame by {@link #renderPanelBackgrounds}.
     */
    public static @Nullable String getHoveredPanelName() {
        return hoveredPanelName;
    }

    // ── Open Standalone Screen ─────────────────────────────────────────────

    /**
     * Opens a standalone MKScreen for the given player. The player must be
     * a ServerPlayer (this triggers the server-to-client open packet).
     * On the client side, this sends a command packet to the server.
     *
     * @param player    the player to open the screen for
     * @param panelName the panel name (must be a standalone screen panel)
     */
    public static void openScreen(Player player, String panelName) {
        MKPanelDef def = panels.get(panelName);
        if (def == null || !def.isStandaloneScreen()) {
            LOGGER.warn("[MenuKit] Cannot open screen '{}' — not a standalone screen", panelName);
            return;
        }

        // Capture the current screen for goBack() navigation
        var mc = net.minecraft.client.Minecraft.getInstance();
        previousScreen = mc.screen;

        // Carried items are handled by the server's openMenu() which calls
        // closeContainer() → doCloseContainer() → returns carried items.
        // No client-side cursor clearing needed.

        // Resolve the ServerPlayer — button clicks come from the client thread
        // with a LocalPlayer, but openMenu() requires ServerPlayer
        ServerPlayer sp;
        if (player instanceof ServerPlayer serverPlayer) {
            sp = serverPlayer;
        } else {
            // Client-side player — find the corresponding ServerPlayer
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) {
                LOGGER.warn("[MenuKit] Cannot open screen '{}' — no singleplayer server", panelName);
                return;
            }
            sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp == null) {
                LOGGER.warn("[MenuKit] Cannot open screen '{}' — ServerPlayer not found", panelName);
                return;
            }
        }

        // Must run on server thread
        final ServerPlayer finalSp = sp;
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            // openMenu() skips closeContainer() when containerMenu == inventoryMenu.
            // This means carried items are NOT returned to inventory — they become
            // ghost items on the persistent inventoryMenu. Manually trigger removed()
            // which calls placeItemBackInInventory() to "let go" of cursor items.
            if (finalSp.containerMenu == finalSp.inventoryMenu) {
                finalSp.inventoryMenu.removed(finalSp);
            }

            finalSp.openMenu(new ExtendedScreenHandlerFactory<String>() {
                @Override
                public Component getDisplayName() {
                    return def.title();
                }

                @Override
                public AbstractContainerMenu createMenu(int syncId, Inventory inv, Player p) {
                    return new MKMenu(syncId, inv, panelName);
                }

                @Override
                public String getScreenOpeningData(ServerPlayer serverPlayer) {
                    return panelName;
                }
            });
        });
    }

    // ── Screen Navigation ─────────────────────────────────────────────────

    /**
     * Goes back to the previous screen (captured when openScreen was called).
     * Works for any screen — MenuKit screens, vanilla screens, inventory, creative.
     * Closes the current container first, then reopens whatever was there before.
     */
    public static void goBack() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) { previousScreen = null; return; }

        // Replicate vanilla's container close flow WITHOUT the intermediate
        // setScreen(null) that would briefly go to game world and reset the mouse.
        //
        // Vanilla's flow when pressing Escape on a container screen:
        //   1. onClose() → closeContainer() → sends close packet + setScreen(null)
        //   2. Server receives packet → doCloseContainer() → resets to inventoryMenu
        //
        // Our flow:
        //   1. Send close packet to server (same as closeContainer)
        //   2. Reset client containerMenu to inventoryMenu (same, but skip setScreen(null))
        //   3. Open the target screen directly (same as pressing E for inventory)

        // Step 1: Tell server to close the MKMenu and reset to inventoryMenu
        mc.player.connection.send(
                new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(
                        mc.player.containerMenu.containerId));

        // Step 2: Reset client state — containerMenu back to inventoryMenu
        // (same as clientSideCloseContainer, but without setScreen(null))
        mc.player.containerMenu = mc.player.inventoryMenu;

        // Step 3: Open the previous screen — both sides are now on inventoryMenu (containerId=0)
        if (previousScreen != null) {
            if (previousScreen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
            } else if (previousScreen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) {
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(
                        mc.player, mc.player.connection.enabledFeatures(),
                        mc.player.canUseGameMasterBlocks()));
            } else if (!(previousScreen instanceof AbstractContainerScreen)) {
                mc.setScreen(previousScreen);
            } else {
                mc.player.closeContainer();
            }
        } else {
            mc.player.closeContainer();
        }
        previousScreen = null;
    }

    /**
     * Closes the current screen and returns to the game world.
     */
    public static void closeScreen() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        previousScreen = null;
    }

    // ── Container Resolution ──────────────────────────────────────────────

    /**
     * Resolves (or creates) a container by name. Handles both player-bound
     * and instance-bound containers based on the container's BindingType.
     *
     * <p>Player-bound: keyed by player UUID, stored in playerServerContainers/
     * playerClientContainers.
     *
     * <p>Instance-bound: keyed by block position, stored in world SavedData
     * (server) or created fresh (client, synced via broadcastChanges).
     *
     * @param containerName the container name (as registered with MenuKit)
     * @param playerId      the player's UUID
     * @param isServer      true for server-side, false for client-side
     * @param blockPos      the block position (required for instance-bound, null for player-bound)
     * @param level         the server level (required for instance-bound server-side, null otherwise)
     * @return the MKContainer, created if it didn't exist
     */
    private static MKContainer resolveContainer(String containerName,
                                                 UUID playerId, boolean isServer,
                                                 net.minecraft.core.@Nullable BlockPos blockPos,
                                                 net.minecraft.server.level.@Nullable ServerLevel level) {
        MKContainerDef def = containerDefs.get(containerName);
        if (def == null) {
            LOGGER.warn("[MenuKit] Container '{}' not registered", containerName);
            return new MKContainer(1); // fallback to avoid NPE
        }

        if (def.binding() == MKContainerDef.BindingType.PLAYER
                || def.binding() == MKContainerDef.BindingType.EPHEMERAL) {
            // Player-bound and ephemeral: both stored in player maps.
            // PLAYER containers are persisted to NBT; EPHEMERAL are not
            // (saveAll/loadAll filter by binding type).
            return (isServer ? playerServerContainers : playerClientContainers)
                    .computeIfAbsent(playerId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(containerName, k -> new MKContainer(def.size()));
        } else {
            // Instance-bound: keyed by block position in world SavedData
            if (blockPos == null) {
                LOGGER.warn("[MenuKit] Instance-bound container '{}' requires a BlockPos", containerName);
                return new MKContainer(def.size()); // fallback — orphaned container
            }
            if (isServer && level != null) {
                // Server: use world SavedData for persistent storage
                return MKWorldData.get(level).getOrCreate(containerName, blockPos, def.size());
            } else {
                // Client: create a fresh empty container (synced via broadcastChanges)
                // Use a client-side cache so the same container is reused within a session
                return playerClientContainers
                        .computeIfAbsent(playerId, k -> new LinkedHashMap<>())
                        .computeIfAbsent(containerName + "@" + blockPos.toShortString(),
                                k -> new MKContainer(def.size()));
            }
        }
    }

    /**
     * Returns a container lookup function for use during slot creation.
     * Maps container names to live MKContainer instances for the given player.
     *
     * @param playerId the player's UUID
     * @param isServer true for server-side, false for client-side
     * @param blockPos the block position (for instance-bound containers), or null
     * @param level    the server level (for instance-bound server-side), or null
     */
    private static java.util.function.Function<String, MKContainer> containerLookup(
            UUID playerId, boolean isServer,
            net.minecraft.core.@Nullable BlockPos blockPos,
            net.minecraft.server.level.@Nullable ServerLevel level) {
        return name -> resolveContainer(name, playerId, isServer, blockPos, level);
    }

    /** Convenience overload for player-bound-only contexts (no BlockPos needed). */
    private static java.util.function.Function<String, MKContainer> containerLookup(
            UUID playerId, boolean isServer) {
        return name -> resolveContainer(name, playerId, isServer, null, null);
    }

    // ── Standalone Screen Slot/Button Creation ─────────────────────────────

    /**
     * Creates MKSlots for a standalone screen from the panel definition.
     * Creates or reuses the MKContainer for the player.
     *
     * @param panelName the panel name
     * @param player    the player
     * @return list of MKSlots for the standalone screen
     */
    public static List<MKSlot> createSlotsForStandaloneScreen(String panelName, Player player) {
        List<MKSlot> slots = new ArrayList<>();
        MKPanelDef def = panels.get(panelName);
        if (def == null) return slots;

        boolean isServer = player instanceof net.minecraft.server.level.ServerPlayer;
        var lookup = containerLookup(player.getUUID(), isServer);

        // Create live slots using flow-computed positions
        int[][] flowPos = def.computeFlowPositions();
        for (int i = 0; i < def.slotDefs().size(); i++) {
            MKSlotDef slotDef = def.slotDefs().get(i);
            MKSlot slot = slotDef.createSlotAt(lookup,
                    0, 0, def.effectivePadding(),
                    flowPos[i][0], flowPos[i][1], panelName, player);
            if (slot != null) {
                slot.setSlotIndexInPanel(i);
                slots.add(slot);
            }
        }

        return slots;
    }

    /**
     * Creates MKButtons for a standalone screen from the panel definition.
     *
     * @param def     the panel definition
     * @param leftPos the screen's leftPos offset
     * @param topPos  the screen's topPos offset
     * @return list of MKButtons for the standalone screen
     */
    public static List<MKButton> createButtonsForStandaloneScreen(MKPanelDef def,
                                                                    int leftPos, int topPos) {
        List<MKButton> buttons = new ArrayList<>();
        Map<String, MKButtonGroup> groups = new HashMap<>();

        for (MKButtonDef btnDef : def.buttonDefs()) {
            MKButton btn = btnDef.createButton(
                    0, 0, def.effectivePadding(),
                    leftPos, topPos, groups);
            btn.panelName = def.name();
            // Standalone screens don't have a context, but set the player for events
            btn.eventContext = null;
            btn.eventPlayer = net.minecraft.client.Minecraft.getInstance().player;
            buttons.add(btn);
        }

        return buttons;
    }

    // ── Menu Construction (called by MKMenuMixin, both sides) ───────────────

    /**
     * Creates MKContainers and MKSlots for all panels registered to the given
     * context. Called during menu construction on BOTH client and server.
     * Uses the context's container dimensions to resolve relative positions.
     *
     * @param menu    the menu being constructed
     * @param context the active MKContext (provides menuClass, dimensions, creative flag)
     * @param player  the player who owns this menu
     * @return list of MKSlots to add to the menu
     */
    public static List<MKSlot> createSlotsForMenu(AbstractContainerMenu menu,
                                                   MKContext context,
                                                   Player player) {
        List<MKSlot> slots = new ArrayList<>();
        boolean isServer = player instanceof net.minecraft.server.level.ServerPlayer;

        // Extract BlockPos from the menu for instance-bound container resolution.
        // Block-entity menus: position from the block entity.
        // Stateless menus: position from ContainerLevelAccess.
        net.minecraft.core.@Nullable BlockPos blockPos = MKBlockPosExtractor.fromMenu(menu);
        net.minecraft.server.level.@Nullable ServerLevel level =
                (isServer && player.level() instanceof net.minecraft.server.level.ServerLevel sl) ? sl : null;
        var lookup = containerLookup(player.getUUID(), isServer, blockPos, level);

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.slotDefs().isEmpty()) continue;
            if (def.isStandaloneScreen()) continue;  // standalone screens handle their own slots

            // Visibility: does this panel apply to the active context?
            if (!def.appliesTo(context)) continue;

            // Resolve position for the given context
            int[] pos = def.resolvePosition(context);

            // Create live slots using flow-computed positions
            int[][] flowPos = def.computeFlowPositions();
            for (int i = 0; i < def.slotDefs().size(); i++) {
                MKSlotDef slotDef = def.slotDefs().get(i);
                MKSlot slot = slotDef.createSlotAt(lookup,
                        pos[0], pos[1], def.effectivePadding(),
                        flowPos[i][0], flowPos[i][1], def.name(), player);
                if (slot != null) {
                    slot.setSlotIndexInPanel(i);
                    slots.add(slot);
                }
            }
        }

        return slots;
    }

    // ── Creative Tab Slot Creation ──────────────────────────────────────────

    /**
     * Creates MKSlots for creative mode tabs. Resolves positions using
     * MKContext.CREATIVE_INVENTORY. Reuses existing MKContainers from the
     * player's InventoryMenu — does NOT create new containers.
     *
     * @param player  the player who owns this menu
     * @param context the creative context (CREATIVE_INVENTORY or CREATIVE_TABS)
     * @return list of MKSlots positioned for the creative container
     */
    public static List<MKSlot> createSlotsForCreativeTab(Player player, MKContext context) {
        List<MKSlot> slots = new ArrayList<>();
        // Creative tab is always client-side
        var lookup = containerLookup(player.getUUID(), false);

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.slotDefs().isEmpty()) continue;
            if (def.isStandaloneScreen()) continue;

            // Visibility: does this panel apply to the given creative context?
            if (!def.appliesTo(context)) continue;

            // Resolve position for the creative context
            int[] pos = def.resolvePosition(context);

            // Create live slots using flow-computed positions
            int[][] flowPos = def.computeFlowPositions();
            for (int i = 0; i < def.slotDefs().size(); i++) {
                MKSlotDef slotDef = def.slotDefs().get(i);
                MKSlot slot = slotDef.createSlotAt(lookup,
                        pos[0], pos[1], def.effectivePadding(),
                        flowPos[i][0], flowPos[i][1], def.name(), player);
                if (slot != null) {
                    slot.setSlotIndexInPanel(i);
                    slots.add(slot);
                }
            }
        }

        return slots;
    }

    // ── Creative Tab Switch (called by MKCreativeMixin) ─────────────────────

    /**
     * Handles a creative tab switch. Repositions all MKSlots and recreates
     * buttons for the new context (CREATIVE_INVENTORY or CREATIVE_TABS).
     *
     * <p>This is the central creative-mode handler — called from MKCreativeMixin
     * after vanilla's selectTab completes. It ensures slots, buttons, and panel
     * backgrounds all use the correct positions for the active tab.
     *
     * <p>Panels that apply to CREATIVE_INVENTORY show on the inventory tab.
     * Panels that apply to CREATIVE_TABS show on the item grid tabs.
     * Panels that apply to neither get their slots moved offscreen.
     *
     * @param screen  the creative screen instance
     * @param context CREATIVE_INVENTORY or CREATIVE_TABS
     */
    /**
     * Handles the slot-positioning part of a creative tab switch.
     * Repositions all MKSlots for the given context (CREATIVE_INVENTORY or
     * CREATIVE_TABS). Slots that don't apply to the context are moved offscreen.
     *
     * <p>Button recreation is handled by the caller (MKCreativeMixin) since
     * widget add/remove requires protected Screen methods.
     *
     * @param screen  the creative screen instance
     * @param context CREATIVE_INVENTORY or CREATIVE_TABS
     */
    /**
     * Unified slot repositioning — the SINGLE entry point for all slot position
     * updates, regardless of screen type or context.
     *
     * <p>Uses identity-based matching: each MKSlot knows its panel name and
     * index within that panel. The position map is keyed by "panelName:index",
     * so slots are always matched to the correct positions — even when the
     * set of slots in the menu differs between contexts (e.g., equipment
     * slots present in CREATIVE_INVENTORY but not CREATIVE_TABS).
     *
     * <p>Handles both direct MKSlots (survival/container screens) and
     * SlotWrapper-wrapped MKSlots (creative mode).
     *
     * @param menu    the active container menu
     * @param context the active MKContext
     */
    public static void repositionSlots(AbstractContainerMenu menu, MKContext context) {
        Map<String, int[]> positionMap = getSlotPositionMap(context);

        for (var slot : menu.slots) {
            // Unwrap SlotWrapper to find the underlying MKSlot
            net.minecraft.world.inventory.Slot targetSlot = slot;
            if (slot instanceof com.trevorschoeny.menukit.mixin.SlotWrapperAccessor wrapper) {
                targetSlot = wrapper.menuKit$getTarget();
            }

            MKSlotState slotState = MKSlotStateRegistry.get(targetSlot);
            if (slotState != null && slotState.isMenuKitSlot() && slotState.getPanelName() != null) {
                String key = slotState.getPanelName() + ":" + slotState.getSlotIndexInPanel();
                int[] pos = positionMap.get(key);

                if (pos != null) {
                    ((com.trevorschoeny.menukit.mixin.SlotPositionAccessor) slot)
                            .menuKit$setX(pos[0]);
                    ((com.trevorschoeny.menukit.mixin.SlotPositionAccessor) slot)
                            .menuKit$setY(pos[1]);
                } else {
                    // No position found — move offscreen
                    ((com.trevorschoeny.menukit.mixin.SlotPositionAccessor) slot)
                            .menuKit$setX(-999);
                    ((com.trevorschoeny.menukit.mixin.SlotPositionAccessor) slot)
                            .menuKit$setY(-999);
                }
            }
        }
    }

    /**
     * Called when a creative tab changes. Repositions slots and invalidates
     * caches so collision avoidance recomputes.
     */
    public static void onCreativeTabChanged(
            net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen screen,
            MKContext context) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;

        repositionSlots(screen.getMenu(), context);

        // Invalidate position caches so collision avoidance recomputes
        invalidateResolvedPositions();

        // Force sync
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * Returns a map of slot positions keyed by "panelName:slotIndex".
     * Used by {@link #repositionSlots} for identity-based slot matching
     * instead of fragile sequential matching.
     *
     * <p>Panels that don't apply to the context get offscreen positions (-999, -999).
     *
     * @param context the active MKContext
     * @return map from "panelName:slotIndex" → [x, y]
     */
    public static Map<String, int[]> getSlotPositionMap(MKContext context) {
        Map<String, int[]> map = new LinkedHashMap<>();

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.slotDefs().isEmpty()) continue;
            if (def.isStandaloneScreen()) continue;

            if (!def.appliesTo(context) || isPanelInactive(def.name())) {
                // Panels that don't apply OR are hidden get offscreen positions.
                // Hidden panels (e.g., inactive pockets) must be offscreen to prevent
                // position collisions — multiple pockets share the same layout position
                // but only one is active at a time.
                for (int i = 0; i < def.slotDefs().size(); i++) {
                    // Skip vanilla-backed slots (not MKContainer) — they don't appear
                    // in the menu for non-applicable contexts
                    if (def.slotDefs().get(i).isVanillaSlot()) continue;
                    map.put(def.name() + ":" + i, new int[]{ -999, -999 });
                }
                continue;
            }

            int[] pos = getResolvedPosition(def, context);
            int[][] flowPos = def.computeFlowPositions();
            for (int i = 0; i < def.slotDefs().size(); i++) {
                map.put(def.name() + ":" + i, new int[]{
                        pos[0] + def.effectivePadding() + flowPos[i][0],
                        pos[1] + def.effectivePadding() + flowPos[i][1]
                });
            }
        }

        return map;
    }

    // ── Screen Init (called by MKScreenMixin, client only) ──────────────────

    /**
     * Creates MKButtons for all panels registered to the given context.
     * Called during screen initialization on the CLIENT only.
     *
     * @param context  the active MKContext
     * @param leftPos  the screen's leftPos offset
     * @param topPos   the screen's topPos offset
     * @return list of MKButtons to add to the screen as widgets
     */
    public static List<MKButton> createButtonsForMenu(MKContext context,
                                                       int leftPos, int topPos) {
        List<MKButton> buttons = new ArrayList<>();

        // Fresh group registry for this screen session
        activeGroups.clear();
        livePanelSizes.clear();
        invalidateResolvedPositions();

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;  // standalone screens handle their own buttons

            // Visibility: does this panel apply to the active context?
            if (!def.appliesTo(context)) continue;

            // Resolve position for the given context
            int[] pos = def.resolvePosition(context);

            // Create buttons from definitions using resolved position
            List<MKButton> panelButtons = new ArrayList<>();
            int[][] btnFlowPos = def.computeFlowPositions();
            for (int bi = 0; bi < def.buttonDefs().size(); bi++) {
                MKButtonDef btnDef = def.buttonDefs().get(bi);
                int fi = def.slotDefs().size() + bi;
                MKButton btn = btnDef.createButtonAt(
                        pos[0], pos[1], def.effectivePadding(),
                        leftPos, topPos,
                        btnFlowPos[fi][0], btnFlowPos[fi][1],
                        activeGroups);
                btn.panelName = def.name();
                // Set event context for bus dispatch
                btn.eventContext = context;
                btn.eventPlayer = net.minecraft.client.Minecraft.getInstance().player;
                panelButtons.add(btn);
                buttons.add(btn);

                // Hide buttons belonging to hidden/disabled panels or disabled by flow
                if (isPanelInactive(def.name()) || btnFlowPos[fi][0] == -9999) {
                    btn.visible = false;
                }
            }

            // Compute live panel size from BOTH slots and buttons.
            // Panel background now renders before widgets in the pipeline
            // (at RETURN of renderBackground), so buttons are inside the panel.
            if (def.autoSize()) {
                int maxRight = 0;
                int maxBottom = 0;
                int[][] flowPos = def.computeFlowPositions();

                // Slots are 18×18 — use flow positions
                for (int si = 0; si < def.slotDefs().size(); si++) {
                    if (flowPos[si][0] == -9999) continue;
                    maxRight = Math.max(maxRight, flowPos[si][0] + 18);
                    maxBottom = Math.max(maxBottom, flowPos[si][1] + 18);
                }

                // Include buttons (use live widget dimensions + flow positions)
                for (int bi = 0; bi < panelButtons.size() && bi < def.buttonDefs().size(); bi++) {
                    MKButton btn = panelButtons.get(bi);
                    if (!btn.visible) continue;
                    int fi = def.slotDefs().size() + bi;
                    if (flowPos[fi][0] == -9999) continue;
                    maxRight = Math.max(maxRight, flowPos[fi][0] + btn.getWidth());
                    maxBottom = Math.max(maxBottom, flowPos[fi][1] + btn.getHeight());
                }

                // Include text elements
                for (int ti = 0; ti < def.textDefs().size(); ti++) {
                    int fi = def.slotDefs().size() + def.buttonDefs().size() + ti;
                    if (fi >= flowPos.length || flowPos[fi][0] == -9999) continue;
                    MKTextDef textDef = def.textDefs().get(ti);
                    maxRight = Math.max(maxRight, flowPos[fi][0] + textDef.estimateWidth());
                    maxBottom = Math.max(maxBottom, flowPos[fi][1] + MKTextDef.TEXT_HEIGHT);
                }

                livePanelSizes.put(def.name(), new int[]{
                        maxRight + def.effectivePadding() * 2,
                        maxBottom + def.effectivePadding() * 2
                });
            }
        }

        return buttons;
    }

    // ── Panel Rendering (called by MKScreenMixin, client only) ──────────────

    /**
     * Renders ONLY the panel background rectangles for all panels registered
     * to the given context. Called at RETURN of renderBackground (screen space),
     * so the panel appears AFTER the inventory texture but BEFORE widgets/buttons.
     *
     * <p>Also updates {@link #hoveredPanelName} — tracks which panel the mouse
     * is currently over. Uses screen-space mouse coordinates.
     *
     * @param graphics the graphics context
     * @param context  the active MKContext
     * @param offsetX  x offset (leftPos in screen space)
     * @param offsetY  y offset (topPos in screen space)
     * @param mouseX   raw screen-space mouse X
     * @param mouseY   raw screen-space mouse Y
     */
    public static void renderPanelBackgrounds(GuiGraphics graphics, MKContext context,
                                               int offsetX, int offsetY,
                                               int mouseX, int mouseY) {
        // Reset panel hover tracking — will be set if mouse is over a panel
        hoveredPanelName = null;

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;

            // Use collision-avoided position
            int[] pos = getResolvedPosition(def, context);

            // Skip region-following panels that have no matching region (-9999 sentinel)
            if (pos[0] == -9999) continue;

            int panelX = offsetX + pos[0];
            int panelY = offsetY + pos[1];
            // Use livePanelSizes when available (has real widget dimensions from screen init),
            // fall back to computeSize() (uses estimated button sizes but handles disabledWhen)
            int[] size = livePanelSizes.containsKey(def.name())
                    ? livePanelSizes.get(def.name())
                    : def.computeSize();

            // Don't render the panel if all elements are disabled (size is 0×0)
            if (size[0] <= 0 || size[1] <= 0) continue;

            MKPanel.renderPanel(graphics, panelX, panelY,
                    size[0], size[1], def.style(), def.customSprite());

            // Render text labels
            if (!def.textDefs().isEmpty()) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                int[][] flowPos = def.computeFlowPositions();
                int ep = def.effectivePadding();
                for (int i = 0; i < def.textDefs().size(); i++) {
                    int fi = def.slotDefs().size() + def.buttonDefs().size() + i;
                    if (fi >= flowPos.length || flowPos[fi][0] == -9999) continue;
                    MKTextDef textDef = def.textDefs().get(i);
                    net.minecraft.network.chat.Component text = textDef.content().get();
                    if (text != null) {
                        int textX = panelX + ep + flowPos[fi][0];
                        int textY = panelY + ep + flowPos[fi][1];
                        graphics.drawString(mc.font, text, textX, textY,
                                textDef.color(), textDef.shadow());
                    }
                }
            }

            // Track panel hover — is the mouse within this panel's bounds?
            if (mouseX >= panelX && mouseX < panelX + size[0]
                    && mouseY >= panelY && mouseY < panelY + size[1]) {
                hoveredPanelName = def.name();
            }
        }
    }

    /**
     * Renders ONLY the slot background insets (18×18 squares) for all panels
     * registered to the given context. Called at HEAD of renderSlots
     * (container-translated space), so slot backgrounds appear AFTER the panel
     * background and widgets, but BEFORE slot items.
     *
     * <p>Also updates {@link #hoveredMKSlot} — MenuKit's own hover detection
     * that works reliably in ALL screen contexts. The same bounds check that
     * draws the white hover highlight also records which MKSlot is under the
     * cursor. This replaces dependence on vanilla's {@code hoveredSlot} field
     * for MKSlot interactions (tooltips, empty-click callbacks).
     *
     * @param graphics   the graphics context
     * @param menu       the active menu
     * @param context    the active MKContext
     * @param offsetX    x offset (0 in translated space)
     * @param offsetY    y offset (0 in translated space)
     * @param relMouseX  mouse X relative to leftPos
     * @param relMouseY  mouse Y relative to topPos
     */
    public static void renderSlotBackgrounds(GuiGraphics graphics,
                                              AbstractContainerMenu menu,
                                              MKContext context,
                                              int offsetX, int offsetY,
                                              int relMouseX, int relMouseY) {
        // Reset hover tracking — will be set if mouse is over an MKSlot
        hoveredMKSlot = null;

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;

            // Visibility: does this panel apply to the active context?
            if (!def.appliesTo(context)) continue;

            // Skip hidden panels
            if (isPanelInactive(def.name())) continue;

            // Use collision-avoided position
            int[] pos = getResolvedPosition(def, context);
            int panelX = offsetX + pos[0];
            int panelY = offsetY + pos[1];

            // Render slot backgrounds (18×18 inset squares) using flow positions
            int[][] flowPos = def.computeFlowPositions();
            for (int slotIdx = 0; slotIdx < def.slotDefs().size(); slotIdx++) {
                MKSlotDef slotDef = def.slotDefs().get(slotIdx);
                // Skip disabled slots (flow positions will be -9999 for these)
                if (flowPos[slotIdx][0] == -9999) continue;

                // Slot.x/y is where the 16×16 item renders.
                // The 18×18 inset background goes 1px outside that.
                // Slot content offset (+1) is already baked into flowPos by the layout engine
                int slotX = panelX + def.effectivePadding() + flowPos[slotIdx][0];
                int slotY = panelY + def.effectivePadding() + flowPos[slotIdx][1];
                MKPanel.renderSlotBackground(graphics, slotX - 1, slotY - 1);

                // Find the live MKSlot from the menu by matching position.
                // Unwrap SlotWrapper (used in creative mode) to reach the real MKSlot.
                net.minecraft.world.inventory.Slot liveMKSlot = findLiveMKSlot(menu, slotX, slotY);

                // Render hover highlight (bright white overlay) when mouse is over this slot.
                // Vanilla's highlight sprites don't render well on MKSlots outside the
                // container, so we draw our own to match vanilla's visual brightness.
                boolean isHovered = relMouseX >= slotX - 1 && relMouseX < slotX + 17
                        && relMouseY >= slotY - 1 && relMouseY < slotY + 17;
                if (isHovered) {
                    graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0x66FFFFFF);
                    // Track the hovered slot for tooltips and empty-click callbacks
                    if (liveMKSlot != null && liveMKSlot.isActive()) {
                        hoveredMKSlot = liveMKSlot;
                    }
                }

                // Render ghost icon only if the slot is empty
                if (slotDef.ghostIcon() != null) {
                    boolean slotEmpty = liveMKSlot == null || !liveMKSlot.hasItem();
                    if (slotEmpty) {
                        Identifier icon = slotDef.ghostIcon().get();
                        if (icon != null) {
                            MKPanel.renderGhostIcon(graphics, icon.getPath(), slotX, slotY);
                        }
                    }
                }

                // ── Background Tint (renders BEHIND the item) ──────────────
                // Read from MKSlotState so both declarative (builder) and
                // imperative (runtime) tints are respected.
                if (liveMKSlot != null) {
                    MKSlotState dState = MKSlotStateRegistry.get(liveMKSlot);
                    if (dState != null && dState.getBackgroundTint() != 0) {
                        // Fill the 16×16 item area with the tint color.
                        // Alpha channel controls transparency — 0x40 = 25%.
                        graphics.fill(slotX, slotY, slotX + 16, slotY + 16,
                                dState.getBackgroundTint());
                    }

                    // ── Lock Tint (dark overlay on locked slots) ─────────────
                    // When a slot is locked (Ctrl+click), draw a semi-transparent
                    // dark overlay so the player can visually identify which slots
                    // are pinned. 0x40000000 = ~25% opacity black — visible but
                    // doesn't obscure the item icon underneath.
                    if (dState != null && dState.isLocked()) {
                        graphics.fill(slotX, slotY, slotX + 16, slotY + 16,
                                0x40000000);
                    }
                }
            }
        }
    }

    /**
     * Renders overlay icons and border decorations ON TOP of slot items.
     * Called at RETURN of {@code renderSlots} — after vanilla has drawn all
     * slot items — so overlays appear above the items.
     *
     * <p>Only processes slots that have decorations set (overlay icon or border
     * color), gated behind {@link MKSlotState#hasDecoration()} for zero cost
     * on undecorated slots.
     *
     * @param graphics  the current GuiGraphics context
     * @param menu      the container menu with all slots
     * @param context   the active MKContext
     * @param offsetX   x offset (0 in translated space)
     * @param offsetY   y offset (0 in translated space)
     */
    public static void renderSlotOverlays(GuiGraphics graphics,
                                           AbstractContainerMenu menu,
                                           MKContext context,
                                           int offsetX, int offsetY) {
        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;

            int[] pos = getResolvedPosition(def, context);
            int panelX = offsetX + pos[0];
            int panelY = offsetY + pos[1];

            int[][] flowPos = def.computeFlowPositions();
            for (int slotIdx = 0; slotIdx < def.slotDefs().size(); slotIdx++) {
                // Skip disabled slots (flow positions will be -9999 for these)
                if (flowPos[slotIdx][0] == -9999) continue;

                int slotX = panelX + def.effectivePadding() + flowPos[slotIdx][0];
                int slotY = panelY + def.effectivePadding() + flowPos[slotIdx][1];

                // Find the live slot and its state
                net.minecraft.world.inventory.Slot liveMKSlot = findLiveMKSlot(menu, slotX, slotY);
                if (liveMKSlot == null) continue;

                MKSlotState dState = MKSlotStateRegistry.get(liveMKSlot);
                // Fast gate: skip slots with no decorations (most slots)
                if (dState == null || !dState.hasDecoration()) continue;

                // ── Overlay Icon (renders ON TOP of the item) ──────────────
                // Draws a 16×16 sprite at the slot position, above the item.
                // Common use: lock icon, warning indicator, status badge.
                Identifier overlay = dState.getOverlayIcon();
                if (overlay != null) {
                    graphics.blitSprite(
                            net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                            overlay, slotX, slotY, 16, 16);
                }

                // ── Border (renders ON TOP of the item) ────────────────────
                // Four 1px fills forming a rectangle around the 16×16 slot area.
                // The border sits at the slot boundary, inside the 18×18 background.
                int bc = dState.getBorderColor();
                if (bc != 0) {
                    // Top edge
                    graphics.fill(slotX, slotY, slotX + 16, slotY + 1, bc);
                    // Bottom edge
                    graphics.fill(slotX, slotY + 15, slotX + 16, slotY + 16, bc);
                    // Left edge
                    graphics.fill(slotX, slotY + 1, slotX + 1, slotY + 15, bc);
                    // Right edge
                    graphics.fill(slotX + 15, slotY + 1, slotX + 16, slotY + 15, bc);
                }
            }
        }
    }

    /**
     * Finds the live MKSlot in the menu at the given container-relative position.
     * Unwraps SlotWrapper (used by creative mode) to reach the underlying MKSlot.
     *
     * @return the MKSlot at this position, or null if not found
     */
    private static net.minecraft.world.inventory.Slot findLiveMKSlot( // nullable
            AbstractContainerMenu menu, int slotX, int slotY) {
        for (var menuSlot : menu.slots) {
            if (menuSlot.x == slotX && menuSlot.y == slotY) {
                // Unwrap SlotWrapper if needed (creative mode wraps slots)
                net.minecraft.world.inventory.Slot target = menuSlot;
                if (menuSlot instanceof com.trevorschoeny.menukit.mixin.SlotWrapperAccessor wrapper) {
                    target = wrapper.menuKit$getTarget();
                }
                MKSlotState state = MKSlotStateRegistry.get(target);
                if (state != null && state.isMenuKitSlot()) {
                    // Skip inactive slots — multiple hidden pockets can share
                    // the same position. We want the ACTIVE one, not the first match.
                    if (!state.isSlotActive()) continue;
                    return target;
                }
            }
        }
        return null;
    }

    // ── Persistence (called by ServerPlayerMixin, server only) ──────────────

    /**
     * Saves all MenuKit containers for the given player to NBT.
     * Called during {@code ServerPlayer.addAdditionalSaveData()}.
     */
    public static void saveAll(UUID playerId, ValueOutput output) {
        Map<String, MKContainer> containers = playerServerContainers.get(playerId);
        if (containers == null) return;

        // Save player-bound containers by container name.
        // EPHEMERAL and INSTANCE containers are intentionally skipped —
        // ephemeral contents live in their bound source, not in player NBT.
        for (MKContainerDef cDef : containerDefs.values()) {
            if (cDef.binding() != MKContainerDef.BindingType.PLAYER) continue;

            MKContainer container = containers.get(cDef.name());
            if (container == null) continue;

            ValueOutput containerOutput = output.child(cDef.name());
            container.saveToNbt("items", containerOutput);
        }

        // Call panel-level custom save hooks (separate from container persistence)
        for (MKPanelDef def : panels.values()) {
            if (def.onSave() != null) {
                def.onSave().accept(output.child("panel_" + def.name()));
            }
        }

        // Call standalone persistence handlers (panel-independent data)
        for (var entry : persistenceHandlers.entrySet()) {
            entry.getValue().save().accept(output.child(entry.getKey()));
        }
    }

    /**
     * Loads all MenuKit containers for the given player from NBT.
     * Called during {@code ServerPlayer.readAdditionalSaveData()}.
     */
    public static void loadAll(UUID playerId, ValueInput input) {
        // Load player-bound containers by container name.
        // EPHEMERAL containers are skipped — their contents come from bound sources, not NBT.
        for (MKContainerDef cDef : containerDefs.values()) {
            if (cDef.binding() != MKContainerDef.BindingType.PLAYER) continue;

            MKContainer container = playerServerContainers
                    .computeIfAbsent(playerId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(cDef.name(), k -> new MKContainer(cDef.size()));

            input.child(cDef.name()).ifPresent(containerInput -> {
                container.loadFromNbt("items", containerInput);
            });
        }

        // Call panel-level custom load hooks
        for (MKPanelDef def : panels.values()) {
            if (def.onLoad() != null) {
                input.child("panel_" + def.name()).ifPresent(panelInput ->
                        def.onLoad().accept(panelInput));
            }
        }

        // Call standalone persistence handlers (panel-independent data)
        for (var entry : persistenceHandlers.entrySet()) {
            input.child(entry.getKey()).ifPresent(handlerInput ->
                    entry.getValue().load().accept(handlerInput));
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    /**
     * Removes all per-player state for the given player.
     * Called when a player disconnects.
     */
    public static void removePlayer(UUID playerId) {
        playerServerContainers.remove(playerId);
        playerClientContainers.remove(playerId);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    /** Returns all registered panel definitions. */
    public static Collection<MKPanelDef> getAllPanels() {
        return Collections.unmodifiableCollection(panels.values());
    }

    /** Returns the SERVER container for a specific player and panel, or null. */
    public static MKContainer getServerContainer(UUID playerId, String panelName) {
        Map<String, MKContainer> containers = playerServerContainers.get(playerId);
        return containers != null ? containers.get(panelName) : null;
    }

    /** Returns the CLIENT container for a specific player and panel, or null. */
    public static MKContainer getClientContainer(UUID playerId, String panelName) {
        Map<String, MKContainer> containers = playerClientContainers.get(playerId);
        return containers != null ? containers.get(panelName) : null;
    }

    /** Returns the container for a player — server or client depending on isServer. */
    public static @Nullable MKContainer getContainerForPlayer(String panelName,
                                                                UUID playerId,
                                                                boolean isServer) {
        Map<String, MKContainer> containers = (isServer ? playerServerContainers : playerClientContainers)
                .get(playerId);
        return containers != null ? containers.get(panelName) : null;
    }

    /** Returns ALL containers for a player (by panel name), or null if none. */
    public static @Nullable Map<String, MKContainer> getAllContainersForPlayer(
            UUID playerId, boolean isServer) {
        return (isServer ? playerServerContainers : playerClientContainers).get(playerId);
    }

    // ── Unified Container API (vanilla + custom containers) ────────────────

    /**
     * Gets a vanilla container wrapper by name from a specific menu instance.
     * Returns null if the container doesn't exist in this menu.
     *
     * <p>Works for any vanilla container: "mk:hotbar", "mk:chest",
     * "mk:furnace_input", "mk:crafting_3x3", etc.
     *
     * @param menu the current menu instance
     * @param name the container name (e.g., "mk:hotbar", "mk:chest")
     * @return the MKContainer wrapping the vanilla slots, or null
     */
    public static @Nullable MKContainer getContainer(AbstractContainerMenu menu, String name) {
        Map<String, MKContainer> map = menuContainerMaps.get(System.identityHashCode(menu));
        return map != null ? map.get(name) : null;
    }

    /**
     * Gets all vanilla container wrappers for a menu instance.
     * Returns an empty map if no containers are mapped.
     */
    public static Map<String, MKContainer> getActiveContainers(AbstractContainerMenu menu) {
        Map<String, MKContainer> map = menuContainerMaps.get(System.identityHashCode(menu));
        return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    /**
     * Gets all containers in a menu that match a specific persistence type.
     */
    public static List<MKContainer> getContainersByPersistence(AbstractContainerMenu menu,
                                                                 MKContainerDef.Persistence persistence) {
        Map<String, MKContainer> map = menuContainerMaps.get(System.identityHashCode(menu));
        if (map == null) return Collections.emptyList();

        List<MKContainer> result = new ArrayList<>();
        for (MKContainer container : map.values()) {
            MKRegion region = container.getRegion();
            if (region != null && region.persistence() == persistence) {
                result.add(container);
            } else if (region == null && persistence == MKContainerDef.Persistence.PERSISTENT) {
                // Containers without a region default to persistent
                result.add(container);
            }
        }
        return result;
    }

    /**
     * Creates vanilla container wrappers for a menu instance based on its context.
     * Called automatically during menu construction by the mixin.
     *
     * @param menu    the menu instance
     * @param context the MKContext for this menu
     * @param player  the player (for player inventory access)
     */
    public static void createVanillaContainerWrappers(AbstractContainerMenu menu,
                                                       @Nullable MKContext context,
                                                       net.minecraft.world.entity.player.Player player) {
        if (context == null) return;

        int menuId = System.identityHashCode(menu);
        Map<String, MKContainer> containers = menuContainerMaps.computeIfAbsent(menuId, k -> new LinkedHashMap<>());

        List<MKContainerMapping.SlotGroup> groups = MKContainerMapping.getSlotGroups(menu, context);
        for (MKContainerMapping.SlotGroup group : groups) {
            MKContainer container;

            // Get the backing vanilla Container and create a region-aware proxy
            net.minecraft.world.Container vanillaContainer;
            int containerStartSlot;

            if (group.name().startsWith("mk:hotbar") || group.name().equals("mk:main_inventory")
                    || group.name().equals("mk:armor") || group.name().equals("mk:offhand")) {
                // Player inventory groups delegate to the player's Inventory object
                vanillaContainer = player.getInventory();
                if (group.name().equals("mk:hotbar")) containerStartSlot = 0;
                else if (group.name().equals("mk:main_inventory")) containerStartSlot = 9;
                else if (group.name().equals("mk:armor")) containerStartSlot = 36;
                else if (group.name().equals("mk:offhand")) containerStartSlot = 40;
                else continue;
            } else {
                // Non-player containers — get the vanilla Container from the first slot
                if (group.menuSlotStart() < menu.slots.size()) {
                    net.minecraft.world.inventory.Slot firstSlot = menu.slots.get(group.menuSlotStart());
                    vanillaContainer = firstSlot.container;
                    containerStartSlot = firstSlot.getContainerSlot();
                } else {
                    continue; // slot doesn't exist yet
                }
            }

            // Create region and proxy
            MKRegion region = new MKRegion(group.name(), vanillaContainer,
                    containerStartSlot, group.size(), group.persistence(),
                    true, true);
            region.setMenuSlotRange(group.menuSlotStart(),
                    group.menuSlotStart() + group.size() - 1);
            container = new MKContainer(vanillaContainer, region);

            containers.put(group.name(), container);

            // Also register the slot→panel mapping for shift-click routing
            int start = group.menuSlotStart();
            int end = Math.min(group.menuSlotEnd() + 1, menu.slots.size()); // +1 for exclusive end
            if (start < end) {
                registerSlotPanelMapping(menu, start, end, group.name());
            }
        }

        LOGGER.debug("[MenuKit] Created {} vanilla container wrappers for {} (context={})",
                containers.size(), menu.getClass().getSimpleName(), context);
    }

    /**
     * Cleans up vanilla container wrappers when a menu is closed.
     */
    public static void cleanupVanillaContainers(AbstractContainerMenu menu) {
        menuContainerMaps.remove(System.identityHashCode(menu));
    }

    // ── Container State (Mixin Layer) ────────────────────────────────────────

    /**
     * Scans all unique Containers in a menu and ensures each has an
     * {@link MKContainerState} in the registry. Called from both
     * MKMenuMixin (InventoryMenu) and MKGenericMenuMixin (other menus).
     *
     * <p>This gives 100% coverage — every Container that appears in any
     * menu gets state attached, regardless of implementation class.
     */
    public static void discoverAndAttachContainerState(AbstractContainerMenu menu) {
        java.util.Set<net.minecraft.world.Container> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (net.minecraft.world.inventory.Slot slot : menu.slots) {
            net.minecraft.world.Container c = slot.container;
            if (c != null && seen.add(c)) {
                MKContainerStateRegistry.getOrCreate(c);
            }
        }
    }

    /**
     * Returns the container names available in a given context (static lookup).
     */
    public static List<String> getContainerNamesForContext(MKContext context) {
        return MKContainerMapping.getContainerNames(context);
    }

    // ── Cross-UI Data Access (for HUD elements reading panel slot data) ────

    /**
     * Returns a {@code Supplier<ItemStack>} that reads from the CLIENT's
     * container for the given panel and slot index. Use this to show a
     * panel's slot item on the HUD or in another screen.
     *
     * <p>Matches vanilla's pattern: the HUD reads from the client's Inventory,
     * which is synced from the server via broadcastChanges. This helper reads
     * from the client's MKContainer the same way.
     *
     * <p>Usage:
     * <pre>{@code
     * MKHudPanel.builder("equip_hud")
     *     .item(0, 0, MenuKit.slotItem("equipment", 0))
     *     .build();
     * }</pre>
     *
     * @param containerName the container name to read from
     * @param slotIndex     the slot index within that container
     * @return a supplier that returns the current ItemStack each frame
     */
    public static java.util.function.Supplier<ItemStack> slotItem(String containerName, int slotIndex) {
        return () -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return ItemStack.EMPTY;
            MKContainer container = getClientContainer(mc.player.getUUID(), containerName);
            if (container == null || slotIndex < 0 || slotIndex >= container.getContainerSize()) {
                return ItemStack.EMPTY;
            }
            return container.getItem(slotIndex);
        };
    }

    /** Returns true if any panels apply to the given context. */
    public static boolean hasPanelsForContext(MKContext context) {
        for (MKPanelDef def : panels.values()) {
            if (!def.isStandaloneScreen() && def.appliesTo(context)) return true;
        }
        return false;
    }

    // ── Collision Avoidance ────────────────────────────────────────────────

    /**
     * Resolves ALL panel positions with collision avoidance applied.
     * Call once per frame before rendering. Results are cached in {@link #resolvedPositions}.
     *
     * <p>Avoidance targets:
     * <ul>
     *   <li>Status effects (right side — posRight panels shift down below effects)</li>
     *   <li>Creative tabs (above/below — posAbove/posBelow panels shift further out)</li>
     *   <li>Other MKPanels (registration order = priority; later panels shift to avoid earlier)</li>
     * </ul>
     *
     * @param context        the active MKContext
     * @param effectsActive  whether status effects are showing on screen
     * @param effectsHeight  total height of the effects panel (0 if not active)
     */
    public static void resolvePositionsWithAvoidance(MKContext context,
                                                      boolean effectsActive, int effectsHeight) {
        int containerWidth = context.containerWidth();
        int containerHeight = context.containerHeight();
        int m = MKPanel.Builder.DEFAULT_MARGIN;

        // Always recompute — disabledWhen predicates can change panel sizes
        // and visibility frame-to-frame, so cached positions may be stale
        resolvedPositions.clear();
        lastResolvedWidth = containerWidth;
        lastResolvedHeight = containerHeight;
        lastResolvedContext = context;

        // ── Exclusive panel pre-pass ─────────────────────────────────────────
        // Check which sides have a visible exclusive panel. Non-exclusive panels
        // on that side will be suppressed (not positioned, not rendered).
        boolean exclusiveLeft = false, exclusiveRight = false;
        boolean exclusiveAboveLeft = false, exclusiveAboveRight = false;
        boolean exclusiveBelowLeft = false, exclusiveBelowRight = false;
        for (MKPanelDef def : panels.values()) {
            if (!def.exclusive() || !def.isAutoStacked()) continue;
            if (def.isRegionFollowing()) continue; // region-following panels don't participate in exclusive stacking
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;
            switch (def.posMode()) {
                case LEFT_AUTO    -> exclusiveLeft = true;
                case RIGHT_AUTO   -> exclusiveRight = true;
                case ABOVE_LEFT   -> exclusiveAboveLeft = true;
                case ABOVE_RIGHT  -> exclusiveAboveRight = true;
                case BELOW_LEFT   -> exclusiveBelowLeft = true;
                case BELOW_RIGHT  -> exclusiveBelowRight = true;
                default -> {}
            }
        }

        // ── Auto-stacking cursors ──────────────────────────────────────────
        // Track the running offset for each auto-stacking lane.
        int leftCursorY = 0;
        int rightCursorY = 0;
        int aboveLeftCursorX = 0;
        int aboveRightCursorX = 0;
        int belowLeftCursorX = 0;
        int belowRightCursorX = 0;

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;

            // Suppress non-exclusive panels when an exclusive panel is active on their side
            if (def.isAutoStacked() && !def.exclusive()) {
                boolean suppressed = switch (def.posMode()) {
                    case LEFT_AUTO    -> exclusiveLeft;
                    case RIGHT_AUTO   -> exclusiveRight;
                    case ABOVE_LEFT   -> exclusiveAboveLeft;
                    case ABOVE_RIGHT  -> exclusiveAboveRight;
                    case BELOW_LEFT   -> exclusiveBelowLeft;
                    case BELOW_RIGHT  -> exclusiveBelowRight;
                    default -> false;
                };
                if (suppressed) {
                    // Position off-screen so it doesn't render or accept interactions
                    resolvedPositions.put(def.name(), new int[]{ -9999, -9999 });
                    continue;
                }
            }

            int[] size = def.computeSize();
            int pw = size[0], ph = size[1];
            int px, py;

            if (def.isAutoStacked()) {
                // ── Auto-stacking: compute position from cursor ────────────
                switch (def.posMode()) {
                    case LEFT_AUTO -> {
                        px = -m - pw;
                        py = leftCursorY;
                        leftCursorY += ph + m; // advance cursor for next panel
                    }
                    case RIGHT_AUTO -> {
                        px = containerWidth + m;
                        py = rightCursorY;
                        rightCursorY += ph + m;
                    }
                    case ABOVE_LEFT -> {
                        px = aboveLeftCursorX;
                        py = -m - ph;
                        aboveLeftCursorX += pw + m;
                    }
                    case ABOVE_RIGHT -> {
                        px = containerWidth - aboveRightCursorX - pw;
                        py = -m - ph;
                        aboveRightCursorX += pw + m;
                    }
                    case BELOW_LEFT -> {
                        px = belowLeftCursorX;
                        py = containerHeight + m;
                        belowLeftCursorX += pw + m;
                    }
                    case BELOW_RIGHT -> {
                        px = containerWidth - belowRightCursorX - pw;
                        py = containerHeight + m;
                        belowRightCursorX += pw + m;
                    }
                    default -> {
                        // shouldn't happen — isAutoStacked() would be false
                        int[] pos = def.resolvePosition(context);
                        px = pos[0]; py = pos[1];
                    }
                }
            } else if (def.isRegionFollowing()) {
                // ── Region-following positioning ────────────────────────────
                // Compute the panel's position from the target region's bounding box.
                // If the region doesn't exist in the current menu, hide the panel.
                net.minecraft.world.inventory.AbstractContainerMenu activeMenu = null;
                var mcRF = net.minecraft.client.Minecraft.getInstance();
                if (mcRF != null && mcRF.player != null) {
                    activeMenu = mcRF.player.containerMenu;
                }
                if (activeMenu == null) {
                    resolvedPositions.put(def.name(), new int[]{ -9999, -9999 });
                    continue;
                }
                MKRegionFollowDef rfd = def.followsRegion();
                MKRegion followedRegion = MKRegionRegistry.getRegion(activeMenu, rfd.regionName());
                if (followedRegion == null || followedRegion.getMenuSlotStart() < 0) {
                    // Region not in this menu — hide panel
                    resolvedPositions.put(def.name(), new int[]{ -9999, -9999 });
                    continue;
                }
                int[] bbox = computeRegionBBox(activeMenu, followedRegion);
                size = def.computeSize(); // reassign outer `size` — no redeclaration needed
                px = computeRegionFollowX(bbox, rfd.direction(), rfd.gap(), size[0]);
                py = computeRegionFollowY(bbox, rfd.direction(), rfd.gap(), size[1]);
            } else {
                // ── Manual positioning ──────────────────────────────────────
                int[] pos = def.resolvePosition(context);
                px = pos[0]; py = pos[1];
            }

            // ── Avoidance ───────────────────────────────────────────────────
            // Auto-stacked panels skip panel-to-panel collision (cursor handles it)
            // but still need to avoid vanilla elements like status effects and tabs.
            if (!def.allowOverlap()) {
                // Avoid status effects (right side)
                boolean isRight = def.posMode() == MKPanelDef.PosMode.RIGHT
                        || def.posMode() == MKPanelDef.PosMode.RIGHT_AUTO;
                if (effectsActive && isRight) {
                    int effectsX = containerWidth + 2;
                    if (px >= effectsX - 4 && py < effectsHeight) {
                        py = effectsHeight + 2;
                    }
                }

                // Avoid creative tabs (applies to all above/below modes)
                if (context.isCreative()) {
                    boolean isAbove = def.posMode() == MKPanelDef.PosMode.ABOVE
                            || def.posMode() == MKPanelDef.PosMode.ABOVE_LEFT
                            || def.posMode() == MKPanelDef.PosMode.ABOVE_RIGHT;
                    boolean isBelow = def.posMode() == MKPanelDef.PosMode.BELOW
                            || def.posMode() == MKPanelDef.PosMode.BELOW_LEFT
                            || def.posMode() == MKPanelDef.PosMode.BELOW_RIGHT;
                    if (isAbove) {
                        int creativeTabHeight = 32;
                        if (py + ph > -creativeTabHeight) {
                            py = -creativeTabHeight - ph - 2;
                        }
                    }
                    if (isBelow) {
                        int creativeTabHeight = 32;
                        if (py < containerHeight + creativeTabHeight) {
                            py = containerHeight + creativeTabHeight + 2;
                        }
                    }
                }

                // Avoid other panels (AABB collision) — manual panels only
                // Auto-stacked panels are already spaced by the cursor system
                if (!def.isAutoStacked()) {
                    for (var entry : resolvedPositions.entrySet()) {
                        MKPanelDef otherDef = panels.get(entry.getKey());
                        if (otherDef == null || otherDef.allowOverlap()) continue;
                        int[] otherPos = entry.getValue();
                        int[] otherSize = livePanelSizes.getOrDefault(otherDef.name(), otherDef.computeSize());
                        int ox = otherPos[0], oy = otherPos[1];
                        int ow = otherSize[0], oh = otherSize[1];
                        if (px < ox + ow && px + pw > ox && py < oy + oh && py + ph > oy) {
                            py = oy + oh + 2;
                        }
                    }
                }
            }

            resolvedPositions.put(def.name(), new int[]{px, py});
        }
    }

    /**
     * Gets the resolved (collision-avoided) position for a panel.
     * Falls back to basic resolution if avoidance hasn't run yet.
     */
    public static int[] getResolvedPosition(MKPanelDef def, MKContext context) {
        int[] resolved = resolvedPositions.get(def.name());
        if (resolved != null) return resolved;
        return def.resolvePosition(context);
    }

    /** Invalidates the resolved positions cache (call when screen changes). */
    public static void invalidateResolvedPositions() {
        resolvedPositions.clear();
        livePanelSizes.clear();
        lastResolvedWidth = -1;
    }

    // ── Region-Following Positioning Helpers ─────────────────────────────────

    /**
     * Computes the bounding box of a region's slots in container-relative
     * coordinates. Returns {minX, minY, maxX, maxY} where max values include
     * the 16×16 item area (slot.x + 16, slot.y + 16).
     */
    private static int[] computeRegionBBox(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                             MKRegion region) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int start = region.getMenuSlotStart();
        int end   = region.getMenuSlotEnd();
        for (int i = start; i <= end && i < menu.slots.size(); i++) {
            net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
            minX = Math.min(minX, slot.x);
            minY = Math.min(minY, slot.y);
            maxX = Math.max(maxX, slot.x + 16);
            maxY = Math.max(maxY, slot.y + 16);
        }
        if (minX == Integer.MAX_VALUE) return new int[]{ 0, 0, 0, 0 };
        return new int[]{ minX, minY, maxX, maxY };
    }

    /**
     * Computes the X coordinate for a region-following panel.
     * ABOVE/BELOW: center the panel horizontally on the region.
     * LEFT: left edge of panel = region left edge - gap - panelWidth.
     * RIGHT: left edge of panel = region right edge + gap.
     */
    private static int computeRegionFollowX(int[] bbox, MKAnchor direction, int gap, int panelWidth) {
        return switch (direction) {
            case ABOVE, BELOW -> (bbox[0] + bbox[2]) / 2 - panelWidth / 2;
            case LEFT         -> bbox[0] - gap - panelWidth;
            case RIGHT        -> bbox[2] + gap;
        };
    }

    /**
     * Computes the Y coordinate for a region-following panel.
     * ABOVE: bottom edge of panel = region top edge - gap.
     * BELOW: top edge of panel = region bottom edge + gap.
     * LEFT/RIGHT: center the panel vertically on the region.
     */
    private static int computeRegionFollowY(int[] bbox, MKAnchor direction, int gap, int panelHeight) {
        return switch (direction) {
            case ABOVE        -> bbox[1] - gap - panelHeight;
            case BELOW        -> bbox[3] + gap;
            case LEFT, RIGHT  -> (bbox[1] + bbox[3]) / 2 - panelHeight / 2;
        };
    }

    // ── Dynamic Position Updates (slots + buttons follow resolved positions) ──

    /**
     * Updates MKSlot x/y positions in the menu to match collision-avoided panel positions.
     * Uses {@link com.trevorschoeny.menukit.mixin.SlotPositionAccessor} to modify
     * the final Slot.x and Slot.y fields.
     */
    /**
     * Updates slot positions for the given menu and context.
     * Delegates to the unified {@link #repositionSlots} method.
     *
     * <p>Called every frame by {@code MKScreenMixin} to apply collision
     * avoidance position updates.
     */
    public static void updateSlotPositions(AbstractContainerMenu menu, MKContext context) {
        repositionSlots(menu, context);
    }

    /**
     * Updates MKButton widget positions to match collision-avoided panel positions.
     * Buttons are Screen widgets — their position is in screen space (leftPos + panelX).
     */
    public static void updateButtonPositions(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen,
                                              MKContext context, int leftPos, int topPos) {
        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;

            // If panel is inactive, hide all its buttons and skip position updates
            if (isPanelInactive(def.name())) {
                for (var child : screen.children()) {
                    if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                            && mkBtn.panelName.equals(def.name())) {
                        mkBtn.visible = false;
                    }
                }
                continue;
            }

            int[] pos = getResolvedPosition(def, context);

            // Region-following panels that have no matching region get -9999.
            // Hide all buttons when this sentinel is set.
            if (pos[0] == -9999) {
                for (var child : screen.children()) {
                    if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                            && mkBtn.panelName.equals(def.name())) {
                        mkBtn.visible = false;
                    }
                }
                continue;
            }

            int[][] flowPos = def.computeFlowPositions();
            for (int i = 0; i < def.buttonDefs().size(); i++) {
                MKButtonDef btnDef = def.buttonDefs().get(i);
                int fi = def.slotDefs().size() + i; // flow position index (buttons after slots)

                // Check button-level disabledWhen predicate (also captured by flow sentinel)
                boolean btnDisabled = flowPos[fi][0] == -9999;

                // No +1 for buttons (unlike slots — buttons don't have bg overhang)
                int newX = leftPos + pos[0] + def.effectivePadding() + flowPos[fi][0];
                int newY = topPos + pos[1] + def.effectivePadding() + flowPos[fi][1];

                // Find the matching button among screen's renderables
                for (var child : screen.children()) {
                    if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                            && mkBtn.panelName.equals(def.name())) {
                        if (mkBtn.getMessage().getString().equals(
                                btnDef.label() != null ? btnDef.label().getString() : "")) {
                            mkBtn.visible = !btnDisabled;
                            mkBtn.setX(newX);
                            mkBtn.setY(newY);
                        }
                    }
                }
            }

            // Recompute live panel size using real widget dimensions + flow positions
            // This runs every frame, keeping livePanelSizes accurate when disabledWhen changes
            if (def.autoSize()) {
                int maxRight = 0, maxBottom = 0;
                int[][] sizeFlowPos = def.computeFlowPositions();

                // Slot content offset (+1) is already baked into flowPos
                for (int si = 0; si < def.slotDefs().size(); si++) {
                    if (sizeFlowPos[si][0] == -9999) continue;
                    maxRight = Math.max(maxRight, sizeFlowPos[si][0] + 18);
                    maxBottom = Math.max(maxBottom, sizeFlowPos[si][1] + 18);
                }
                // Use actual button widget dimensions
                int btnIdx = 0;
                for (var child : screen.children()) {
                    if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                            && mkBtn.panelName.equals(def.name()) && mkBtn.visible) {
                        if (btnIdx < def.buttonDefs().size()) {
                            int fi = def.slotDefs().size() + btnIdx;
                            if (sizeFlowPos[fi][0] != -9999) {
                                maxRight = Math.max(maxRight, sizeFlowPos[fi][0] + mkBtn.getWidth());
                                maxBottom = Math.max(maxBottom, sizeFlowPos[fi][1] + mkBtn.getHeight());
                            }
                        }
                        btnIdx++;
                    }
                }

                // Include text elements in size computation
                for (int ti = 0; ti < def.textDefs().size(); ti++) {
                    int fi = def.slotDefs().size() + def.buttonDefs().size() + ti;
                    if (fi >= sizeFlowPos.length || sizeFlowPos[fi][0] == -9999) continue;
                    MKTextDef textDef = def.textDefs().get(ti);
                    maxRight = Math.max(maxRight, sizeFlowPos[fi][0] + textDef.estimateWidth());
                    maxBottom = Math.max(maxBottom, sizeFlowPos[fi][1] + MKTextDef.TEXT_HEIGHT);
                }

                livePanelSizes.put(def.name(), new int[]{
                        maxRight + def.effectivePadding() * 2,
                        maxBottom + def.effectivePadding() * 2
                });
            }
        }
    }

    // ── Click Bounds (called by MKScreenMixin) ──────────────────────────────

    /**
     * Checks if a mouse click falls within any registered MKPanel's bounds.
     * Used by {@code hasClickedOutside} override to prevent MKSlots outside
     * the container from being treated as "outside" clicks (which vanilla
     * interprets as THROW/drop item).
     *
     * @return true if the click is inside any panel's bounds
     */
    public static boolean isClickInsideAnyPanel(double mouseX, double mouseY,
                                                 int leftPos, int topPos,
                                                 MKContext context) {
        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;

            // Use collision-avoided position
            int[] pos = getResolvedPosition(def, context);

            // Use live panel sizes (real widget dimensions) when available,
            // fall back to computed size (estimated button widths)
            int[] size = livePanelSizes.containsKey(def.name())
                    ? livePanelSizes.get(def.name())
                    : def.computeSize();

            // Panel bounds in screen space
            int panelX = leftPos + pos[0];
            int panelY = topPos + pos[1];
            int panelW = size[0];
            int panelH = size[1];

            // Check if click is within this panel
            if (mouseX >= panelX && mouseX < panelX + panelW
                    && mouseY >= panelY && mouseY < panelY + panelH) {
                return true;
            }
        }
        return false;
    }

    // ── Unified Click Handler ──────────────────────────────────────────────

    /**
     * Unified click handler for all screen types. Checks if the click is inside
     * any MKPanel; if so, routes the click to screen widgets (MKButtons) first
     * and consumes it to prevent vanilla elements underneath (creative tabs,
     * recipe book buttons, etc.) from firing.
     *
     * <p>Called from both MKScreenMixin and MKCreativeMixin at HEAD of
     * mouseClicked. The logic is identical regardless of screen type —
     * context determines which panels are active.
     *
     * @return true if the click was consumed (inside a panel), false to let vanilla handle
     */
    public static boolean handleMouseClicked(net.minecraft.client.gui.screens.Screen screen,
                                              double mouseX, double mouseY,
                                              int leftPos, int topPos,
                                              MKContext context,
                                              net.minecraft.client.input.MouseButtonEvent event,
                                              boolean flag) {
        if (!isClickInsideAnyPanel(mouseX, mouseY, leftPos, topPos, context)) {
            return false;
        }

        // Click is inside a panel — process only MKButtons, not ALL children
        // (iterating all children would trigger creative tab buttons too)
        for (var child : screen.children()) {
            if (child instanceof MKButton mkBtn && mkBtn.mouseClicked(event, flag)) {
                return true; // MKButton handled the click
            }
        }

        // Even if no widget handled it, consume the click to prevent
        // vanilla elements underneath (tabs, slots) from receiving it
        return true;
    }

    // ── Event System ────────────────────────────────────────────────────────

    /**
     * Entry point for registering event handlers via builder chain.
     *
     * <p>Call this during mod init to register global handlers that fire
     * whenever a matching slot event occurs. Use the builder's filter methods
     * to narrow which events reach your handler.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * MenuKit.on(MKEvent.Type.LEFT_CLICK, MKEvent.Type.RIGHT_CLICK)
     *     .region("storage")
     *     .handler(event -> {
     *         // custom logic here
     *         return MKEventResult.CONSUMED;
     *     });
     * }</pre>
     *
     * @param types one or more event types to listen for
     * @return a builder for configuring filters and the handler
     */
    public static MKEventBuilder on(MKEvent.Type... types) {
        return new MKEventBuilder(types);
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD — Registration, Rendering, Notifications
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Registers a HUD panel definition. Called by {@link MKHudPanel.Builder#build()}.
     */
    static void registerHud(MKHudPanelDef def) {
        hudPanels.put(def.name(), def);
        LOGGER.info("[MenuKit] Registered HUD panel '{}'", def.name());
    }

    /**
     * Registers a notification definition. Called by {@link MKHudNotification.Builder#build()}.
     */
    static void registerNotification(MKHudNotification def) {
        notificationDefs.put(def.getKey(), def);
        LOGGER.info("[MenuKit] Registered notification '{}'", def.getKey());
    }

    /**
     * Renders all registered HUD panels. Called at RETURN of {@code Gui.render()}
     * by GuiMixin. Evaluates visibility conditions, resolves anchor positions,
     * and delegates to each panel's element tree.
     *
     * <p>Uses the same {@link net.minecraft.client.gui.GuiGraphics} and coordinate
     * system as vanilla's HUD — working WITH vanilla, not against it.
     *
     * @param graphics     the GUI graphics context
     * @param deltaTracker tick delta for animations
     */
    public static void renderHud(GuiGraphics graphics,
                                  net.minecraft.client.DeltaTracker deltaTracker) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        boolean hasScreen = mc.screen != null;

        // Render HUD panels
        for (MKHudPanelDef def : hudPanels.values()) {
            // Visibility checks
            if (def.hideInScreen() && hasScreen) continue;
            if (!def.showWhen().get()) continue;

            // Compute panel size
            int[] size = def.computeSize();

            // Resolve anchor to absolute screen position
            int[] pos = def.anchor().resolve(screenW, screenH,
                    size[0], size[1], def.offsetX(), def.offsetY());

            // Render panel background (reuses MKPanel.renderPanel)
            if (def.style() != MKPanel.Style.NONE) {
                MKPanel.renderPanel(graphics, pos[0], pos[1],
                        size[0], size[1], def.style());
            }

            // Optional onRender callback
            if (def.onRender() != null) {
                def.onRender().render(graphics, pos[0], pos[1],
                        size[0], size[1], deltaTracker);
            }

            // Render child elements
            int contentX = pos[0] + def.padding();
            int contentY = pos[1] + def.padding();
            for (MKHudElement element : def.elements()) {
                element.render(graphics, contentX, contentY, deltaTracker);
            }
        }

        // Render active notifications
        renderActiveNotifications(graphics, deltaTracker, screenW, screenH);
    }

    /**
     * Triggers a notification by key. The notification slides in, displays
     * for its configured duration, then fades out.
     *
     * @param key  notification key (registered via MKHudNotification.builder)
     * @param text text to display
     */
    public static void notify(String key, String text) {
        notify(key, text, (ItemStack) null);
    }

    /**
     * Triggers a notification with text and an item icon.
     */
    public static void notify(String key, String text, @Nullable ItemStack item) {
        if (!notificationDefs.containsKey(key)) {
            LOGGER.warn("[MenuKit] Unknown notification key '{}'", key);
            return;
        }
        activeNotifications.put(key, new ActiveNotification(
                System.currentTimeMillis(), text, item));
    }

    /**
     * Triggers a notification with text and an item type.
     */
    public static void notify(String key, String text, net.minecraft.world.item.Item item) {
        notify(key, text, new ItemStack(item));
    }

    /**
     * Renders all active notifications, removing expired ones.
     */
    private static void renderActiveNotifications(
            GuiGraphics graphics,
            net.minecraft.client.DeltaTracker deltaTracker,
            int screenW, int screenH) {
        if (activeNotifications.isEmpty()) return;

        var expired = new ArrayList<String>();
        long now = System.currentTimeMillis();

        for (var entry : activeNotifications.entrySet()) {
            String key = entry.getKey();
            ActiveNotification active = entry.getValue();
            MKHudNotification def = notificationDefs.get(key);
            if (def == null) { expired.add(key); continue; }

            long elapsed = now - active.triggerTimeMs();
            if (elapsed > def.getDurationMs()) {
                expired.add(key);
                continue;
            }

            def.render(graphics, deltaTracker, screenW, screenH,
                    elapsed, active.textData(), active.itemData());
        }

        // Remove expired notifications
        expired.forEach(activeNotifications::remove);
    }

    // ── Server-Thread Execution ───────────────────────────────────────────────

    /**
     * Executes an action on the server thread. Safe to call from either side.
     *
     * <p><b>When this works:</b>
     * <ul>
     *   <li>On a dedicated or integrated server — the action runs on the server
     *       thread via {@link MinecraftServer#execute(Runnable)}.</li>
     *   <li>On a singleplayer client — {@code player.getServer()} returns the
     *       integrated server, so the action is submitted normally.</li>
     * </ul>
     *
     * <p><b>When this is a no-op (returns false):</b>
     * <ul>
     *   <li>On a multiplayer client — there is no local server instance.
     *       Server-side actions must be triggered via network packets instead.</li>
     * </ul>
     *
     * <p>The return value lets callers know whether the action was actually
     * submitted. A {@code false} return does <em>not</em> mean an error — it
     * means there is no server reachable from this context, and the caller
     * should use a packet-based approach instead.
     *
     * @param player the player (used to find the server instance)
     * @param action the action to run on the server thread
     * @return true if the action was submitted, false if no server was available
     */
    public static boolean executeOnServer(Player player, Runnable action) {
        // player.level().getServer() returns the MinecraftServer if one is accessible:
        //   - On dedicated server: always present
        //   - On integrated server (singleplayer): always present
        //   - On multiplayer client: null (no local server)
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;

        // Submit to the server's main thread. If we're already on it, this
        // executes synchronously at the end of the current tick; otherwise
        // it queues for the next tick.
        server.execute(action);
        return true;
    }
}
