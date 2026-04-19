package com.trevorschoeny.menukit.verification;

import com.trevorschoeny.menukit.MenuKit;
import com.trevorschoeny.menukit.core.HudRegion;
import com.trevorschoeny.menukit.core.MenuRegion;
import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.PanelElement;
import com.trevorschoeny.menukit.core.PanelStyle;
import com.trevorschoeny.menukit.core.RenderContext;
import com.trevorschoeny.menukit.hud.MKHudPanel;
import com.trevorschoeny.menukit.inject.ScreenBounds;
import com.trevorschoeny.menukit.inject.ScreenPanelAdapter;
import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-scaffolding probes for M5 §9.2 integration-level verification.
 * Registered at client init; invisible by default. Toggled on via
 * {@code /mkverify regions}; an additional {@code /mkverify regions stack}
 * command flips one stacking probe's visibility to verify per-frame reflow.
 *
 * <p>What's exercised:
 * <ul>
 *   <li>Single-panel placement in each of 8 inventory regions + 9 HUD regions.</li>
 *   <li>Multi-panel stacking in {@link MenuRegion#RIGHT_ALIGN_TOP} (3 probes).</li>
 *   <li>Mid-stack visibility toggle → reflow (middle probe's {@code showWhen} flips).</li>
 *   <li>Frame-responsive coord tracking (Trevor opens inventory → recipe book →
 *       chest → shulker → etc.; probes track each screen's {@link ScreenBounds}).</li>
 * </ul>
 *
 * <p>Not exercised in v1 probes: overflow cutoff (covered by {@code /mkverify all}
 * contract 6's pure-math overflow cases; visual overflow isn't additive).
 */
public final class RegionProbes {

    private RegionProbes() {}

    // ── Toggle state ────────────────────────────────────────────────────

    /** Master flag — all probes hidden unless {@code true}. */
    private static volatile boolean master = false;

    /**
     * Middle-stack flag — gates the middle probe in RIGHT_ALIGN_TOP. Flipping
     * it verifies per-frame reflow of subsequent stacking entries.
     */
    private static volatile boolean stackMiddleVisible = true;

    /** Inventory-probe adapters — rendered by {@code ProbeRenderMixin}. */
    private static final List<ScreenPanelAdapter> INVENTORY_ADAPTERS = new ArrayList<>();

    // ── Probe visuals ───────────────────────────────────────────────────

    /** One 18×18 filled rectangle with a 1px black border. Easy to eyeball. */
    private static PanelElement filledRect(int size, int color) {
        return new PanelElement() {
            @Override public int getChildX() { return 0; }
            @Override public int getChildY() { return 0; }
            @Override public int getWidth() { return size; }
            @Override public int getHeight() { return size; }
            @Override public void render(RenderContext ctx) {
                int x = ctx.originX();
                int y = ctx.originY();
                // Solid fill
                ctx.graphics().fill(x, y, x + size, y + size, color);
                // 1px black border (4 sides)
                ctx.graphics().fill(x, y, x + size, y + 1, 0xFF000000);
                ctx.graphics().fill(x, y + size - 1, x + size, y + size, 0xFF000000);
                ctx.graphics().fill(x, y, x + 1, y + size, 0xFF000000);
                ctx.graphics().fill(x + size - 1, y, x + size, y + size, 0xFF000000);
            }
        };
    }

    private static final int PROBE_SIZE = 18;

    // Colors for inventory probes — distinct per region for quick eyeballing.
    private static int colorFor(MenuRegion region) {
        return switch (region) {
            case LEFT_ALIGN_TOP     -> 0xFFFF4444; // red
            case LEFT_ALIGN_BOTTOM  -> 0xFFFF8800; // orange
            case RIGHT_ALIGN_TOP    -> 0xFFFFFF44; // yellow
            case RIGHT_ALIGN_BOTTOM -> 0xFF44FF44; // green
            case TOP_ALIGN_LEFT     -> 0xFF44FFFF; // cyan
            case TOP_ALIGN_RIGHT    -> 0xFF4488FF; // blue
            case BOTTOM_ALIGN_LEFT  -> 0xFFCC44FF; // purple
            case BOTTOM_ALIGN_RIGHT -> 0xFFFFFFFF; // white
        };
    }

    private static int colorFor(HudRegion region) {
        return switch (region) {
            case TOP_LEFT      -> 0xFFFF4444;
            case TOP_CENTER    -> 0xFFFF8800;
            case TOP_RIGHT     -> 0xFFFFFF44;
            case LEFT_CENTER   -> 0xFF44FFFF;
            case RIGHT_CENTER  -> 0xFF4488FF;
            case BOTTOM_LEFT   -> 0xFF44FF44;
            case BOTTOM_CENTER -> 0xFFCC44FF;
            case BOTTOM_RIGHT  -> 0xFFFFFFFF;
            case CENTER        -> 0xFFFFAAAA; // pinkish — visible against world
        };
    }

    // ── Registration ────────────────────────────────────────────────────

    /**
     * Client-side init — registers all probe Panels + adapters.
     * Called from {@link MenuKit#initClient()}. Registration is a side
     * effect of adapter/builder construction; panels only render when
     * {@link #master} is true (gated via {@link Panel#showWhen}).
     */
    public static void registerClient() {
        // ── 8 MenuContext probes (one per region) ─────────────────────
        // Probes .onAny() — they fire on every AbstractContainerScreen so
        // the MenuContext region system gets exercised across chests,
        // crafting screens, furnaces, etc. Step 3 wires the dispatch; for
        // step 2, the declaration just records targeting.
        for (MenuRegion region : MenuRegion.values()) {
            Panel probe = new Panel("probe_inv_" + region.name().toLowerCase(),
                    List.of(filledRect(PROBE_SIZE, colorFor(region))));
            probe.showWhen(() -> master);
            INVENTORY_ADAPTERS.add(new ScreenPanelAdapter(probe, region).onAny());
        }

        // ── 2 extra probes in RIGHT_ALIGN_TOP for stacking test ───────
        // The existing single RIGHT_ALIGN_TOP probe registered above is the
        // top of the stack; these two stack below it. Middle is toggleable.
        Panel stackMid = new Panel("probe_stack_mid",
                List.of(filledRect(PROBE_SIZE, 0xFFFF44FF))); // magenta
        stackMid.showWhen(() -> master && stackMiddleVisible);
        INVENTORY_ADAPTERS.add(
                new ScreenPanelAdapter(stackMid, MenuRegion.RIGHT_ALIGN_TOP).onAny());

        Panel stackBot = new Panel("probe_stack_bot",
                List.of(filledRect(PROBE_SIZE, 0xFF44AAFF))); // light blue
        stackBot.showWhen(() -> master);
        INVENTORY_ADAPTERS.add(
                new ScreenPanelAdapter(stackBot, MenuRegion.RIGHT_ALIGN_TOP).onAny());

        // ── 9 HUD probes (one per region) ─────────────────────────────
        for (HudRegion region : HudRegion.values()) {
            MKHudPanel.builder("probe_hud_" + region.name().toLowerCase())
                    .region(region)
                    .style(PanelStyle.NONE)
                    .showWhen(() -> master)
                    .element(filledRect(PROBE_SIZE, colorFor(region)))
                    .autoSize()
                    .build();
        }

        MenuKit.LOGGER.info("[RegionProbes] DIAG — registered {} inventory adapters",
                INVENTORY_ADAPTERS.size());
    }

    /** Set to true on master toggle; logs once-per-toggle inside renderInventoryProbes. */
    private static boolean logNextRender = false;

    // ── Toggles (called from /mkverify regions commands) ───────────────

    public static boolean toggleMaster() {
        master = !master;
        if (master) logNextRender = true;
        return master;
    }

    public static boolean toggleStackMiddle() {
        stackMiddleVisible = !stackMiddleVisible;
        return stackMiddleVisible;
    }

    public static boolean isMasterOn() { return master; }
    public static boolean isStackMiddleVisible() { return stackMiddleVisible; }

    // ── Render path (called from ProbeRenderMixin) ─────────────────────

    /**
     * Iterates registered inventory probe adapters and renders them against
     * the given screen's bounds. Short-circuits when {@link #master} is false
     * (the adapters' panels will also self-short-circuit, but saving the
     * per-adapter call when probes are globally off is zero work).
     */
    public static void renderInventoryProbes(AbstractContainerScreen<?> screen,
                                              GuiGraphics graphics,
                                              int mouseX, int mouseY) {
        if (!master) return;
        ScreenBounds bounds = boundsOf(screen);
        if (logNextRender) {
            logNextRender = false;
            MenuKit.LOGGER.info(
                    "[RegionProbes] DIAG — renderInventoryProbes reached; adapters={} bounds=(left={}, top={}, w={}, h={}) screen={}",
                    INVENTORY_ADAPTERS.size(),
                    bounds.leftPos(), bounds.topPos(), bounds.imageWidth(), bounds.imageHeight(),
                    screen.getClass().getSimpleName());
            // Also log the first probe's computed origin
            if (!INVENTORY_ADAPTERS.isEmpty()) {
                ScreenPanelAdapter first = INVENTORY_ADAPTERS.get(0);
                Panel p = first.getPanel();
                MenuKit.LOGGER.info(
                        "[RegionProbes] DIAG — first probe '{}' visible={} width={} height={}",
                        p.getId(), p.isVisible(), p.getWidth(), p.getHeight());
            }
        }
        for (ScreenPanelAdapter adapter : INVENTORY_ADAPTERS) {
            adapter.render(graphics, bounds, mouseX, mouseY, screen);
        }
    }

    private static ScreenBounds boundsOf(AbstractContainerScreen<?> screen) {
        var acc = (AbstractContainerScreenAccessor) screen;
        return new ScreenBounds(
                acc.trevorMod$getLeftPos(),
                acc.trevorMod$getTopPos(),
                acc.trevorMod$getImageWidth(),
                acc.trevorMod$getImageHeight());
    }
}
