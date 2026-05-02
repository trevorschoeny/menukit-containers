package com.trevorschoeny.menukit;

import com.trevorschoeny.menukit.core.PanelElement;
import com.trevorschoeny.menukit.core.PanelRendering;
import com.trevorschoeny.menukit.core.PanelStyle;
import com.trevorschoeny.menukit.core.RenderContext;
import com.trevorschoeny.menukit.hud.MKHudNotification;
import com.trevorschoeny.menukit.hud.MKHudPanelDef;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mod entry point for MenuKit, plus thin facades for the orthogonal
 * subsystems that want a process-wide home: HUD panels, notifications.
 *
 * <p>MenuKit's canonical surface lives elsewhere:
 * <ul>
 *   <li><b>Screens:</b> {@code com.trevorschoeny.menukit.screen.MenuKitScreenHandler}
 *       + {@code MenuKitHandledScreen}</li>
 *   <li><b>Composition:</b> {@code com.trevorschoeny.menukit.core.Panel},
 *       {@code SlotGroup}, {@code SlotGroupLike}</li>
 *   <li><b>Elements:</b> {@code PanelElement}, {@code Button}, {@code TextLabel}</li>
 *   <li><b>Observed screens:</b> {@code HandlerRecognizerRegistry},
 *       {@code VirtualSlotGroup}</li>
 *   <li><b>Per-screen events:</b>
 *       {@code MenuKitHandledScreen.ScreenEventListener}</li>
 * </ul>
 *
 * <p>This class intentionally stays small. Anything screen-scoped lives on
 * {@code MenuKitHandledScreen}; anything group-scoped lives on {@code SlotGroup};
 * anything truly process-wide (HUD overlays, notification triggers) is the
 * only thing that lives here.
 */
public class MenuKit implements ModInitializer {

    /** MenuKit's own logger — independent of any consuming mod's logger. */
    public static final Logger LOGGER = LoggerFactory.getLogger("menukit");

    // ── HUD panel registry (registered at mod init, client-only) ──────────
    private static final Map<String, MKHudPanelDef> hudPanels = new LinkedHashMap<>();

    // ── Notification definitions (registered at mod init) ─────────────────
    private static final Map<String, MKHudNotification> notificationDefs = new LinkedHashMap<>();

    // ── Active notifications (runtime animation state) ────────────────────
    private static final Map<String, ActiveNotification> activeNotifications = new LinkedHashMap<>();

    /** Snapshot of what a caller passed to {@link #notify}, plus trigger time. */
    record ActiveNotification(long triggerTimeMs,
                              @Nullable String textData,
                              @Nullable ItemStack itemData) {}

    // ══════════════════════════════════════════════════════════════════════
    // Mod lifecycle
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void onInitialize() {
        init();
    }

    /** Server-safe initialization. Retained for symmetry and
     *  consumer-ordering (mods can call {@code MenuKit.init()} explicitly
     *  before their own registration if needed). Also registers the
     *  contract-verification harness ({@code /mkverify} command suite +
     *  test MenuType) so phase verification can be re-run at any time. */
    public static void init() {
        LOGGER.info("[MenuKit] Initialized");
        // M1 per-slot state — attachments + shared networking types register
        // here (attachment registration must run on both sides; networking
        // payload-type registration is also symmetric).
        com.trevorschoeny.menukit.state.SlotStateAttachments.register();
        com.trevorschoeny.menukit.state.SlotStateHooks.registerCommon();
        com.trevorschoeny.menukit.state.SlotStateHooks.registerServer();
        com.trevorschoeny.menukit.verification.ContractVerification.initServer();
    }

