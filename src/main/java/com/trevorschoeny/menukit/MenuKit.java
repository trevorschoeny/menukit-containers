package com.trevorschoeny.menukit;

import com.trevorschoeny.menukit.config.*;
import com.trevorschoeny.menukit.container.*;
import com.trevorschoeny.menukit.data.*;
import com.trevorschoeny.menukit.event.*;
import com.trevorschoeny.menukit.hud.*;
import com.trevorschoeny.menukit.input.*;
import com.trevorschoeny.menukit.panel.*;
import com.trevorschoeny.menukit.region.*;
import com.trevorschoeny.menukit.screen.*;
import com.trevorschoeny.menukit.widget.*;

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

    /** Offscreen coordinate used to hide slots that are scrolled outside their viewport.
     *  Not a disabled-element sentinel — these slots are still "active" in the layout,
     *  just physically moved out of view for viewport clipping. */
    public static final int SCROLL_OFFSCREEN = -999;

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

    // Panels suppressed by exclusive-panel logic or missing region targets.
    // Checked by isPanelSuppressed() — replaces the old {-9999, -9999} sentinel
    // in resolvedPositions.
    private static final Set<String> suppressedPanels = new HashSet<>();
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


    // ── Scroll / Tab region tracking (client-only, updated each frame) ─────
    // Collected by walking panel layout trees during renderPanelBackgrounds.
    // Used by renderSlotBackgrounds (scissor clipping), scroll input handling,
    // and tab click handling.
    //
    // Each entry maps: panelName -> list of scroll/tab regions with absolute
    // screen-space coordinates (panel position + padding + content-relative offset).

    /** Per-panel scroll regions computed each frame. Key = panelName. */
    private static final Map<String, List<MKGroupDef.ScrollRegion>> scrollRegionsByPanel = new HashMap<>();

    /** Per-panel tab regions computed each frame. Key = panelName. */
    private static final Map<String, List<MKGroupDef.TabsRegion>> tabsRegionsByPanel = new HashMap<>();

    /**
     * Sorts scroll regions by viewport area (smallest first) so that nested
     * inner scroll regions match before their enclosing parent regions.
     * Parent regions have larger content/viewport bounds that encompass child
     * regions, so without this sort a slot inside an inner scroll could
     * incorrectly match the outer scroll first.
     */
    private static void sortScrollRegionsSmallestFirst(List<MKGroupDef.ScrollRegion> regions) {
        regions.sort(Comparator.comparingInt(
                sr -> sr.viewportWidth() * sr.viewportHeight()));
    }

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

    // ── Conditional element rules ──────────────────────────────────────────
    // Rules that insert elements into the panel tree when their predicate
    // matches an existing element. Evaluated after menu resolution.
    private static final List<MKConditionalRule> conditionalRules = new ArrayList<>();
    // Tracks which rule+element pairs have already been inserted, preventing
    // duplicate insertion on re-evaluation. Key format: "ruleId:elementId".
    private static final Set<String> conditionalInsertions = new HashSet<>();

    // ── Button Attachments ───────────────────────────────────────────────────
    // Registered button sets that auto-attach to containers by type.
    // In-tree injection happens at panel build time; overlay panels are
    // created at menu resolution for vanilla wrapper panels.
    private static final List<MKButtonAttachment> buttonAttachments = new ArrayList<>();
    // Tracks which overlay panels have been created for button attachments,
    // so we don't create duplicates. Key format: "att:<id>:<regionName>".
    private static final Set<String> createdOverlayPanels = new HashSet<>();

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

        // Register built-in region groups — logical groupings of vanilla regions
        // that mods can query for aggregate item counts, sorting, etc.
        registerBuiltInRegionGroups();

        LOGGER.info("[MenuKit] Registered MK_MENU_TYPE + vanilla inventory panels + region groups");
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
    public static void register(MKPanelDef def) {
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
        // Position is irrelevant: Style.NONE panels don't render, and these
        // panels have zero slot defs (slots come from vanilla via MKSlotWrapper).
        MKPanel.builder(PANEL_HOTBAR)
                .showIn(MKContext.ALL_WITH_PLAYER_INVENTORY)
                .style(MKPanel.Style.NONE)
                .pos(0, 0)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // Main inventory — always shift-clickable in both directions
        MKPanel.builder(PANEL_MAIN_INVENTORY)
                .showIn(MKContext.ALL_WITH_PLAYER_INVENTORY)
                .style(MKPanel.Style.NONE)
                .pos(0, 0)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // Armor — shift-clickable (vanilla armor shift-click behavior)
        MKPanel.builder(PANEL_ARMOR)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(0, 0)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // Offhand — shift-clickable
        MKPanel.builder(PANEL_OFFHAND)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(0, 0)
                .shiftClickIn(true)
                .shiftClickOut(true)
                .column().build();

        // 2x2 crafting grid — transient workspace, can shift OUT but not IN
        MKPanel.builder(PANEL_CRAFT_2X2)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(0, 0)
                .shiftClickIn(false)
                .shiftClickOut(true)
                .column().build();

        // Crafting result — output only (can take out, can't put in)
        MKPanel.builder(PANEL_CRAFT_RESULT)
                .showIn(MKContext.PERSONAL)
                .style(MKPanel.Style.NONE)
                .pos(0, 0)
                .shiftClickIn(false)
                .shiftClickOut(true)
                .column().build();
    }

    // ── Built-in Region Groups ──────────────────────────────────────────────

    /**
     * Registers built-in region groups for common vanilla region combinations.
     * These are available in any menu that has the relevant regions.
     */
    private static void registerBuiltInRegionGroups() {
        // player_storage: hotbar + main inventory — the two main item storage regions
        regionGroup("player_storage")
                .region(PANEL_HOTBAR, 1)
                .region(PANEL_MAIN_INVENTORY, 2)
                .register();

        // player_all: all four player inventory regions
        regionGroup("player_all")
                .region(PANEL_HOTBAR, 1)
                .region(PANEL_MAIN_INVENTORY, 2)
                .region(PANEL_ARMOR, 3)
                .region(PANEL_OFFHAND, 4)
                .register();
    }

    // ── Slot Group Registration (v2 API) ────────────────────────────────────

    // Slot group definitions (registered at mod init, immutable after startup)
    private static final Map<String, MKSlotGroupDef> slotGroupDefs = new LinkedHashMap<>();

    /**
     * Creates a slot group builder — the primary declaration unit in MenuKit v2.
     * A slot group defines slots, rules, persistence, and transfer policy in
     * a single declaration.
     *
     * <p>Usage:
     * <pre>{@code
     * MenuKit.slotGroup("equipment")
     *     .slots(2)
     *     .playerBound()
     *     .register();
     *
     * MenuKit.slotGroup("fuel")
     *     .slots(1)
     *     .filter(stack -> stack.getBurnTime() > 0)
     *     .shiftIn()
     *     .instanceBound()
     *     .register();
     * }</pre>
     *
     * @param name the unique group name
     * @return a fluent builder
     */
    public static MKSlotGroupBuilder slotGroup(String name) {
        return new MKSlotGroupBuilder(name);
    }

    /**
     * Registers a slot group definition. Called by {@link MKSlotGroupBuilder#register()}.
     * Internally creates an {@link MKContainerDef} for backward compatibility
     * with the existing region/container infrastructure.
     */
    public static void registerSlotGroup(MKSlotGroupDef def) {
        if (slotGroupDefs.containsKey(def.name())) {
            LOGGER.warn("[MenuKit] Slot group '{}' registered twice — overwriting", def.name());
        }
        slotGroupDefs.put(def.name(), def);

        // Create the internal MKContainerDef for backward compatibility.
        // The existing region resolution machinery uses MKContainerDef,
        // so we bridge from the new API to the old infrastructure.
        MKContainerDef containerDef = new MKContainerDef(
                def.name(), def.binding(), def.persistence(), def.size(), def.containerType()
        );
        registerContainer(containerDef);

        LOGGER.info("[MenuKit] Registered slot group '{}' → {} slots, {}, shiftIn={}, shiftOut={}",
                def.name(), def.size(), def.binding(), def.shiftClickIn(), def.shiftClickOut());
    }

    /** Returns a slot group definition by name, or null. */
    public static @Nullable MKSlotGroupDef getSlotGroupDef(String name) {
        return slotGroupDefs.get(name);
    }

    /** Returns all registered slot group definitions. */
    public static Collection<MKSlotGroupDef> getSlotGroupDefs() {
        return Collections.unmodifiableCollection(slotGroupDefs.values());
    }

    // ── Region Group Registration ───────────────────────────────────────────

    /**
     * Creates a region group builder. Region groups treat multiple regions as
     * one logical unit for aggregate queries (item counts, empty slots, etc.)
     * and cross-region sorting.
     *
     * <p>Usage:
     * <pre>{@code
     * MenuKit.regionGroup("player_storage")
     *     .region("mk:hotbar", 1)
     *     .region("mk:main_inventory", 2)
     *     .register();
     * }</pre>
     *
     * @param name the group name (e.g., "player_storage")
     * @return a fluent builder
     */
    public static RegionGroupBuilder regionGroup(String name) {
        return new RegionGroupBuilder(name);
    }

    /**
     * Registers a region group definition. Called by {@link RegionGroupBuilder#register()}.
     * Package-private — external code uses the builder API.
     */
    public static void registerRegionGroup(MKRegionGroupDef def) {
        MKRegionRegistry.registerGroupDef(def);
    }

    /**
     * Registers a container definition. Called by {@link MKContainerDef.Builder#register()}.
     */
    public static void registerContainer(MKContainerDef def) {
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

    // ── Conditional Element API ────────────────────────────────────────────

    /**
     * Entry point for building a conditional element rule.
     * The rule will be evaluated after menu resolution to insert elements
     * into the panel tree wherever the condition matches.
     *
     * <p>Example:
     * <pre>{@code
     * MenuKit.conditionalElement("sort_button")
     *     .when(child -> child instanceof MKGroupChild.SlotGroup sg
     *             && sg.containerType() == MKContainerType.SIMPLE)
     *     .insertAfter()
     *     .element(ctx -> new MKGroupChild.Button(sortButtonDef, "sort:" + ctx.matchedElementId()))
     *     .register();
     * }</pre>
     *
     * @param ruleId unique identifier for this rule
     * @return a builder for the conditional rule
     */
    public static MKConditionalRule.Builder conditionalElement(String ruleId) {
        return MKConditionalRule.builder(ruleId);
    }

    /**
     * Registers a conditional rule. Called by {@link MKConditionalRule.Builder#register()}.
     * Package-private -- external code uses the builder API.
     */
    public static void registerConditionalRule(MKConditionalRule rule) {
        conditionalRules.add(rule);
        LOGGER.info("[MenuKit] Registered conditional rule '{}'", rule.id());
    }

    // ── Button Attachment API ────────────────────────────────────────────────

    /**
     * Starts building a button attachment — a set of buttons that
     * automatically attach to containers of a given type.
     *
     * <p>For MenuKit panels, buttons are injected into the tree at build time.
     * For vanilla panels, overlay panels are created at menu resolution.
     *
     * <p>Example:
     * <pre>{@code
     * MenuKit.buttonAttachment("sort_buttons")
     *     .forContainerType(MKContainerType.SIMPLE)
     *     .above()
     *     .gap(2)
     *     .buttons(regionName -> List.of(sortBtn, moveBtn))
     *     .register();
     * }</pre>
     *
     * @param attachmentId unique identifier
     * @return a builder
     */
    public static MKButtonAttachment.Builder buttonAttachment(String attachmentId) {
        return MKButtonAttachment.builder(attachmentId);
    }

    /**
     * Creates a builder for registering a custom drag mode.
     *
     * <p>Example usage:
     * <pre>{@code
     * MenuKit.dragMode("shift_click_drag")
     *     .when(ctx -> ctx.isShiftHeld() && ctx.cursorStack().isEmpty())
     *     .onSlotEntered(event -> quickMoveSlot(event.slot(), event.context()))
     *     .register();
     * }</pre>
     *
     * @param id unique identifier for this drag mode
     * @return a builder to configure and register the mode
     */
    public static MKDragMode.Builder dragMode(String id) {
        return new MKDragMode.Builder(id);
    }

    /**
     * Registers a button attachment. Called by the builder's register().
     * Package-private.
     */
    public static void registerButtonAttachment(MKButtonAttachment attachment) {
        buttonAttachments.add(attachment);
        LOGGER.info("[MenuKit] Registered button attachment '{}' for type {}",
                attachment.id(), attachment.containerType());

        // Hot-patch: apply this attachment to any panels that were already built.
        // This handles the case where a mod registers an attachment after another
        // mod's panel has already been built (common with cross-mod dependencies).
        for (MKPanelDef def : panels.values()) {
            if (def.rootGroup() == null) continue;
            applyAttachmentsToGroup(def.rootGroup(), def.name(), java.util.List.of(attachment));
        }
    }

    /**
     * Applies registered button attachments to a panel's tree.
     * Walks the tree looking for SlotGroups matching each attachment's
     * container type. For each match, creates a button row and inserts
     * it before/after the SlotGroup in its parent group.
     *
     * <p>Called from {@link MKPanel.Builder#build()} so buttons are part
     * of the tree from birth — no post-hoc injection needed.
     *
     * @param root the panel's root group
     * @param panelName the panel name (for logging)
     */
    public static void applyButtonAttachments(MKGroupDef root, String panelName) {
        if (buttonAttachments.isEmpty()) return;
        applyAttachmentsToGroup(root, panelName);
    }

    /**
     * Recursively walks a group looking for SlotGroups that match
     * button attachments. When found, creates the button row and
     * inserts it into the parent group.
     *
     * <p>Merging: if multiple attachments target the same SlotGroup at the
     * same position (above/below), their buttons are merged into a single
     * row so they stack horizontally, not vertically.
     */
    private static void applyAttachmentsToGroup(MKGroupDef group, String panelName) {
        applyAttachmentsToGroup(group, panelName, buttonAttachments);
    }

    /**
     * Applies a specific list of attachments to the group tree.
     * Used both for initial build (all attachments) and hot-patching (single new attachment).
     */
    private static void applyAttachmentsToGroup(MKGroupDef group, String panelName,
                                                 java.util.List<MKButtonAttachment> attachments) {
        // Snapshot children to avoid ConcurrentModificationException
        List<MKGroupChild> snapshot = new ArrayList<>(group.children());

        for (MKGroupChild child : snapshot) {
            if (child instanceof MKGroupChild.SlotGroup sg) {
                String regionName = sg.id();

                for (MKButtonAttachment att : attachments) {
                    if (sg.containerType() != att.containerType()) continue;
                    if (att.isExcluded(regionName)) continue;

                    MKGroupDef buttonRow = att.createButtonRow(regionName);
                    if (buttonRow == null) continue;

                    // Merge: check if there's already an attachment row at this position.
                    // If so, append our buttons to it instead of creating a new row.
                    String mergedId = "att:merged:" + regionName + ":" + (att.above() ? "above" : "below");
                    MKGroupChild existing = group.findById(mergedId);

                    if (existing instanceof MKGroupChild.Group g) {
                        // Append buttons to the existing merged row
                        g.def().children().addAll(buttonRow.children());
                        LOGGER.debug("[MenuKit] Merged '{}' buttons into existing row for '{}' in '{}'",
                                att.id(), regionName, panelName);
                    } else {
                        // Create new merged row
                        MKGroupChild rowChild = new MKGroupChild.Group(buttonRow, mergedId);
                        if (att.above()) {
                            group.insertBefore(regionName, rowChild);
                        } else {
                            group.insertAfter(regionName, rowChild);
                        }
                        LOGGER.debug("[MenuKit] Attached '{}' buttons to SlotGroup '{}' in panel '{}'",
                                att.id(), regionName, panelName);
                    }
                }
            }

            // Recurse into nested groups
            switch (child) {
                case MKGroupChild.Group g -> applyAttachmentsToGroup(g.def(), panelName, attachments);
                case MKGroupChild.SlotGroup sg -> applyAttachmentsToGroup(sg.group(), panelName, attachments);
                case MKGroupChild.Dynamic d -> applyAttachmentsToGroup(d.def().expandedGroup(), panelName, attachments);
                case MKGroupChild.Scroll sc -> applyAttachmentsToGroup(sc.def().contentGroup(), panelName, attachments);
                case MKGroupChild.Tabs tb -> {
                    for (MKTabDef tab : tb.def().tabs()) {
                        applyAttachmentsToGroup(tab.contentGroup(), panelName, attachments);
                    }
                }
                case MKGroupChild.Spanning s -> {
                    if (s.inner() instanceof MKGroupChild.Group g)
                        applyAttachmentsToGroup(g.def(), panelName, attachments);
                    else if (s.inner() instanceof MKGroupChild.SlotGroup sg)
                        applyAttachmentsToGroup(sg.group(), panelName, attachments);
                }
                default -> {} // Slot, Button, Text — no children
            }
        }
    }

    /**
     * Creates overlay panels for button attachments that match vanilla
     * regions (regions not owned by a MenuKit panel's tree).
     *
     * <p>Called from {@link #onMenuResolved} after regions are populated.
     *
     * @param menu the current container menu
     */
    static void createAttachmentOverlays(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        if (buttonAttachments.isEmpty()) return;

        for (MKRegion region : MKRegionRegistry.getRegions(menu)) {
            for (MKButtonAttachment att : buttonAttachments) {
                if (region.containerType() != att.containerType()) continue;
                if (att.isExcluded(region.name())) continue;

                String overlayName = att.overlayPanelName(region.name());

                // Skip if already created or if a MenuKit panel owns this region
                // (the in-tree injection already handles it)
                if (createdOverlayPanels.contains(overlayName)) continue;
                if (isRegionOwnedByMenuKitPanel(region.name())) continue;

                // Create an overlay panel positioned above/below the region
                MKGroupDef buttonRow = att.createButtonRow(region.name());
                if (buttonRow == null) continue;

                MKPanel.builder(overlayName)
                        .showIn(MKContext.ALL)
                        .autoSize()
                        .style(MKPanel.Style.NONE)
                        .followsRegion(region.name(), att.overlayPlacement(), 1,
                                att.overlayOffsetX(), att.overlayOffsetY())
                        .injectRootGroup(buttonRow)
                        .build();

                createdOverlayPanels.add(overlayName);
                LOGGER.debug("[MenuKit] Created overlay '{}' for region '{}'",
                        overlayName, region.name());
            }
        }
    }

    /**
     * Returns true if the given region name corresponds to a SlotGroup
     * in any MenuKit panel's tree (i.e., the panel has buttons attached
     * in-tree and doesn't need an overlay).
     */
    private static boolean isRegionOwnedByMenuKitPanel(String regionName) {
        for (MKPanelDef def : panels.values()) {
            if (def.rootGroup() == null) continue;
            // Empty wrapper panels (vanilla) have no real children —
            // check if the panel has a SlotGroup with this region name
            if (hasSlotGroupInTree(def.rootGroup(), regionName)) return true;
        }
        return false;
    }

    /** Recursively checks if a tree contains a SlotGroup with the given ID. */
    private static boolean hasSlotGroupInTree(MKGroupDef group, String regionName) {
        for (MKGroupChild child : group.children()) {
            if (child instanceof MKGroupChild.SlotGroup sg && regionName.equals(sg.id())) {
                return true;
            }
            switch (child) {
                case MKGroupChild.Group g -> { if (hasSlotGroupInTree(g.def(), regionName)) return true; }
                case MKGroupChild.SlotGroup sg -> { if (hasSlotGroupInTree(sg.group(), regionName)) return true; }
                case MKGroupChild.Dynamic d -> { if (hasSlotGroupInTree(d.def().expandedGroup(), regionName)) return true; }
                case MKGroupChild.Scroll sc -> { if (hasSlotGroupInTree(sc.def().contentGroup(), regionName)) return true; }
                case MKGroupChild.Tabs tb -> {
                    for (MKTabDef tab : tb.def().tabs()) {
                        if (hasSlotGroupInTree(tab.contentGroup(), regionName)) return true;
                    }
                }
                default -> {}
            }
        }
        return false;
    }

    /**
     * Evaluates all registered conditional rules against the panel trees.
     * For each panel definition with a rootGroup, walks the tree and checks
     * each child against each rule's predicate. If a match is found and
     * the elements haven't already been inserted (tracked by rule+element ID),
     * creates and inserts the new children.
     *
     * <p>Called after {@code resolveForMenu()} and when new regions appear.
     *
     * @param menu the current container menu
     */
    static void evaluateConditionalRules(AbstractContainerMenu menu) {
        if (conditionalRules.isEmpty()) return;

        for (MKPanelDef panelDef : panels.values()) {
            MKGroupDef root = panelDef.rootGroup();
            if (root == null) continue;

            // Walk the tree and evaluate rules against each child
            evaluateRulesOnGroup(root, panelDef.name(), menu);
        }
    }

    /**
     * Recursively walks a group's children and evaluates conditional rules.
     * When a rule matches, inserts the new elements using the group's
     * mutation methods.
     */
    private static void evaluateRulesOnGroup(MKGroupDef group, String panelName,
                                              AbstractContainerMenu menu) {
        // Snapshot the children list to avoid ConcurrentModificationException
        // since we may insert new children during iteration.
        List<MKGroupChild> snapshot = new ArrayList<>(group.children());

        for (MKGroupChild child : snapshot) {
            // Extract the child's ID for insertion tracking
            String childId = extractChildId(child);

            // Check each rule against this child
            if (childId != null) {
                for (MKConditionalRule rule : conditionalRules) {
                    // Skip disabled rules
                    if (rule.disabledWhen() != null && rule.disabledWhen().getAsBoolean()) continue;

                    // Check duplicate prevention
                    String insertionKey = rule.id() + ":" + childId;
                    if (conditionalInsertions.contains(insertionKey)) continue;

                    // Test the predicate
                    if (!rule.condition().test(child)) continue;

                    // Match found -- build context
                    String regionName = null;
                    MKContainerType containerType = null;
                    if (child instanceof MKGroupChild.SlotGroup sg) {
                        regionName = sg.id();
                        containerType = sg.containerType();
                    }

                    MKConditionalContext ctx = new MKConditionalContext(
                            childId, child, panelName, regionName, containerType);

                    // Create new elements from factories
                    List<MKGroupChild> newChildren = new ArrayList<>();
                    for (MKConditionalRule.ElementFactory factory : rule.elements()) {
                        MKGroupChild created = factory.create(ctx);
                        if (created != null) newChildren.add(created);
                    }

                    if (newChildren.isEmpty()) continue;

                    // Insert the new children
                    if (rule.placement() == MKConditionalRule.Placement.BEFORE) {
                        // Insert all in reverse order so they end up in the correct order
                        for (int i = newChildren.size() - 1; i >= 0; i--) {
                            group.insertBefore(childId, newChildren.get(i));
                        }
                    } else {
                        // Insert after -- each successive element goes after the previous
                        // Find the target ID to insert after. For the first element, it's
                        // the matched child. For subsequent elements, we need to insert
                        // after the previously inserted element (if it has an ID) or
                        // after the original matched child.
                        String afterId = childId;
                        for (MKGroupChild newChild : newChildren) {
                            group.insertAfter(afterId, newChild);
                            // If the newly inserted child has an ID, subsequent elements
                            // go after it to maintain order
                            String newId = extractChildId(newChild);
                            if (newId != null) afterId = newId;
                        }
                    }

                    // Mark as inserted
                    conditionalInsertions.add(insertionKey);
                }
            }

            // Recurse into nested groups
            recurseIntoChild(child, panelName, menu);
        }
    }

    /** Recursively evaluates conditional rules inside nested group structures. */
    private static void recurseIntoChild(MKGroupChild child, String panelName,
                                          AbstractContainerMenu menu) {
        switch (child) {
            case MKGroupChild.Group g ->
                evaluateRulesOnGroup(g.def(), panelName, menu);
            case MKGroupChild.SlotGroup sg ->
                evaluateRulesOnGroup(sg.group(), panelName, menu);
            case MKGroupChild.Dynamic d ->
                evaluateRulesOnGroup(d.def().expandedGroup(), panelName, menu);
            case MKGroupChild.Scroll sc ->
                evaluateRulesOnGroup(sc.def().contentGroup(), panelName, menu);
            case MKGroupChild.Tabs tb -> {
                for (MKTabDef tab : tb.def().tabs()) {
                    evaluateRulesOnGroup(tab.contentGroup(), panelName, menu);
                }
            }
            case MKGroupChild.Spanning s ->
                recurseIntoChild(s.inner(), panelName, menu);
            default -> {} // Slot, Button, Text -- no nesting
        }
    }

    /** Extracts the ID from any MKGroupChild variant. Returns null if no ID. */
    private static @Nullable String extractChildId(MKGroupChild child) {
        return switch (child) {
            case MKGroupChild.Slot s -> s.id();
            case MKGroupChild.Button b -> b.id();
            case MKGroupChild.Text t -> t.id();
            case MKGroupChild.Group g -> g.id();
            case MKGroupChild.SlotGroup sg -> sg.id();
            case MKGroupChild.Spanning s -> null;
            case MKGroupChild.Dynamic d -> d.id();
            case MKGroupChild.Scroll sc -> sc.id();
            case MKGroupChild.Tabs tb -> tb.id();
        };
    }

    /**
     * Injects virtual {@link MKGroupChild.SlotGroup} children into vanilla
     * wrapper panels whose trees are empty. This gives the conditional rule
     * system something to match against in vanilla panels.
     *
     * <p>Called after {@code resolveForMenu()}, before {@code evaluateConditionalRules()}.
     *
     * @param menu the current container menu
     */
    static void injectVirtualSlotGroups(AbstractContainerMenu menu) {
        // For each resolved region, check if a vanilla wrapper panel exists
        List<MKRegion> regions = MKRegionRegistry.getRegions(menu);
        for (MKRegion region : regions) {
            // Find the panel that wraps this region, if any
            // Convention: vanilla wrapper panels are named after the region
            // (e.g., panel "mk:chest" wraps region "mk:chest")
            MKPanelDef panelDef = panels.get(region.name());
            if (panelDef == null) continue;

            MKGroupDef root = panelDef.rootGroup();
            if (root == null) continue;

            // Only inject if the tree is empty (no existing children)
            if (!root.children().isEmpty()) continue;

            // Create a virtual SlotGroup -- empty inner group (no actual slot defs)
            String slotGroupId = "slots:" + region.name();
            MKGroupDef emptyGroup = new MKGroupDef(
                    MKGroupDef.LayoutMode.COLUMN, 0, 18, 9, false,
                    List.of(), null, null, null);

            MKGroupChild.SlotGroup virtualSlotGroup = new MKGroupChild.SlotGroup(
                    slotGroupId, region.containerType(), emptyGroup);

            root.children().add(virtualSlotGroup);
            LOGGER.debug("[MenuKit] Injected virtual SlotGroup '{}' into panel '{}'",
                    slotGroupId, panelDef.name());
        }
    }

    /**
     * Clears conditional insertion tracking. Called when the menu changes
     * so rules can be re-evaluated for the new context.
     */
    static void resetConditionalInsertions() {
        conditionalInsertions.clear();
    }

    /**
     * Called after regions are resolved for a menu. Injects virtual SlotGroups
     * into vanilla wrapper panels and evaluates conditional rules.
     *
     * <p>This is the main entry point for the conditional element system
     * during menu construction. Also called when dynamic regions appear
     * (e.g., peek containers becoming visible).
     *
     * @param menu the container menu that was just resolved
     */
    public static void onMenuResolved(AbstractContainerMenu menu) {
        resetConditionalInsertions();
        createdOverlayPanels.clear();
        injectVirtualSlotGroups(menu);
        evaluateConditionalRules(menu);
        createAttachmentOverlays(menu);
    }

    /**
     * Re-evaluates conditional rules for a specific menu. Called when
     * new regions appear (e.g., dynamic region registration) so that
     * rules can react to newly-available containers.
     *
     * @param menu the container menu to re-evaluate
     */
    public static void onRegionPopulated(AbstractContainerMenu menu) {
        // Re-inject virtual slot groups (new regions may have appeared)
        injectVirtualSlotGroups(menu);
        // Re-evaluate rules (new matches may be possible)
        evaluateConditionalRules(menu);
        // Create overlays for newly-appeared regions
        createAttachmentOverlays(menu);
    }

    // ── Panel Queries ──────────────────────────────────────────────────────

    /** Returns a panel definition by name, or null if not found. */
    public static MKPanelDef getPanelDef(String name) {
        return panels.get(name);
    }

    // ── Panel Visibility ───────────────────────────────────────────────────

    /** Shows a hidden panel by name. Fires a PANEL_SHOW event.
     *  Also registers dynamic regions for any container-backed slots in
     *  this panel that don't already have a region (e.g., peek containers
     *  activated after initial menu construction). */
    public static void showPanel(String name) {
        hiddenPanels.remove(name);
        // Fire panel visibility event
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            MKContext ctx = resolveCurrentContext();
            MKEventBus.fire(MKUIEvent.panelShow(name, ctx, mc.player));

            // Register dynamic regions for container-backed slots in this panel.
            // Panels that are initially hidden (e.g., peek) have their slots in
            // the menu but no region — because resolveForMenu() only creates
            // regions from MKContextLayout (vanilla containers). This ensures
            // dynamically-shown panels get proper regions for item tips, etc.
            registerDynamicRegionsForPanel(name, mc.player);

            // Invalidate position caches so overlay panels recompute positions
            // using the newly-visible panel's region.
            invalidateResolvedPositions();
        }
    }

    /**
     * Checks a panel's slot definitions for containers that don't yet have
     * a region in the current menu, and creates regions for them.
     *
     * <p>Called from {@link #showPanel(String)} to ensure dynamically-activated
     * containers (peek, etc.) get regions. Generic — works for any container.
     */
    private static void registerDynamicRegionsForPanel(String panelName,
                                                        net.minecraft.world.entity.player.Player player) {
        MKPanelDef def = panels.get(panelName);
        if (def == null || def.slotDefs().isEmpty()) return;

        net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return;

        // Collect unique container names referenced by this panel's slots
        java.util.Set<String> containerNames = new java.util.LinkedHashSet<>();
        for (MKSlotDef slotDef : def.slotDefs()) {
            if (!slotDef.isVanillaSlot()) {
                containerNames.add(slotDef.containerName());
            }
        }

        // For each container name, check if a region already exists. If not,
        // look up the container definition and the live container instance,
        // then register a dynamic region.
        boolean isServer = player instanceof net.minecraft.server.level.ServerPlayer;
        for (String containerName : containerNames) {
            // Skip if region already exists for this container name
            if (MKRegionRegistry.getRegion(menu, containerName) != null) continue;

            MKContainerDef containerDef = containerDefs.get(containerName);
            if (containerDef == null) continue;

            // Get the live container — for player-bound and ephemeral containers
            MKContainer mkContainer = getContainerForPlayer(
                    containerName, player.getUUID(), isServer);
            if (mkContainer == null) continue;

            // Use the delegate Container (what the actual MKSlots reference)
            net.minecraft.world.Container delegate = mkContainer.getDelegate();

            // Use the overload that fires REGION_POPULATED so listeners
            // (e.g., dynamic sort button creation) can react to new regions.
            MKContext ctx = resolveCurrentContext();
            MKRegionRegistry.registerDynamicRegion(
                    menu, containerName, delegate,
                    containerDef.size(), containerDef.persistence(),
                    def.shiftClickIn(), def.shiftClickOut(),
                    player, ctx
            );
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
     * Returns true if the named panel was suppressed during position resolution.
     * Suppressed panels are those hidden by exclusive-panel logic (a competing
     * exclusive panel is active on the same side) or region-following panels
     * whose target region/group doesn't exist in the current menu.
     *
     * <p>This replaces the old pattern of storing {-9999, -9999} in resolvedPositions.
     */
    public static boolean isPanelSuppressed(String name) {
        return suppressedPanels.contains(name);
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
        if (isPanelInactive(name)) {
            LOGGER.debug("[MenuKit] isShiftClickIn('{}') = false (panel inactive: hidden={}, disabled={})",
                    name, isPanelHidden(name), isPanelDisabled(name));
            return false;
        }
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
        if (isPanelInactive(name)) {
            LOGGER.debug("[MenuKit] isShiftClickOut('{}') = false (panel inactive: hidden={}, disabled={})",
                    name, isPanelHidden(name), isPanelDisabled(name));
            return false;
        }
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
        LOGGER.debug("[MenuKit] tryRouteToOtherPanels: sourcePanel='{}', item={}, count={}, totalSlots={}",
                sourcePanel, sourceStack.getItem(), sourceStack.getCount(), menu.slots.size());

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

            // Skip sort-locked slots — they are pinned in place for sorting
            // and should not receive shift-clicked items
            MKSlotState targetState = MKSlotStateRegistry.get(targetSlot);
            if (targetState != null && targetState.isSortLocked()) continue;

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

            // Skip sort-locked slots — pinned in place, don't receive shift-clicked items
            MKSlotState targetState = MKSlotStateRegistry.get(targetSlot);
            if (targetState != null && targetState.isSortLocked()) continue;

            if (targetSlot.getItem().isEmpty()) {
                int toPlace = Math.min(sourceStack.getCount(), targetSlot.getMaxStackSize(sourceStack));
                targetSlot.set(sourceStack.split(toPlace));
                moved = true;
            }
        }

        LOGGER.debug("[MenuKit] tryRouteToOtherPanels: result moved={}, remaining={}",
                moved, sourceStack.getCount());
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

            // Skip sort-locked slots — pinned in place, don't receive shift-clicked items
            if (state.isSortLocked()) continue;

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

            // Skip sort-locked slots — pinned in place, don't receive shift-clicked items
            if (state.isSortLocked()) continue;

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
        MKLayoutResult layout = def.computeFlowPositions();
        for (int i = 0; i < def.slotDefs().size(); i++) {
            MKSlotDef slotDef = def.slotDefs().get(i);
            MKSlot slot = slotDef.createSlotAt(lookup,
                    0, 0, def.effectivePadding(),
                    layout.x(i), layout.y(i), panelName, player);
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

        for (int bi = 0; bi < def.buttonDefs().size(); bi++) {
            MKButtonDef btnDef = def.buttonDefs().get(bi);
            MKButton btn = btnDef.createButton(
                    0, 0, def.effectivePadding(),
                    leftPos, topPos, groups);
            btn.panelName = def.name();
            btn.buttonIndex = bi;
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
            MKLayoutResult layout = def.computeFlowPositions();
            for (int i = 0; i < def.slotDefs().size(); i++) {
                MKSlotDef slotDef = def.slotDefs().get(i);
                MKSlot slot = slotDef.createSlotAt(lookup,
                        pos[0], pos[1], def.effectivePadding(),
                        layout.x(i), layout.y(i), def.name(), player);
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
            MKLayoutResult layout = def.computeFlowPositions();
            for (int i = 0; i < def.slotDefs().size(); i++) {
                MKSlotDef slotDef = def.slotDefs().get(i);
                MKSlot slot = slotDef.createSlotAt(lookup,
                        pos[0], pos[1], def.effectivePadding(),
                        layout.x(i), layout.y(i), def.name(), player);
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
                }
                // Slots with no position entry are inactive — their isActive()
                // flag prevents all interaction, so no need to move offscreen.
            }
        }
    }

    /**
     * Called when a creative tab changes. Repositions slots, resolves regions
     * on the ItemPickerMenu, and invalidates caches.
     *
     * <p>Regions must be resolved on the screen's menu (ItemPickerMenu) so that
     * overlay panels (sort/move buttons) can compute correct bounding boxes
     * from the creative-positioned SlotWrapper slots. The inventoryMenu's
     * vanilla slots can't be repositioned (repositionSlots only moves MKSlots),
     * so we resolve regions on the menu that HAS correct positions.
     */
    public static void onCreativeTabChanged(
            net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen screen,
            MKContext context) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;

        repositionSlots(screen.getMenu(), context);

        // Resolve regions on the ItemPickerMenu so overlay panels can find
        // regions with correct creative-layout slot positions. The vanilla
        // SlotWrapper slots in ItemPickerMenu have creative positions set by
        // selectTab, so computeRegionBBox reads the right coordinates.
        resolveCreativeMenuRegions(screen.getMenu(), context, player);

        // Invalidate position caches so collision avoidance recomputes
        invalidateResolvedPositions();

        // Force sync
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * Resolves regions on the creative screen's ItemPickerMenu by scanning
     * for SlotWrapper objects that wrap inventoryMenu slots, grouping them
     * by panel name, and creating MKRegion objects with the correct
     * ItemPickerMenu slot indices.
     *
     * <p>Cannot use {@link MKRegionRegistry#resolveForMenu} because that reads
     * hardcoded slot indices from {@link MKContextLayout} (inventoryMenu indices).
     * Cannot use {@link MKRegionRegistry#registerDynamicRegion} because vanilla
     * inventory slots share the same Container (Inventory), so container-matching
     * can't distinguish main inventory from hotbar. We must scan and create
     * regions manually.
     */
    private static void resolveCreativeMenuRegions(
            net.minecraft.world.inventory.AbstractContainerMenu creativeMenu,
            MKContext context,
            net.minecraft.world.entity.player.Player player) {

        // Track the first and last ItemPickerMenu index for each panel name,
        // plus the container and container-start-index.
        java.util.Map<String, int[]> panelRanges = new java.util.LinkedHashMap<>();

        for (int i = 0; i < creativeMenu.slots.size(); i++) {
            var slot = creativeMenu.slots.get(i);

            // Unwrap SlotWrapper to find the original inventoryMenu slot
            net.minecraft.world.inventory.Slot targetSlot = slot;
            if (slot instanceof com.trevorschoeny.menukit.mixin.SlotWrapperAccessor wrapper) {
                targetSlot = wrapper.menuKit$getTarget();
            }

            // Look up panel name from the inventoryMenu slot mapping
            String panelName = getSlotPanelName(player.inventoryMenu, targetSlot.index);
            if (panelName == null) continue;

            // Track range: [firstMenuSlot, lastMenuSlot, containerStartIndex]
            int[] range = panelRanges.get(panelName);
            if (range == null) {
                panelRanges.put(panelName, new int[]{ i, i, slot.getContainerSlot() });
            } else {
                range[1] = i; // extend end
            }

            // Map panel name on the creative menu too
            registerSlotPanelMapping(creativeMenu, i, i + 1, panelName);

            // Set up state for the creative menu slot
            MKSlotState state = MKSlotStateRegistry.getOrCreate(slot);
            if (state.getPanelName() == null) {
                state.setPanelName(panelName);
            }
        }

        // Remove only the regions we're about to re-create (player inv regions
        // like mk:main_inventory, mk:hotbar, etc.). Do NOT use clearRegions()
        // here — that would also destroy dynamic regions (e.g., peek containers)
        // that were registered on this menu by showPanel/registerDynamicRegion.
        for (String regionName : panelRanges.keySet()) {
            MKRegionRegistry.removeDynamicRegion(creativeMenu, regionName);
        }

        // Create MKRegion objects directly with the correct ItemPickerMenu indices.
        for (var entry : panelRanges.entrySet()) {
            String panelName = entry.getKey();
            int[] range = entry.getValue();
            int slotCount = range[1] - range[0] + 1;
            int containerStart = range[2];

            // Get container type from the inventoryMenu's existing region
            MKRegion existingRegion = MKRegionRegistry.getRegion(player.inventoryMenu, panelName);
            MKContainerType containerType = existingRegion != null
                    ? existingRegion.containerType() : MKContainerType.SIMPLE;

            // Get the backing container from the first slot in the range
            net.minecraft.world.Container container = creativeMenu.slots.get(range[0]).container;

            // Create the region with correct ItemPickerMenu slot indices
            MKRegion region = new MKRegion(panelName, container, containerStart, slotCount,
                    MKContainerDef.Persistence.PERSISTENT, true, true, containerType);
            region.setMenuSlotRange(range[0], range[1]);

            MKRegionRegistry.addRegion(creativeMenu, region);
        }

        // Create overlay panels for any new regions (sort/move buttons)
        onMenuResolved(creativeMenu);
    }

    /**
     * Called when the creative screen closes. Cleans up regions resolved on
     * the ItemPickerMenu so they don't persist into future menu cycles.
     */
    public static void onCreativeScreenClosed() {
        // Position cache invalidation — survival inventory will recompute
        invalidateResolvedPositions();
    }

    /**
     * Returns a map of slot positions keyed by "panelName:slotIndex".
     * Used by {@link #repositionSlots} for identity-based slot matching
     * instead of fragile sequential matching.
     *
     * <p>Panels that don't apply to the context are omitted entirely from the map.
     * Their slots' {@code isActive()} flag prevents all interaction. Slots scrolled
     * outside a scroll viewport are mapped to offscreen positions so vanilla doesn't
     * render them.
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
                // Panels that don't apply or are hidden are omitted entirely.
                // Their slots' isActive() flag prevents all interaction, so no
                // position entry is needed. repositionSlots() skips slots with
                // no map entry.
                continue;
            }

            int[] pos = getResolvedPosition(def, context);
            MKLayoutResult layout = def.computeFlowPositions();
            int ep = def.effectivePadding();

            // Collect scroll regions for this panel (if any) so we can apply
            // scroll offsets to slot positions inside scroll containers.
            // Sorted smallest-first so nested inner regions match before parents.
            List<MKGroupDef.ScrollRegion> scrollRegions = null;
            if (def.rootGroup() != null) {
                scrollRegions = new ArrayList<>();
                def.rootGroup().collectScrollRegions(0, 0, def.name(), scrollRegions);
                sortScrollRegionsSmallestFirst(scrollRegions);
            }

            for (int i = 0; i < def.slotDefs().size(); i++) {
                // Skip inactive slots — omit from map entirely.
                // repositionSlots() skips missing entries, and isActive() prevents interaction.
                if (!layout.isActive(i)) continue;

                int slotX = pos[0] + ep + layout.x(i);
                int slotY = pos[1] + ep + layout.y(i);

                // Check if this slot is inside a scroll container and apply offset.
                // The slot's flowPos is in content space (full layout, no scroll).
                // We need to translate by the scroll offset and hide slots outside
                // the viewport so vanilla doesn't render them.
                if (scrollRegions != null) {
                    for (MKGroupDef.ScrollRegion sr : scrollRegions) {
                        // Check if the slot's content-relative position falls within
                        // this scroll region's content area (viewport origin + content extent)
                        int contentRelX = layout.x(i) - sr.viewportX();
                        int contentRelY = layout.y(i) - sr.viewportY();

                        // Slot is inside this scroll region's content area if its
                        // top-left corner is within the content bounds
                        if (contentRelX >= 0 && contentRelX < sr.contentWidth()
                                && contentRelY >= 0 && contentRelY < sr.contentHeight()) {

                            // Apply scroll offset (scroll offset is positive = scrolled down,
                            // so we subtract it from the position to move content upward)
                            MKPanelState state = MKPanelStateRegistry.get(def.name());
                            float[] scrollOffset = state != null
                                    ? state.getScrollOffset(sr.id())
                                    : new float[]{0f, 0f};

                            int scrolledX = slotX - (int) scrollOffset[0];
                            int scrolledY = slotY - (int) scrollOffset[1];

                            // Check if the scrolled slot is within the viewport.
                            // The viewport in container-relative coords:
                            int vpLeft = pos[0] + ep + sr.viewportX();
                            int vpTop = pos[1] + ep + sr.viewportY();
                            int vpRight = vpLeft + sr.viewportWidth();
                            int vpBottom = vpTop + sr.viewportHeight();

                            // Slot is 18x18 (with the +1 offset baked in, content is 16x16).
                            // Check if any part of the slot is within the viewport.
                            if (scrolledX + 17 < vpLeft || scrolledX > vpRight
                                    || scrolledY + 17 < vpTop || scrolledY > vpBottom) {
                                // Entirely outside viewport -- move offscreen
                                slotX = SCROLL_OFFSCREEN;
                                slotY = SCROLL_OFFSCREEN;
                            } else {
                                // Partially or fully inside -- use scrolled position
                                slotX = scrolledX;
                                slotY = scrolledY;
                            }
                            break; // Found the scroll region for this slot
                        }
                    }
                }

                map.put(def.name() + ":" + i, new int[]{slotX, slotY});
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

            // Create buttons from the tree-derived list, which includes
            // conditionally-injected buttons (not just the original flat list).
            List<MKButtonDef> treeButtonDefs = def.getTreeButtonDefs();
            int treeSlotCount = def.getTreeSlotCount();
            List<MKButton> panelButtons = new ArrayList<>();
            MKLayoutResult btnLayout = def.computeFlowPositions();
            for (int bi = 0; bi < treeButtonDefs.size(); bi++) {
                MKButtonDef btnDef = treeButtonDefs.get(bi);
                int fi = treeSlotCount + bi;
                MKButton btn = btnDef.createButtonAt(
                        pos[0], pos[1], def.effectivePadding(),
                        leftPos, topPos,
                        btnLayout.x(fi), btnLayout.y(fi),
                        activeGroups);
                btn.panelName = def.name();
                btn.buttonIndex = bi;
                // Set event context for bus dispatch
                btn.eventContext = context;
                btn.eventPlayer = net.minecraft.client.Minecraft.getInstance().player;
                panelButtons.add(btn);
                buttons.add(btn);

                // Hide buttons belonging to hidden/disabled panels, disabled by flow,
                // or hidden by element-level visibility override
                if (isPanelInactive(def.name()) || !btnLayout.isActive(fi)) {
                    btn.visible = false;
                } else if (btn.elementId != null) {
                    MKPanelState pState = MKPanelStateRegistry.get(def.name());
                    if (pState != null) {
                        Boolean override = pState.getVisible(btn.elementId);
                        if (override != null && !override) btn.visible = false;
                    }
                }
            }

            // Compute live panel size from BOTH slots and buttons.
            // Panel background now renders before widgets in the pipeline
            // (at RETURN of renderBackground), so buttons are inside the panel.
            if (def.autoSize()) {
                int maxRight = 0;
                int maxBottom = 0;

                // Slots are 18x18 — use flow positions (tree-derived count)
                for (int si = 0; si < treeSlotCount; si++) {
                    if (!btnLayout.isActive(si)) continue;
                    maxRight = Math.max(maxRight, btnLayout.x(si) + 18);
                    maxBottom = Math.max(maxBottom, btnLayout.y(si) + 18);
                }

                // Include buttons (use live widget dimensions + flow positions)
                for (int bi = 0; bi < panelButtons.size(); bi++) {
                    MKButton btn = panelButtons.get(bi);
                    if (!btn.visible) continue;
                    int fi = treeSlotCount + bi;
                    if (!btnLayout.isActive(fi)) continue;
                    maxRight = Math.max(maxRight, btnLayout.x(fi) + btn.getWidth());
                    maxBottom = Math.max(maxBottom, btnLayout.y(fi) + btn.getHeight());
                }

                // Include text elements (tree-derived counts for offset)
                int treeButtonCount = treeButtonDefs.size();
                for (int ti = 0; ti < def.textDefs().size(); ti++) {
                    int fi = treeSlotCount + treeButtonCount + ti;
                    if (!btnLayout.isActive(fi)) continue;
                    MKTextDef textDef = def.textDefs().get(ti);
                    maxRight = Math.max(maxRight, btnLayout.x(fi) + textDef.layoutWidth());
                    maxBottom = Math.max(maxBottom, btnLayout.y(fi) + textDef.layoutHeight());
                }

                // Use tree-computed content dimensions as a floor — the layout
                // already accounts for group children (e.g. vertical text) that
                // aren't in the flat slot/button/text lists.
                maxRight = Math.max(maxRight, btnLayout.contentWidth());
                maxBottom = Math.max(maxBottom, btnLayout.contentHeight());

                livePanelSizes.put(def.name(), new int[]{
                        maxRight + def.effectivePadding() * 2,
                        maxBottom + def.effectivePadding() * 2
                });
            }
        }

        return buttons;
    }

    /**
     * Creates MKButton widgets for panels that were registered AFTER the
     * screen's {@code init()} ran. This handles dynamically-registered panels
     * (e.g., sort/move-matching buttons created in response to REGION_POPULATED
     * events from peek containers) that missed the initial button creation pass.
     *
     * <p>Checks each panel with buttons against the screen's current children.
     * If a panel has button definitions but no matching MKButton widgets exist
     * on the screen, creates and returns them. The caller (MKScreenMixin) adds
     * them via {@code addRenderableWidget}.
     *
     * <p>Called every frame from the render pipeline, but only creates buttons
     * once per panel (subsequent frames find existing widgets and return empty).
     *
     * @param screen  the active container screen
     * @param context the active MKContext
     * @param leftPos screen-space left offset
     * @param topPos  screen-space top offset
     * @return list of newly-created MKButton widgets to add to the screen
     */
    public static List<MKButton> createMissingButtons(
            AbstractContainerScreen<?> screen, MKContext context,
            int leftPos, int topPos) {
        List<MKButton> newButtons = new ArrayList<>();

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;

            // Use tree-derived button defs to capture conditionally-injected buttons
            List<MKButtonDef> treeButtonDefs = def.getTreeButtonDefs();
            if (treeButtonDefs.isEmpty()) continue;

            // Check if any MKButton for this panel already exists on the screen.
            // If so, skip — buttons were already created during init() or a
            // previous frame's createMissingButtons call.
            boolean hasExistingButton = false;
            for (var child : screen.children()) {
                if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                        && mkBtn.panelName.equals(def.name())) {
                    hasExistingButton = true;
                    break;
                }
            }
            if (hasExistingButton) continue;

            // No buttons exist for this panel — create them now.
            // Use a placeholder position; updateButtonPositions() will set the
            // correct position on this same frame (it runs right after this).
            int treeSlotCount = def.getTreeSlotCount();
            int[] pos = def.resolvePosition(context);
            MKLayoutResult newBtnLayout = def.computeFlowPositions();
            for (int bi = 0; bi < treeButtonDefs.size(); bi++) {
                MKButtonDef btnDef = treeButtonDefs.get(bi);
                int fi = treeSlotCount + bi;
                MKButton btn = btnDef.createButtonAt(
                        pos[0], pos[1], def.effectivePadding(),
                        leftPos, topPos,
                        newBtnLayout.x(fi), newBtnLayout.y(fi),
                        activeGroups);
                btn.panelName = def.name();
                btn.buttonIndex = bi;
                btn.eventContext = context;
                btn.eventPlayer = net.minecraft.client.Minecraft.getInstance().player;
                newButtons.add(btn);

                // Start hidden if panel is inactive, disabled by flow,
                // or hidden by element-level visibility override
                if (isPanelInactive(def.name()) || !newBtnLayout.isActive(fi)) {
                    btn.visible = false;
                } else if (btn.elementId != null) {
                    MKPanelState pState = MKPanelStateRegistry.get(def.name());
                    if (pState != null) {
                        Boolean override = pState.getVisible(btn.elementId);
                        if (override != null && !override) btn.visible = false;
                    }
                }
            }

            LOGGER.debug("[MenuKit] Created {} missing button(s) for dynamically-registered panel '{}'",
                    treeButtonDefs.size(), def.name());
        }

        return newButtons;
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

        // Reset scroll/tab region tracking for this frame
        scrollRegionsByPanel.clear();
        tabsRegionsByPanel.clear();

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;

            // Skip suppressed panels (exclusive-panel logic or missing region)
            if (isPanelSuppressed(def.name())) continue;

            // Use collision-avoided position
            int[] pos = getResolvedPosition(def, context);

            int panelX = offsetX + pos[0];
            int panelY = offsetY + pos[1];
            // Use livePanelSizes when available (has real widget dimensions from screen init),
            // fall back to computeSize() (uses estimated button sizes but handles disabledWhen)
            int[] size = livePanelSizes.containsKey(def.name())
                    ? livePanelSizes.get(def.name())
                    : def.computeSize();

            // Don't render the panel if all elements are disabled (size is 0x0)
            if (size[0] <= 0 || size[1] <= 0) continue;

            MKPanel.renderPanel(graphics, panelX, panelY,
                    size[0], size[1], def.style(), def.customSprite());

            // Render text labels
            if (!def.textDefs().isEmpty()) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                MKLayoutResult textLayout = def.computeFlowPositions();
                int ep = def.effectivePadding();
                // Text indices follow slots + buttons in the layout — use tree-derived
                // counts so conditionally-injected buttons don't shift text positions
                int textOffset = def.getTreeSlotCount() + def.getTreeButtonDefs().size();
                for (int i = 0; i < def.textDefs().size(); i++) {
                    int fi = textOffset + i;
                    if (!textLayout.isActive(fi)) continue;
                    MKTextDef textDef = def.textDefs().get(i);
                    net.minecraft.network.chat.Component text = textDef.content().get();
                    if (text != null) {
                        int textX = panelX + ep + textLayout.x(fi);
                        int textY = panelY + ep + textLayout.y(fi);
                        if (textDef.vertical()) {
                            // Rotate -90° for bottom-to-top reading.
                            // Translate so the text origin lands at (textX, textY + textWidth),
                            // then rotate -90° so the string reads upward from that point.
                            int textWidth = mc.font.width(text);
                            var pose = graphics.pose();
                            pose.pushMatrix();
                            pose.translate(textX, textY + textWidth);
                            pose.rotate((float) Math.toRadians(-90));
                            // After rotation, draw at (0,0) in rotated space
                            graphics.drawString(mc.font, text, 0, 0,
                                    textDef.color(), textDef.shadow());
                            pose.popMatrix();
                        } else {
                            graphics.drawString(mc.font, text, textX, textY,
                                    textDef.color(), textDef.shadow());
                        }
                    }
                }
            }

            // ── Collect scroll and tab regions from the layout tree ──────────
            // Walk the root group (if present) to find scroll/tab containers.
            // Their viewport/bar bounds are stored for use by renderSlotBackgrounds
            // (scissor clipping) and input handling (mouse wheel, tab clicks).
            int ep = def.effectivePadding();
            if (def.rootGroup() != null) {
                // Collect scroll regions, sorted smallest-first so nested
                // inner regions match before their enclosing parents.
                List<MKGroupDef.ScrollRegion> scrollRegions = new ArrayList<>();
                def.rootGroup().collectScrollRegions(0, 0, def.name(), scrollRegions);
                if (!scrollRegions.isEmpty()) {
                    sortScrollRegionsSmallestFirst(scrollRegions);
                    scrollRegionsByPanel.put(def.name(), scrollRegions);

                    // Render scrollbar indicators for each scroll region
                    for (MKGroupDef.ScrollRegion sr : scrollRegions) {
                        if (!sr.scrollDef().showScrollbar()) continue;

                        // Absolute screen-space position of the viewport
                        int vpX = panelX + ep + sr.viewportX();
                        int vpY = panelY + ep + sr.viewportY();

                        // Get current scroll offset
                        MKPanelState state = MKPanelStateRegistry.get(def.name());
                        float[] offset = state != null
                                ? state.getScrollOffset(sr.id())
                                : new float[]{0f, 0f};

                        renderScrollbar(graphics, sr, vpX, vpY, offset);
                    }
                }

                // Collect tab regions
                List<MKGroupDef.TabsRegion> tabsRegions = new ArrayList<>();
                def.rootGroup().collectTabsRegions(0, 0, def.name(), tabsRegions);
                if (!tabsRegions.isEmpty()) {
                    tabsRegionsByPanel.put(def.name(), tabsRegions);

                    // Render tab bars
                    for (MKGroupDef.TabsRegion tr : tabsRegions) {
                        int tabsX = panelX + ep + tr.x();
                        int tabsY = panelY + ep + tr.y();

                        MKPanelState state = MKPanelStateRegistry.get(def.name());
                        int activeTab = state != null
                                ? state.getActiveTab(tr.id())
                                : tr.tabsDef().defaultTab();
                        activeTab = Math.max(0, Math.min(activeTab,
                                tr.tabsDef().tabs().size() - 1));

                        renderTabBar(graphics, tr, tabsX, tabsY, activeTab, mouseX, mouseY);
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

    // ── Scrollbar Rendering ──────────────────────────────────────────────────
    //
    // Renders a subtle scrollbar indicator inside the scroll viewport.
    // The scrollbar is 3px wide, positioned at the right edge (vertical)
    // or bottom edge (horizontal) of the viewport. Uses vanilla-style
    // colors: dark track, lighter handle.

    /** Scrollbar track color — dark gray, semi-transparent. */
    private static final int SCROLLBAR_TRACK  = 0x40000000;
    /** Scrollbar handle color — medium gray, opaque. */
    private static final int SCROLLBAR_HANDLE = 0xC0808080;
    /** Scrollbar handle hover color — lighter gray. */
    private static final int SCROLLBAR_HANDLE_HOVER = 0xC0A0A0A0;
    /** Scrollbar width in pixels. */
    private static final int SCROLLBAR_WIDTH = 3;

    /**
     * Renders a scrollbar indicator for a scroll region.
     *
     * @param graphics the graphics context
     * @param sr       the scroll region metadata
     * @param vpX      viewport left edge in screen space
     * @param vpY      viewport top edge in screen space
     * @param offset   current scroll offset [scrollX, scrollY]
     */
    private static void renderScrollbar(GuiGraphics graphics,
                                         MKGroupDef.ScrollRegion sr,
                                         int vpX, int vpY,
                                         float[] offset) {
        // ── Vertical scrollbar (right edge) ──────────────────────────────
        if (sr.scrollDef().verticalScroll() && sr.contentHeight() > sr.viewportHeight()) {
            int trackX = vpX + sr.viewportWidth() - SCROLLBAR_WIDTH;
            int trackY = vpY;
            int trackH = sr.viewportHeight();

            // Draw track background
            graphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH,
                    trackY + trackH, SCROLLBAR_TRACK);

            // Compute handle position and size
            // Handle size is proportional to viewport/content ratio
            float ratio = (float) sr.viewportHeight() / sr.contentHeight();
            int handleH = Math.max(8, (int) (trackH * ratio));

            // Handle position is proportional to scroll offset
            float maxScroll = sr.contentHeight() - sr.viewportHeight();
            float scrollFraction = maxScroll > 0 ? Math.abs(offset[1]) / maxScroll : 0;
            int handleY = trackY + (int) ((trackH - handleH) * scrollFraction);

            // Draw handle
            graphics.fill(trackX, handleY, trackX + SCROLLBAR_WIDTH,
                    handleY + handleH, SCROLLBAR_HANDLE);
        }

        // ── Horizontal scrollbar (bottom edge) ──────────────────────────
        if (sr.scrollDef().horizontalScroll() && sr.contentWidth() > sr.viewportWidth()) {
            int trackX = vpX;
            int trackY = vpY + sr.viewportHeight() - SCROLLBAR_WIDTH;
            int trackW = sr.viewportWidth();

            // Draw track background
            graphics.fill(trackX, trackY, trackX + trackW,
                    trackY + SCROLLBAR_WIDTH, SCROLLBAR_TRACK);

            // Compute handle position and size
            float ratio = (float) sr.viewportWidth() / sr.contentWidth();
            int handleW = Math.max(8, (int) (trackW * ratio));

            float maxScroll = sr.contentWidth() - sr.viewportWidth();
            float scrollFraction = maxScroll > 0 ? Math.abs(offset[0]) / maxScroll : 0;
            int handleX = trackX + (int) ((trackW - handleW) * scrollFraction);

            // Draw handle
            graphics.fill(handleX, trackY, handleX + handleW,
                    trackY + SCROLLBAR_WIDTH, SCROLLBAR_HANDLE);
        }
    }

    // ── Tab Bar Rendering ────────────────────────────────────────────────────
    //
    // Renders tab buttons in a horizontal or vertical bar. Active tab is
    // visually raised and connected to the content area. Inactive tabs
    // are flat/inset.
    //
    // Tab bar layout:
    //   TOP:    tab buttons above content, horizontal row
    //   BOTTOM: tab buttons below content, horizontal row
    //   LEFT:   tab buttons left of content, vertical column
    //   RIGHT:  tab buttons right of content, vertical column

    /** Tab button active background — lighter, matches panel raised style. */
    private static final int TAB_ACTIVE_BG   = 0xFFC6C6C6;
    /** Tab button inactive background — darker, inset look. */
    private static final int TAB_INACTIVE_BG = 0xFF8B8B8B;
    /** Tab button text color — dark for readability. */
    private static final int TAB_TEXT_COLOR   = 0xFF404040;
    /** Tab button active text color — slightly darker for emphasis. */
    private static final int TAB_ACTIVE_TEXT  = 0xFF303030;
    /** Tab border color — matches vanilla dark border. */
    private static final int TAB_BORDER      = 0xFF000000;

    /**
     * Renders the tab bar for a tabs container.
     *
     * @param graphics  the graphics context
     * @param tr        the tabs region metadata
     * @param tabsX     tabs container left edge in screen space
     * @param tabsY     tabs container top edge in screen space
     * @param activeTab the currently active tab index
     * @param mouseX    screen-space mouse X (for hover highlighting)
     * @param mouseY    screen-space mouse Y (for hover highlighting)
     */
    private static void renderTabBar(GuiGraphics graphics,
                                      MKGroupDef.TabsRegion tr,
                                      int tabsX, int tabsY,
                                      int activeTab,
                                      int mouseX, int mouseY) {
        MKTabsDef tabsDef = tr.tabsDef();
        List<MKTabDef> tabs = tabsDef.tabs();
        if (tabs.isEmpty()) return;

        // Skip rendering the bar when there's only one tab -- no switching needed,
        // and it avoids visual clutter for single-content containers.
        if (tabs.size() == 1) return;

        // Compute tab button bounds via the shared method (single source of truth
        // shared with handleTabClick so render and click positions never drift).
        List<TabButtonBounds> allBounds = computeTabButtonBounds(tr, tabsX, tabsY);

        for (TabButtonBounds tbb : allBounds) {
            int i = tbb.index();
            MKTabDef tab = tabs.get(i);
            boolean isActive = (i == activeTab);

            int tabBtnX = tbb.x();
            int tabBtnY = tbb.y();
            int tabBtnW = tbb.w();
            int tabBtnH = tbb.h();

            // Check hover
            boolean hovered = mouseX >= tabBtnX && mouseX < tabBtnX + tabBtnW
                    && mouseY >= tabBtnY && mouseY < tabBtnY + tabBtnH;

            // ── Draw tab button background ──────────────────────────────
            if (isActive) {
                // Active tab — raised look, visually connected to content
                int bg = TAB_ACTIVE_BG;
                graphics.fill(tabBtnX, tabBtnY, tabBtnX + tabBtnW, tabBtnY + tabBtnH, bg);

                // Draw border on 3 sides (the side touching content is open)
                // to create the visual connection effect
                switch (tabsDef.barPosition()) {
                    case TOP -> {
                        graphics.fill(tabBtnX, tabBtnY, tabBtnX + tabBtnW, tabBtnY + 1, TAB_BORDER);         // top
                        graphics.fill(tabBtnX, tabBtnY, tabBtnX + 1, tabBtnY + tabBtnH, TAB_BORDER);         // left
                        graphics.fill(tabBtnX + tabBtnW - 1, tabBtnY, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // right
                        // bottom is open (connects to content)
                    }
                    case BOTTOM -> {
                        graphics.fill(tabBtnX, tabBtnY + tabBtnH - 1, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // bottom
                        graphics.fill(tabBtnX, tabBtnY, tabBtnX + 1, tabBtnY + tabBtnH, TAB_BORDER);         // left
                        graphics.fill(tabBtnX + tabBtnW - 1, tabBtnY, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // right
                        // top is open
                    }
                    case LEFT -> {
                        graphics.fill(tabBtnX, tabBtnY, tabBtnX + tabBtnW, tabBtnY + 1, TAB_BORDER);         // top
                        graphics.fill(tabBtnX, tabBtnY + tabBtnH - 1, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // bottom
                        graphics.fill(tabBtnX, tabBtnY, tabBtnX + 1, tabBtnY + tabBtnH, TAB_BORDER);         // left
                        // right is open
                    }
                    case RIGHT -> {
                        graphics.fill(tabBtnX, tabBtnY, tabBtnX + tabBtnW, tabBtnY + 1, TAB_BORDER);         // top
                        graphics.fill(tabBtnX, tabBtnY + tabBtnH - 1, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // bottom
                        graphics.fill(tabBtnX + tabBtnW - 1, tabBtnY, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // right
                        // left is open
                    }
                }
            } else {
                // Inactive tab — flat/inset look
                int bg = hovered ? 0xFFA0A0A0 : TAB_INACTIVE_BG;
                graphics.fill(tabBtnX, tabBtnY, tabBtnX + tabBtnW, tabBtnY + tabBtnH, bg);

                // Full border on all 4 sides
                graphics.fill(tabBtnX, tabBtnY, tabBtnX + tabBtnW, tabBtnY + 1, TAB_BORDER);                 // top
                graphics.fill(tabBtnX, tabBtnY + tabBtnH - 1, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // bottom
                graphics.fill(tabBtnX, tabBtnY, tabBtnX + 1, tabBtnY + tabBtnH, TAB_BORDER);                 // left
                graphics.fill(tabBtnX + tabBtnW - 1, tabBtnY, tabBtnX + tabBtnW, tabBtnY + tabBtnH, TAB_BORDER); // right
            }

            // ── Draw tab label text ─────────────────────────────────────
            if (tab.label() != null) {
                int textColor = isActive ? TAB_ACTIVE_TEXT : TAB_TEXT_COLOR;
                int textX = tabBtnX + 4;
                int textY = tabBtnY + (tabBtnH - 8) / 2; // vertically centered (font height ~8)
                graphics.drawString(net.minecraft.client.Minecraft.getInstance().font, tab.label(), textX, textY, textColor, false);
            }

            // ── Draw tab icon (if present, before label) ────────────────
            if (tab.icon() != null) {
                int iconSz = tab.iconSize() > 0 ? tab.iconSize() : 12;
                int iconX = tabBtnX + 2;
                int iconY = tabBtnY + (tabBtnH - iconSz) / 2;
                graphics.blitSprite(
                        net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                        tab.icon(), iconX, iconY, iconSz, iconSz);
            }
        }
    }

    /**
     * Estimates the pixel width of a tab label for layout purposes.
     * Uses the font's string width when available, falls back to char count.
     */
    private static int estimateTabLabelWidth(MKTabDef tab,
                                              net.minecraft.client.Minecraft mc) {
        int width = 0;
        if (tab.icon() != null) {
            width += (tab.iconSize() > 0 ? tab.iconSize() : 12) + 2;
        }
        if (tab.label() != null) {
            width += mc.font.width(tab.label());
        }
        return Math.max(width, 12); // minimum tab width
    }

    /**
     * Bounds of a single tab button within a tab bar, used by both rendering
     * and click handling to ensure positions stay in sync.
     */
    record TabButtonBounds(int index, int x, int y, int w, int h) {}

    /**
     * Computes the screen-space bounds for every tab button in a tabs region.
     * Both {@link #renderTabBar} and {@link #handleTabClick} use this single
     * source of truth so tab positions never drift between render and input.
     *
     * @param tr    the tabs region metadata
     * @param tabsX tabs container left edge in screen space
     * @param tabsY tabs container top edge in screen space
     * @return list of tab button bounds, one per tab
     */
    private static List<TabButtonBounds> computeTabButtonBounds(
            MKGroupDef.TabsRegion tr, int tabsX, int tabsY) {
        MKTabsDef tabsDef = tr.tabsDef();
        List<MKTabDef> tabs = tabsDef.tabs();
        var mc = net.minecraft.client.Minecraft.getInstance();

        boolean isHorizontal = tabsDef.barPosition() == MKTabsDef.TabBarPosition.TOP
                || tabsDef.barPosition() == MKTabsDef.TabBarPosition.BOTTOM;
        int barThickness = tabsDef.barThickness();
        int tabGap = tabsDef.tabGap();

        List<TabButtonBounds> bounds = new ArrayList<>(tabs.size());
        int cursor = 0;

        for (int i = 0; i < tabs.size(); i++) {
            MKTabDef tab = tabs.get(i);

            int tabBtnX, tabBtnY, tabBtnW, tabBtnH;
            if (isHorizontal) {
                // Horizontal tab bar -- buttons laid out left-to-right
                int labelWidth = estimateTabLabelWidth(tab, mc);
                tabBtnW = labelWidth + 8; // 4px padding each side
                tabBtnH = barThickness;

                if (tabsDef.barPosition() == MKTabsDef.TabBarPosition.TOP) {
                    tabBtnX = tabsX + cursor;
                    tabBtnY = tabsY;
                } else {
                    // BOTTOM -- bar is below the content
                    tabBtnX = tabsX + cursor;
                    tabBtnY = tabsY + tr.totalHeight() - barThickness;
                }

                cursor += tabBtnW + tabGap;
            } else {
                // Vertical tab bar -- buttons laid out top-to-bottom
                tabBtnW = barThickness;
                tabBtnH = 16; // Fixed height for vertical tab buttons

                if (tabsDef.barPosition() == MKTabsDef.TabBarPosition.LEFT) {
                    tabBtnX = tabsX;
                    tabBtnY = tabsY + cursor;
                } else {
                    // RIGHT -- bar is right of the content
                    tabBtnX = tabsX + tr.totalWidth() - barThickness;
                    tabBtnY = tabsY + cursor;
                }

                cursor += tabBtnH + tabGap;
            }

            bounds.add(new TabButtonBounds(i, tabBtnX, tabBtnY, tabBtnW, tabBtnH));
        }

        return bounds;
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
            int ep = def.effectivePadding();

            // ── Scroll region scissor clipping ──────────────────────────────
            // If this panel has scroll regions, we need to apply scissor clipping
            // around viewport bounds so slot backgrounds at the viewport edges
            // are cleanly clipped. We enable scissor for each scroll region's
            // viewport, render the slots inside that region, then disable scissor.
            //
            // Slots OUTSIDE scroll regions render normally (no scissor).
            // Slots INSIDE scroll regions use the scrolled position (already
            // applied by getSlotPositionMap -> repositionSlots).
            List<MKGroupDef.ScrollRegion> scrollRegions = scrollRegionsByPanel.get(def.name());

            // Render slot backgrounds (18x18 inset squares) using flow positions.
            // For scroll containers, the live slot positions already include scroll
            // offset (set by getSlotPositionMap). We use the live slot position for
            // rendering to stay consistent with vanilla's item rendering.
            MKLayoutResult slotLayout = def.computeFlowPositions();
            for (int slotIdx = 0; slotIdx < def.slotDefs().size(); slotIdx++) {
                MKSlotDef slotDef = def.slotDefs().get(slotIdx);
                // Skip inactive slots
                if (!slotLayout.isActive(slotIdx)) continue;

                // Find the live MKSlot from the menu by matching the scroll-adjusted
                // position. getSlotPositionMap already applied scroll offsets, so
                // the live slot's x/y reflects the scrolled position.
                int origSlotX = panelX + ep + slotLayout.x(slotIdx);
                int origSlotY = panelY + ep + slotLayout.y(slotIdx);

                // Determine if this slot is in a scroll region and compute its
                // actual render position (which may differ from the flow position
                // due to scroll offset).
                int slotX = origSlotX;
                int slotY = origSlotY;
                MKGroupDef.ScrollRegion containingScroll = null;

                if (scrollRegions != null) {
                    for (MKGroupDef.ScrollRegion sr : scrollRegions) {
                        int contentRelX = slotLayout.x(slotIdx) - sr.viewportX();
                        int contentRelY = slotLayout.y(slotIdx) - sr.viewportY();
                        if (contentRelX >= 0 && contentRelX < sr.contentWidth()
                                && contentRelY >= 0 && contentRelY < sr.contentHeight()) {
                            containingScroll = sr;

                            // Apply scroll offset to get the rendered position
                            MKPanelState state = MKPanelStateRegistry.get(def.name());
                            float[] scrollOffset = state != null
                                    ? state.getScrollOffset(sr.id())
                                    : new float[]{0f, 0f};
                            slotX = origSlotX - (int) scrollOffset[0];
                            slotY = origSlotY - (int) scrollOffset[1];
                            break;
                        }
                    }
                }

                // If slot is inside a scroll region but moved offscreen, skip it
                if (containingScroll != null) {
                    int vpLeft = panelX + ep + containingScroll.viewportX();
                    int vpTop = panelY + ep + containingScroll.viewportY();
                    int vpRight = vpLeft + containingScroll.viewportWidth();
                    int vpBottom = vpTop + containingScroll.viewportHeight();

                    // Entirely outside viewport -- skip rendering
                    if (slotX + 17 < vpLeft || slotX > vpRight
                            || slotY + 17 < vpTop || slotY > vpBottom) {
                        continue;
                    }

                    // Enable scissor clipping for partially visible slots.
                    // GuiGraphics.enableScissor takes screen-space coordinates,
                    // but we're in container-translated space. Convert by adding
                    // the leftPos/topPos offset that the caller subtracted.
                    // Note: renderSlotBackgrounds is called with offsetX=0, offsetY=0
                    // in container-translated space. The scissor coords need to be
                    // in the same translated coordinate system.
                    graphics.enableScissor(vpLeft, vpTop, vpRight, vpBottom);
                }

                // Render the slot background at the (possibly scrolled) position
                MKPanel.renderSlotBackground(graphics, slotX - 1, slotY - 1);

                // Find the live MKSlot by its scrolled position (set by repositionSlots)
                net.minecraft.world.inventory.Slot liveMKSlot = findLiveMKSlot(menu, slotX, slotY);

                // Render hover highlight (bright white overlay) when mouse is over this slot.
                // Vanilla's highlight sprites don't render well on MKSlots outside the
                // container, so we draw our own to match vanilla's visual brightness.
                // For scroll containers, also check that the mouse is within the viewport.
                boolean isHovered = relMouseX >= slotX - 1 && relMouseX < slotX + 17
                        && relMouseY >= slotY - 1 && relMouseY < slotY + 17;
                if (isHovered && containingScroll != null) {
                    // Verify mouse is within viewport bounds (interaction clipping)
                    int vpLeft = panelX + ep + containingScroll.viewportX();
                    int vpTop = panelY + ep + containingScroll.viewportY();
                    int vpRight = vpLeft + containingScroll.viewportWidth();
                    int vpBottom = vpTop + containingScroll.viewportHeight();
                    isHovered = relMouseX >= vpLeft && relMouseX < vpRight
                            && relMouseY >= vpTop && relMouseY < vpBottom;
                }
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
                        // Fill the 16x16 item area with the tint color.
                        // Alpha channel controls transparency — 0x40 = 25%.
                        graphics.fill(slotX, slotY, slotX + 16, slotY + 16,
                                dState.getBackgroundTint());
                    }

                    // Lock and sort-lock overlays are rendered by the universal
                    // renderLockOverlays() pass (called after renderSlotOverlays),
                    // which covers ALL menu slots — both panel and vanilla.
                }

                // Disable scissor if we enabled it for this slot
                if (containingScroll != null) {
                    graphics.disableScissor();
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
            int ep = def.effectivePadding();

            // Get scroll regions for scroll-offset-aware position lookup
            List<MKGroupDef.ScrollRegion> scrollRegions = scrollRegionsByPanel.get(def.name());

            MKLayoutResult tipLayout = def.computeFlowPositions();
            for (int slotIdx = 0; slotIdx < def.slotDefs().size(); slotIdx++) {
                // Skip inactive slots
                if (!tipLayout.isActive(slotIdx)) continue;

                int slotX = panelX + ep + tipLayout.x(slotIdx);
                int slotY = panelY + ep + tipLayout.y(slotIdx);

                // Apply scroll offset for slots inside scroll containers
                // (matches the adjustment in renderSlotBackgrounds and getSlotPositionMap)
                MKGroupDef.ScrollRegion containingScroll = null;
                if (scrollRegions != null) {
                    for (MKGroupDef.ScrollRegion sr : scrollRegions) {
                        int contentRelX = tipLayout.x(slotIdx) - sr.viewportX();
                        int contentRelY = tipLayout.y(slotIdx) - sr.viewportY();
                        if (contentRelX >= 0 && contentRelX < sr.contentWidth()
                                && contentRelY >= 0 && contentRelY < sr.contentHeight()) {
                            containingScroll = sr;
                            MKPanelState state = MKPanelStateRegistry.get(def.name());
                            float[] scrollOffset = state != null
                                    ? state.getScrollOffset(sr.id())
                                    : new float[]{0f, 0f};
                            slotX -= (int) scrollOffset[0];
                            slotY -= (int) scrollOffset[1];
                            break;
                        }
                    }
                }

                // Skip slots scrolled outside the viewport
                if (containingScroll != null) {
                    int vpLeft = panelX + ep + containingScroll.viewportX();
                    int vpTop = panelY + ep + containingScroll.viewportY();
                    int vpRight = vpLeft + containingScroll.viewportWidth();
                    int vpBottom = vpTop + containingScroll.viewportHeight();
                    if (slotX + 17 < vpLeft || slotX > vpRight
                            || slotY + 17 < vpTop || slotY > vpBottom) {
                        continue;
                    }
                }

                // Find the live slot and its state (using scrolled position)
                net.minecraft.world.inventory.Slot liveMKSlot = findLiveMKSlot(menu, slotX, slotY);
                if (liveMKSlot == null) continue;

                MKSlotState dState = MKSlotStateRegistry.get(liveMKSlot);
                // Fast gate: skip slots with no decorations (most slots)
                if (dState == null || !dState.hasDecoration()) continue;

                // Enable scissor if inside a scroll container
                if (containingScroll != null) {
                    int vpLeft = panelX + ep + containingScroll.viewportX();
                    int vpTop = panelY + ep + containingScroll.viewportY();
                    graphics.enableScissor(vpLeft, vpTop,
                            vpLeft + containingScroll.viewportWidth(),
                            vpTop + containingScroll.viewportHeight());
                }

                // ── Overlay Icon (renders ON TOP of the item) ──────────────
                // Draws a 16x16 sprite at the slot position, above the item.
                // Common use: lock icon, warning indicator, status badge.
                Identifier overlay = dState.getOverlayIcon();
                if (overlay != null) {
                    graphics.blitSprite(
                            net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                            overlay, slotX, slotY, 16, 16);
                }

                // ── Border (renders ON TOP of the item) ────────────────────
                // Four 1px fills forming a rectangle around the 16x16 slot area.
                // The border sits at the slot boundary, inside the 18x18 background.
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

                if (containingScroll != null) {
                    graphics.disableScissor();
                }
            }
        }
    }

    /**
     * Renders lock and sort-lock overlays for ALL slots in the menu.
     *
     * <p>This is a universal rendering pass that covers both MK-panel slots
     * AND vanilla container slots (chests, furnaces, etc.). It iterates every
     * slot in the menu and draws:
     * <ul>
     *   <li>Dark grey tint for fully locked slots ({@code isLocked()})</li>
     *   <li>Amber tint for sort-locked slots ({@code isSortLocked()})</li>
     * </ul>
     *
     * <p>Called at RETURN of {@code renderSlots} — after vanilla has drawn
     * all slot backgrounds and items — so the overlays appear on top.
     *
     * @param graphics the current GuiGraphics context
     * @param menu     the container menu with all slots
     */
    public static void renderLockOverlays(GuiGraphics graphics,
                                            AbstractContainerMenu menu) {
        for (var slot : menu.slots) {
            // Only check slots that have state registered
            MKSlotState state = MKSlotStateRegistry.get(slot);
            if (state == null) continue;

            // Skip inactive slots (disabled or hidden panel)
            if (!state.isSlotActive()) continue;

            int slotX = slot.x;
            int slotY = slot.y;

            // Full lock: dark semi-transparent overlay (same as panel rendering)
            if (state.isLocked()) {
                graphics.fill(slotX, slotY, slotX + 16, slotY + 16,
                        0x40000000);
            }

            // Sort lock: amber semi-transparent overlay to distinguish from
            // full lock. 0x30FFAA00 = ~19% opacity amber — visible but subtle.
            if (state.isSortLocked()) {
                graphics.fill(slotX, slotY, slotX + 16, slotY + 16,
                        0x30FFAA00);
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
        suppressedPanels.clear();
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

        // ── Per-placement stacking cursors for region-relative panels ────
        // Key: "regionOrGroupName:PLACEMENT" — tracks cumulative offset
        // along the edge so multiple panels at the same placement stack
        // rather than overlap.
        Map<String, Integer> placementCursors = new HashMap<>();

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
                    // Mark as suppressed — no position needed, rendering/interaction
                    // checks use isPanelSuppressed() to skip this panel
                    suppressedPanels.add(def.name());
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
                // Compute the panel's position from the target region's or group's
                // bounding box. If the target doesn't exist, hide the panel.
                net.minecraft.world.inventory.AbstractContainerMenu activeMenu = null;
                var mcRF = net.minecraft.client.Minecraft.getInstance();
                if (mcRF != null && mcRF.player != null) {
                    // Prefer the screen's menu when a container screen is open.
                    // In creative mode, the screen's ItemPickerMenu has regions
                    // resolved with correct creative-layout slot positions (set
                    // by onCreativeTabChanged). The player's containerMenu
                    // (inventoryMenu) has stale survival positions for vanilla slots.
                    if (mcRF.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> acs) {
                        var screenMenu = acs.getMenu();
                        // Use screen menu if it has regions, otherwise fall back
                        if (!MKRegionRegistry.getRegions(screenMenu).isEmpty()) {
                            activeMenu = screenMenu;
                        }
                    }
                    if (activeMenu == null) {
                        activeMenu = mcRF.player.containerMenu;
                    }
                }
                if (activeMenu == null) {
                    suppressedPanels.add(def.name());
                    continue;
                }
                MKRegionFollowDef rfd = def.followsRegion();

                // Resolve the bounding box — either from a single region or a group
                int[] bbox;
                if (rfd.isGroup()) {
                    MKRegionGroup group = MKRegionRegistry.getGroup(activeMenu, rfd.regionName());
                    if (group == null || group.regions().isEmpty()) {
                        // Group not in this menu — suppress panel
                        suppressedPanels.add(def.name());
                        continue;
                    }
                    bbox = computeGroupBBox(activeMenu, group);
                } else {
                    MKRegion followedRegion = MKRegionRegistry.getRegion(activeMenu, rfd.regionName());
                    if (followedRegion == null || followedRegion.getMenuSlotStart() < 0) {
                        // Region not in this menu — suppress panel
                        suppressedPanels.add(def.name());
                        continue;
                    }
                    bbox = computeRegionBBox(activeMenu, followedRegion);
                }

                size = def.computeSize(); // reassign outer `size` — no redeclaration needed

                if (rfd.placement() != null) {
                    // ── New 8-placement positioning ──────────────────────────
                    int[] pos = computePlacementPosition(bbox, rfd.placement(), rfd.gap(), size[0], size[1]);
                    px = pos[0] + rfd.offsetX();
                    py = pos[1] + rfd.offsetY();

                    // Apply per-placement stacking: panels sharing the same
                    // target + placement stack along the edge, away from anchor
                    String cursorKey = rfd.regionName() + ":" + rfd.placement().name();
                    int cursor = placementCursors.getOrDefault(cursorKey, 0);
                    switch (rfd.placement()) {
                        case TOP_LEFT, BOTTOM_LEFT   -> { px += cursor; placementCursors.put(cursorKey, cursor + size[0] + m); }
                        case TOP_RIGHT, BOTTOM_RIGHT -> { px -= cursor; placementCursors.put(cursorKey, cursor + size[0] + m); }
                        case LEFT_TOP, RIGHT_TOP     -> { py += cursor; placementCursors.put(cursorKey, cursor + size[1] + m); }
                        case LEFT_BOTTOM, RIGHT_BOTTOM -> { py -= cursor; placementCursors.put(cursorKey, cursor + size[1] + m); }
                    }
                } else {
                    // ── Legacy center-on-side positioning ────────────────────
                    px = computeRegionFollowX(bbox, rfd.direction(), rfd.gap(), size[0]);
                    py = computeRegionFollowY(bbox, rfd.direction(), rfd.gap(), size[1]);
                }
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
        suppressedPanels.clear();
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
            // Skip inactive slots — check MKSlotState if available, otherwise
            // include the slot (vanilla slots always have valid positions)
            MKSlotState bboxState = MKSlotStateRegistry.get(slot);
            if (bboxState != null && !bboxState.isSlotActive()) continue;
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

    /**
     * Computes the union bounding box of all regions in a group.
     * Returns {minX, minY, maxX, maxY} in container-relative coordinates,
     * same format as {@link #computeRegionBBox}.
     */
    private static int[] computeGroupBBox(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                            MKRegionGroup group) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (MKRegion region : group.regions()) {
            int[] regionBBox = computeRegionBBox(menu, region);
            // Skip degenerate regions (no slots resolved)
            if (regionBBox[0] == 0 && regionBBox[1] == 0 && regionBBox[2] == 0 && regionBBox[3] == 0) continue;
            minX = Math.min(minX, regionBBox[0]);
            minY = Math.min(minY, regionBBox[1]);
            maxX = Math.max(maxX, regionBBox[2]);
            maxY = Math.max(maxY, regionBBox[3]);
        }
        if (minX == Integer.MAX_VALUE) return new int[]{ 0, 0, 0, 0 };
        return new int[]{ minX, minY, maxX, maxY };
    }

    /**
     * Computes the base {x, y} position for a panel using the 8-placement system.
     * The panel sits on the specified side of the bbox, aligned to the specified
     * corner of that edge.
     *
     * <p>This returns the anchor position BEFORE stacking. The caller applies
     * per-placement cursor offsets for stacking.
     *
     * @param bbox        {minX, minY, maxX, maxY} of the target region/group
     * @param placement   which edge + alignment
     * @param gap         pixel gap from the bbox edge to the panel edge
     * @param panelWidth  panel width in pixels
     * @param panelHeight panel height in pixels
     * @return {x, y} container-relative position for the panel's top-left corner
     */
    private static int[] computePlacementPosition(int[] bbox, MKRegionPlacement placement,
                                                    int gap, int panelWidth, int panelHeight) {
        return switch (placement) {
            // TOP edge: panel bottom touches bbox top - gap
            case TOP_LEFT     -> new int[]{ bbox[0],                           bbox[1] - gap - panelHeight };
            case TOP_RIGHT    -> new int[]{ bbox[2] - panelWidth,              bbox[1] - gap - panelHeight };

            // BOTTOM edge: panel top touches bbox bottom + gap
            case BOTTOM_LEFT  -> new int[]{ bbox[0],                           bbox[3] + gap };
            case BOTTOM_RIGHT -> new int[]{ bbox[2] - panelWidth,              bbox[3] + gap };

            // RIGHT edge: panel left touches bbox right + gap
            case RIGHT_TOP    -> new int[]{ bbox[2] + gap,                     bbox[1] };
            case RIGHT_BOTTOM -> new int[]{ bbox[2] + gap,                     bbox[3] - panelHeight };

            // LEFT edge: panel right touches bbox left - gap
            case LEFT_TOP     -> new int[]{ bbox[0] - gap - panelWidth,        bbox[1] };
            case LEFT_BOTTOM  -> new int[]{ bbox[0] - gap - panelWidth,        bbox[3] - panelHeight };
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

            // Suppressed panels (exclusive-panel or missing region) — hide buttons
            if (isPanelSuppressed(def.name())) {
                for (var child : screen.children()) {
                    if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                            && mkBtn.panelName.equals(def.name())) {
                        mkBtn.visible = false;
                    }
                }
                continue;
            }

            int[] pos = getResolvedPosition(def, context);

            // Use tree-derived button defs and slot count for correct indexing
            // when conditionally-injected buttons are present in the tree
            List<MKButtonDef> treeButtonDefs = def.getTreeButtonDefs();
            int treeSlotCount = def.getTreeSlotCount();

            MKLayoutResult updateLayout = def.computeFlowPositions();
            for (int i = 0; i < treeButtonDefs.size(); i++) {
                MKButtonDef btnDef = treeButtonDefs.get(i);
                int fi = treeSlotCount + i; // flow position index (buttons after slots)

                // Check button-level disabledWhen predicate
                boolean btnDisabled = !updateLayout.isActive(fi);

                // No +1 for buttons (unlike slots — buttons don't have bg overhang)
                int newX = leftPos + pos[0] + def.effectivePadding() + updateLayout.x(fi);
                int newY = topPos + pos[1] + def.effectivePadding() + updateLayout.y(fi);

                // Find the matching button among screen's renderables by index
                for (var child : screen.children()) {
                    if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                            && mkBtn.panelName.equals(def.name())
                            && mkBtn.buttonIndex == i) {
                        // Check element-level visibility override
                        boolean elementHidden = false;
                        if (mkBtn.elementId != null) {
                            MKPanelState pState = MKPanelStateRegistry.get(def.name());
                            if (pState != null) {
                                Boolean override = pState.getVisible(mkBtn.elementId);
                                if (override != null && !override) elementHidden = true;
                            }
                        }
                        mkBtn.visible = !btnDisabled && !elementHidden;
                        mkBtn.setX(newX);
                        mkBtn.setY(newY);
                        break; // exact match — no need to keep searching
                    }
                }
            }

            // Recompute live panel size using real widget dimensions + flow positions
            // This runs every frame, keeping livePanelSizes accurate when disabledWhen changes
            if (def.autoSize()) {
                int maxRight = 0, maxBottom = 0;

                // Slot content offset (+1) is already baked into layout positions
                for (int si = 0; si < treeSlotCount; si++) {
                    if (!updateLayout.isActive(si)) continue;
                    maxRight = Math.max(maxRight, updateLayout.x(si) + 18);
                    maxBottom = Math.max(maxBottom, updateLayout.y(si) + 18);
                }
                // Use actual button widget dimensions
                int btnIdx = 0;
                for (var child : screen.children()) {
                    if (child instanceof MKButton mkBtn && mkBtn.panelName != null
                            && mkBtn.panelName.equals(def.name()) && mkBtn.visible) {
                        if (btnIdx < treeButtonDefs.size()) {
                            int fi = treeSlotCount + btnIdx;
                            if (updateLayout.isActive(fi)) {
                                maxRight = Math.max(maxRight, updateLayout.x(fi) + mkBtn.getWidth());
                                maxBottom = Math.max(maxBottom, updateLayout.y(fi) + mkBtn.getHeight());
                            }
                        }
                        btnIdx++;
                    }
                }

                // Include text elements in size computation
                int treeButtonCount = treeButtonDefs.size();
                for (int ti = 0; ti < def.textDefs().size(); ti++) {
                    int fi = treeSlotCount + treeButtonCount + ti;
                    if (!updateLayout.isActive(fi)) continue;
                    MKTextDef textDef = def.textDefs().get(ti);
                    maxRight = Math.max(maxRight, updateLayout.x(fi) + textDef.layoutWidth());
                    maxBottom = Math.max(maxBottom, updateLayout.y(fi) + textDef.layoutHeight());
                }

                // Use tree-computed content dimensions as a floor — the layout
                // already accounts for group children (e.g. vertical text) that
                // aren't in the flat slot/button/text lists.
                maxRight = Math.max(maxRight, updateLayout.contentWidth());
                maxBottom = Math.max(maxBottom, updateLayout.contentHeight());

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

    // ── Scroll Container Input Handling ──────────────────────────────────────
    //
    // Handles mouse wheel events for scroll containers. When the cursor is
    // over a scroll viewport, the scroll offset is updated by the scroll speed.
    // Called from MKScreenMixin before the slot-level SCROLL event fires.

    /**
     * Attempts to handle a mouse scroll event for scroll container viewports.
     * If the mouse is over a scroll viewport, updates the scroll offset and
     * returns true (consumed). Otherwise returns false (let other handlers run).
     *
     * @param mouseX   screen-space mouse X
     * @param mouseY   screen-space mouse Y
     * @param scrollY  vertical scroll amount (positive = up, negative = down)
     * @param leftPos  screen's leftPos offset
     * @param topPos   screen's topPos offset
     * @param context  the active MKContext
     * @return true if scroll was consumed by a scroll container
     */
    public static boolean handleScrollContainerInput(double mouseX, double mouseY,
                                                      double scrollY,
                                                      int leftPos, int topPos,
                                                      MKContext context) {
        if (scrollRegionsByPanel.isEmpty()) return false;

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;

            List<MKGroupDef.ScrollRegion> regions = scrollRegionsByPanel.get(def.name());
            if (regions == null || regions.isEmpty()) continue;

            int[] pos = getResolvedPosition(def, context);
            int ep = def.effectivePadding();

            for (MKGroupDef.ScrollRegion sr : regions) {
                // Viewport bounds in screen space
                int vpLeft = leftPos + pos[0] + ep + sr.viewportX();
                int vpTop = topPos + pos[1] + ep + sr.viewportY();
                int vpRight = vpLeft + sr.viewportWidth();
                int vpBottom = vpTop + sr.viewportHeight();

                // Check if mouse is within this viewport
                if (mouseX >= vpLeft && mouseX < vpRight
                        && mouseY >= vpTop && mouseY < vpBottom) {

                    // Get or create panel state for scroll offset
                    MKPanelState state = MKPanelStateRegistry.getOrCreate(def.name());
                    float[] offset = state.getScrollOffset(sr.id());
                    int speed = sr.scrollDef().scrollSpeed();

                    // Apply scroll: scrollY positive = scroll up = content moves down = offset decreases
                    // scrollY negative = scroll down = content moves up = offset increases
                    float newScrollY = offset[1] - (float)(scrollY * speed);
                    float newScrollX = offset[0]; // horizontal scroll not driven by wheel

                    // Clamp to valid range: offset is 0..maxScroll
                    float maxScrollY = Math.max(0, sr.contentHeight() - sr.viewportHeight());
                    float maxScrollX = Math.max(0, sr.contentWidth() - sr.viewportWidth());
                    newScrollY = Math.max(0, Math.min(newScrollY, maxScrollY));
                    newScrollX = Math.max(0, Math.min(newScrollX, maxScrollX));

                    state.setScrollOffset(sr.id(), newScrollX, newScrollY);
                    return true; // Consumed
                }
            }
        }

        return false;
    }

    // ── Tab Click Handling ────────────────────────────────────────────────────
    //
    // Handles mouse clicks on tab bar buttons. When a click lands on a tab
    // button, switches the active tab and fires TAB_CHANGED through the bus.

    /**
     * Attempts to handle a mouse click on a tab bar button.
     * If the click is on a tab button, switches the active tab, fires
     * TAB_CHANGED event, and returns true. Otherwise returns false.
     *
     * @param mouseX   screen-space mouse X
     * @param mouseY   screen-space mouse Y
     * @param button   mouse button (0 = left, 1 = right, 2 = middle)
     * @param leftPos  screen's leftPos offset
     * @param topPos   screen's topPos offset
     * @param context  the active MKContext
     * @return true if the click was consumed by a tab button
     */
    public static boolean handleTabClick(double mouseX, double mouseY,
                                          int button, int leftPos, int topPos,
                                          MKContext context) {
        // Only respond to left clicks
        if (button != 0) return false;
        if (tabsRegionsByPanel.isEmpty()) return false;

        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return false;

        for (MKPanelDef def : panels.values()) {
            if (!def.needsMenuClass(context.menuClass())) continue;
            if (def.isStandaloneScreen()) continue;
            if (!def.appliesTo(context)) continue;
            if (isPanelInactive(def.name())) continue;

            List<MKGroupDef.TabsRegion> regions = tabsRegionsByPanel.get(def.name());
            if (regions == null || regions.isEmpty()) continue;

            int[] pos = getResolvedPosition(def, context);
            int ep = def.effectivePadding();

            for (MKGroupDef.TabsRegion tr : regions) {
                MKTabsDef tabsDef = tr.tabsDef();
                List<MKTabDef> tabs = tabsDef.tabs();
                if (tabs.size() <= 1) continue; // No tab bar for single-tab containers

                int tabsX = leftPos + pos[0] + ep + tr.x();
                int tabsY = topPos + pos[1] + ep + tr.y();

                // Use shared tab button bounds computation (single source of truth
                // shared with renderTabBar so click positions match rendered positions).
                List<TabButtonBounds> allBounds = computeTabButtonBounds(tr, tabsX, tabsY);

                for (TabButtonBounds tbb : allBounds) {
                    int i = tbb.index();

                    // Check if the click is within this tab button
                    if (mouseX >= tbb.x() && mouseX < tbb.x() + tbb.w()
                            && mouseY >= tbb.y() && mouseY < tbb.y() + tbb.h()) {

                        // Get current active tab
                        MKPanelState state = MKPanelStateRegistry.getOrCreate(def.name());
                        int previousTab = state.getActiveTab(tr.id());

                        // Only switch if clicking a different tab
                        if (i != previousTab) {
                            state.setActiveTab(tr.id(), i);

                            // Fire TAB_CHANGED event through the bus
                            MKUIEvent event = MKUIEvent.tabChanged(
                                    def.name(), tr.id(),
                                    previousTab, i,
                                    context, mc.player);
                            MKEventBus.fire(event);

                            LOGGER.debug("[MenuKit] Tab switched: {} tab '{}' {} -> {}",
                                    def.name(), tr.id(), previousTab, i);
                        }

                        return true; // Click consumed by tab button
                    }
                }
            }
        }

        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD — Registration, Rendering, Notifications
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Registers a HUD panel definition. Called by {@link MKHudPanel.Builder#build()}.
     */
    public static void registerHud(MKHudPanelDef def) {
        hudPanels.put(def.name(), def);
        LOGGER.info("[MenuKit] Registered HUD panel '{}'", def.name());
    }

    /**
     * Registers a notification definition. Called by {@link MKHudNotification.Builder#build()}.
     */
    public static void registerNotification(MKHudNotification def) {
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
