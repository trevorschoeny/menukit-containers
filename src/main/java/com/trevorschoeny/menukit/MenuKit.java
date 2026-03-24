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
    private static @Nullable MKSlot hoveredMKSlot = null;
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

        LOGGER.info("[MenuKit] Registered MK_MENU_TYPE");
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

    // ── Panel Queries ──────────────────────────────────────────────────────

    /** Returns a panel definition by name, or null if not found. */
    public static MKPanelDef getPanelDef(String name) {
        return panels.get(name);
    }

    // ── Panel Visibility ───────────────────────────────────────────────────

    /** Shows a hidden panel by name. */
    public static void showPanel(String name) {
        hiddenPanels.remove(name);
    }

    /** Hides a panel by name. Hidden panels' slots become inactive and
     *  their backgrounds and buttons are not rendered. */
    public static void hidePanel(String name) {
        hiddenPanels.add(name);
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

    /** Toggles a panel's visibility. */
    public static void togglePanel(String name) {
        if (hiddenPanels.contains(name)) {
            hiddenPanels.remove(name);
        } else {
            hiddenPanels.add(name);
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

    /**
     * Returns true if the named panel should NOT be shown — either because it's
     * hidden (imperative) or disabled (predicate). Use this for all visibility checks.
     */
    public static boolean isPanelInactive(String name) {
        return isPanelHidden(name) || isPanelDisabled(name);
    }

    // ── Hover Tracking (client-only) ──────────────────────────────────────

    /**
     * Returns the MKSlot currently under the mouse, or null.
     * Updated each frame by {@link #renderSlotBackgrounds} — our own hover
     * detection that works in all screen contexts (unlike vanilla's hoveredSlot
     * which can miss slots in certain screen subclasses).
     */
    public static @Nullable MKSlot getHoveredMKSlot() {
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

            if (targetSlot instanceof MKSlot mkSlot) {
                String key = mkSlot.panelName() + ":" + mkSlot.slotIndexInPanel();
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
                MKSlot liveMKSlot = findLiveMKSlot(menu, slotX, slotY);

                // Render hover highlight (bright white overlay) when mouse is over this slot.
                // Vanilla's highlight sprites don't render well on MKSlots outside the
                // container, so we draw our own to match vanilla's visual brightness.
                boolean isHovered = relMouseX >= slotX - 1 && relMouseX < slotX + 17
                        && relMouseY >= slotY - 1 && relMouseY < slotY + 17;
                if (isHovered) {
                    graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0x66FFFFFF);
                    // Track the hovered MKSlot for tooltips and empty-click callbacks
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
            }
        }
    }

    /**
     * Finds the live MKSlot in the menu at the given container-relative position.
     * Unwraps SlotWrapper (used by creative mode) to reach the underlying MKSlot.
     *
     * @return the MKSlot at this position, or null if not found
     */
    private static @Nullable MKSlot findLiveMKSlot(AbstractContainerMenu menu, int slotX, int slotY) {
        for (var menuSlot : menu.slots) {
            if (menuSlot.x == slotX && menuSlot.y == slotY) {
                // Unwrap SlotWrapper if needed (creative mode wraps slots)
                net.minecraft.world.inventory.Slot target = menuSlot;
                if (menuSlot instanceof com.trevorschoeny.menukit.mixin.SlotWrapperAccessor wrapper) {
                    target = wrapper.menuKit$getTarget();
                }
                if (target instanceof MKSlot ms) {
                    // Skip inactive slots — multiple hidden pockets can share
                    // the same position. We want the ACTIVE one, not the first match.
                    if (!ms.isActive()) continue;
                    return ms;
                }
                // Position matched but not an MKSlot — don't break, another slot
                // at the same position might be the active MKSlot
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
}