    /** Client-safe initialization. Invoked from
     *  {@link MenuKitClient#onInitializeClient()}. Registers the
     *  verification test-screen factory.
     *
     *  <p>Phase 14d-2.7: RegionProbes registration migrated to validator
     *  (consumer-side test scaffolding per TESTING_CONVENTIONS.md). */
    public static void initClient() {
        LOGGER.info("[MenuKit] Client initialized");
        com.trevorschoeny.menukit.state.SlotStateHooks.registerClient();
        com.trevorschoeny.menukit.verification.ContractVerification.initClient();
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD — registration, rendering, notifications
    // ══════════════════════════════════════════════════════════════════════

    /** Registers a HUD panel definition. Called by
     *  {@link com.trevorschoeny.menukit.hud.MKHudPanel.Builder#build()}. */
    public static void registerHud(MKHudPanelDef def) {
        hudPanels.put(def.name(), def);
        LOGGER.info("[MenuKit] Registered HUD panel '{}'", def.name());
    }

    /** Registers a notification definition. Called by
     *  {@link MKHudNotification.Builder#build()}. */
    public static void registerNotification(MKHudNotification def) {
        notificationDefs.put(def.getKey(), def);
        LOGGER.info("[MenuKit] Registered notification '{}'", def.getKey());
    }

    /**
     * Renders all registered HUD panels. Called at RETURN of
     * {@code Gui.render()} by {@code MKGuiMixin}. Evaluates visibility
     * conditions, resolves anchor positions, and delegates to each panel's
     * element tree.
     *
     * <p>Uses vanilla's {@link GuiGraphics} and coordinate system directly —
     * working WITH vanilla, not against it.
     */
    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        boolean hasScreen = mc.screen != null;

        // ── HUD panels ─────────────────────────────────────────────────────
        for (MKHudPanelDef def : hudPanels.values()) {
            // Visibility gates
            if (def.hideInScreen() && hasScreen) continue;
            if (!def.showWhen().get()) continue;

            // Compute panel size (auto or explicit)
            int[] size = def.computeSize();

            // Resolve to absolute screen position — region-based if the
            // consumer called .region() at build time, otherwise fall back
            // to anchor + offset. The two are mutually exclusive at build.
            int[] pos;
            if (def.region() != null) {
                int prefix = com.trevorschoeny.menukit.inject.RegionRegistry
                        .axialPrefix(def, def.region());
                var origin = com.trevorschoeny.menukit.core.RegionMath
                        .resolveHud(def.region(), screenW, screenH,
                                size[0], size[1], prefix);
                if (origin.isEmpty()) {
                    // Overflow — skip this frame. Log once per (panel, region)
                    // pair so consumers see a diagnostic instead of silent
                    // no-render. Phase 12.5 V4 finding.
                    com.trevorschoeny.menukit.inject.RegionRegistry
                            .warnHudOverflowOnce(def, def.region(),
                                    size[0], size[1], prefix, screenW, screenH);
                    continue;
                }
                pos = new int[]{origin.get().x(), origin.get().y()};
            } else {
                pos = def.anchor().resolve(screenW, screenH,
                        size[0], size[1], def.offsetX(), def.offsetY());
            }

            // Background
            if (def.style() != PanelStyle.NONE) {
                PanelRendering.renderPanel(graphics, pos[0], pos[1],
                        size[0], size[1], def.style());
            }

            // Optional onRender callback (consumer-supplied arbitrary draw)
            if (def.onRender() != null) {
                def.onRender().render(graphics, pos[0], pos[1],
                        size[0], size[1], deltaTracker);
            }

            // Child elements, offset by padding. RenderContext uses -1 for
            // mouse coordinates because HUDs don't dispatch input.
            int contentX = pos[0] + def.padding();
            int contentY = pos[1] + def.padding();
            RenderContext ctx = new RenderContext(graphics, contentX, contentY, -1, -1);
            for (PanelElement element : def.elements()) {
                if (!element.isVisible()) continue;
                element.render(ctx);
            }
        }

        // ── Active notifications ──────────────────────────────────────────
        renderActiveNotifications(graphics, deltaTracker, screenW, screenH);
    }

    /**
     * Triggers a notification by key. The notification slides in, displays
     * for its configured duration, then fades out.
     *
     * @param key  notification key (must match a
     *             {@link MKHudNotification#builder(String)} registration)
     * @param text text to display, or null for icon-only
     */
    public static void notify(String key, String text) {
        notify(key, text, (ItemStack) null);
    }

    /** Triggers a notification with text and an item icon. */
    public static void notify(String key, String text, @Nullable ItemStack item) {
        if (!notificationDefs.containsKey(key)) {
            LOGGER.warn("[MenuKit] Unknown notification key '{}'", key);
            return;
        }
        activeNotifications.put(key, new ActiveNotification(
                System.currentTimeMillis(), text, item));
    }

    /** Triggers a notification with text and an item type. */
    public static void notify(String key, String text, net.minecraft.world.item.Item item) {
        notify(key, text, new ItemStack(item));
    }

    /**
     * Renders all active notifications, removing expired ones.
     * Iterates once; each notification's own {@link MKHudNotification#render}
     * handles its slide-in / display / fade-out animation based on elapsed
     * time since trigger.
     */
    private static void renderActiveNotifications(GuiGraphics graphics,
                                                   DeltaTracker deltaTracker,
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

        expired.forEach(activeNotifications::remove);
    }
}
