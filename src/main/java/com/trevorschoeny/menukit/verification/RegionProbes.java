package com.trevorschoeny.menukit.verification;

import com.trevorschoeny.menukit.MenuKit;
import com.trevorschoeny.menukit.core.HudRegion;
import com.trevorschoeny.menukit.core.MenuRegion;
import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.PanelElement;
import com.trevorschoeny.menukit.core.PanelStyle;
import com.trevorschoeny.menukit.core.RenderContext;
import com.trevorschoeny.menukit.core.SlotGroupCategory;
import com.trevorschoeny.menukit.core.SlotGroupRegion;
import com.trevorschoeny.menukit.hud.MKHudPanel;
import com.trevorschoeny.menukit.inject.ScreenPanelAdapter;
import com.trevorschoeny.menukit.inject.SlotGroupPanelAdapter;

import java.util.List;

/**
 * Dev-scaffolding probes for M5 §9.2 integration-level verification.
 * Registered at client init; invisible by default. Toggled on via
 * {@code /mkverify regions}; an additional {@code /mkverify regions stack}
 * command flips one stacking probe's visibility to verify per-frame reflow.
 *
 * <p>What's exercised:
 * <ul>
 *   <li>Single-panel placement in each of 8 MenuContext regions + 9 HUD regions.</li>
 *   <li>Multi-panel stacking in {@link MenuRegion#RIGHT_ALIGN_TOP} (3 probes).</li>
 *   <li>Mid-stack visibility toggle → reflow (middle probe's {@code showWhen} flips).</li>
 *   <li>Frame-responsive coord tracking (Trevor opens inventory → recipe book →
 *       chest → shulker → etc.; probes track each screen's frame bounds).</li>
 * </ul>
 *
 * <p>Not exercised in v1 probes: overflow cutoff (covered by {@code /mkverify all}
 * contract 6's pure-math overflow cases; visual overflow isn't additive).
 *
 * <h3>M8 dispatch</h3>
 *
 * Probes are region-based adapters declaring {@code .onAny()} — they fire
 * on every opened {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}
 * via
 * {@link com.trevorschoeny.menukit.inject.ScreenPanelRegistry}. The pre-M8
 * probe mixins (ProbeRenderMixin / ProbeRenderRecipeBookMixin) are retired;
 * registry dispatch covers every screen class including the recipe-book
 * family the mixins had coverage gaps on.
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

    /**
     * Smaller size for SlotGroupContext probes so they're visually
     * distinguishable from the 18×18 MenuContext probes when both render
     * on the same screen.
     */
    private static final int SG_PROBE_SIZE = 12;

    // Colors for MenuContext probes — distinct per region for quick eyeballing.
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
     *
     * <p>MenuContext probes use {@code .onAny()} — every
     * {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}
     * gets probe coverage via the library-owned
     * {@link com.trevorschoeny.menukit.inject.ScreenPanelRegistry}. No
     * dedicated probe mixin anymore.
     */
    public static void registerClient() {
        int inventoryCount = 0;

        // ── 8 MenuContext probes (one per region) ─────────────────────
        for (MenuRegion region : MenuRegion.values()) {
            Panel probe = new Panel("probe_inv_" + region.name().toLowerCase(),
                    List.of(filledRect(PROBE_SIZE, colorFor(region))));
            probe.showWhen(() -> master);
            new ScreenPanelAdapter(probe, region).onAny();
            inventoryCount++;
        }

        // ── 2 extra probes in RIGHT_ALIGN_TOP for stacking test ───────
        // The single RIGHT_ALIGN_TOP probe registered above is the top of
        // the stack; these two stack below it. Middle is toggleable.
        Panel stackMid = new Panel("probe_stack_mid",
                List.of(filledRect(PROBE_SIZE, 0xFFFF44FF))); // magenta
        stackMid.showWhen(() -> master && stackMiddleVisible);
        new ScreenPanelAdapter(stackMid, MenuRegion.RIGHT_ALIGN_TOP).onAny();
        inventoryCount++;

        Panel stackBot = new Panel("probe_stack_bot",
                List.of(filledRect(PROBE_SIZE, 0xFF44AAFF))); // light blue
        stackBot.showWhen(() -> master);
        new ScreenPanelAdapter(stackBot, MenuRegion.RIGHT_ALIGN_TOP).onAny();
        inventoryCount++;

        // ── SlotGroupContext probes ───────────────────────────────────
        // Two probes exercising M8's SlotGroupContext dispatch:
        //
        //   1. PLAYER_INVENTORY × TOP_ALIGN_RIGHT (hot pink, 12×12) — above
        //      the right edge of the player inventory's bounding box.
        //      Appears in every AbstractContainerScreen whose resolver
        //      produces PLAYER_INVENTORY slots (which is every vanilla menu
        //      that calls addStandardInventorySlots — effectively all of
        //      them except LecternMenu, which is deferred from v1).
        //
        //   2. CHEST_STORAGE × RIGHT_ALIGN_TOP (mint, 12×12) — right of
        //      the chest storage bounding box. Appears only when
        //      ChestMenu (chests, barrels) opens — absent in other menus.
        //
        // Different size (12 vs 18) keeps SlotGroup probes visually
        // distinguishable from MenuContext probes; different anchor
        // (inside-frame slot bounds vs outside-frame screen bounds) means
        // they won't overlap in practice.

        Panel sgPlayerInv = new Panel("probe_sg_player_inv",
                List.of(filledRect(SG_PROBE_SIZE, 0xFFFF44BB))); // hot pink
        sgPlayerInv.showWhen(() -> master);
        new SlotGroupPanelAdapter(sgPlayerInv, SlotGroupRegion.TOP_ALIGN_RIGHT)
                .on(SlotGroupCategory.PLAYER_INVENTORY);

        Panel sgChestStorage = new Panel("probe_sg_chest_storage",
                List.of(filledRect(SG_PROBE_SIZE, 0xFF44DDCC))); // mint
        sgChestStorage.showWhen(() -> master);
        new SlotGroupPanelAdapter(sgChestStorage, SlotGroupRegion.RIGHT_ALIGN_TOP)
                .on(SlotGroupCategory.CHEST_STORAGE);

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

        MenuKit.LOGGER.info(
                "[RegionProbes] registered {} MenuContext probes + 2 SlotGroup probes + 9 HUD probes",
                inventoryCount);
    }

    // ── Toggles (called from /mkverify regions commands) ───────────────

    public static boolean toggleMaster() {
        master = !master;
        return master;
    }

    public static boolean toggleStackMiddle() {
        stackMiddleVisible = !stackMiddleVisible;
        return stackMiddleVisible;
    }

    public static boolean isMasterOn() { return master; }
    public static boolean isStackMiddleVisible() { return stackMiddleVisible; }
}
