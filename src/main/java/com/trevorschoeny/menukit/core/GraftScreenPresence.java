package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.ScreenMatcher;

import net.minecraft.client.gui.GuiGraphics;

import org.jspecify.annotations.Nullable;

/**
 * A consumer's declaration that a graft should manifest on a family of screens —
 * the public face of inventory-screen parity. Register one of these per graft and
 * MenuKit drives its draw, hover, click, and reveal on <b>every</b> matching
 * inventory-bearing screen, by default, with no per-screen consumer mixin.
 *
 * <h3>What MenuKit does for you, automatically</h3>
 *
 * For each screen the {@linkplain #on(ScreenMatcher) matcher} accepts, every
 * frame:
 * <ol>
 *   <li>fires {@link #onPrepare} (update hover-reveal, reposition slots for this
 *       screen) before the slots draw / hit-test;</li>
 *   <li>draws your {@link #background} decoration, then the grafted slot frames +
 *       items + hover (the library's own render), then your {@link #foreground}
 *       decoration (icons, buttons) — in that z-order;</li>
 *   <li>routes hover + click to the grafted slot it covers, and eats clicks in the
 *       panel's empty space so a carried item can't fall through;</li>
 *   <li>offers your {@link #onClick} / {@link #onScroll} the event first, so your
 *       interactive decoration (resize buttons, etc.) can consume it.</li>
 * </ol>
 *
 * Creative is included for free — the library parks the creative slot wrappers and
 * draws/hit-tests the graft itself, so the same registration covers survival and
 * creative without the consumer hand-wiring either.
 *
 * <h3>Default-on, opt-out per screen</h3>
 *
 * The default screen set is <b>every</b> {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}.
 * Narrow it only deliberately: {@link #exceptScreens} to exclude (e.g. "not in
 * creative"), {@link #onlyScreens} to restrict to a closed set, or {@link #on}
 * with a hand-built {@link ScreenMatcher}.
 *
 * <h3>Recipe</h3>
 *
 * <pre>{@code
 * // After grafting (client init), register the presence once:
 * GraftScreenPresence.forGraft(pockets)
 *     .onPrepare(ctx -> { PocketHover.update(ctx); PocketRow.reposition(ctx.menu()); })
 *     .background((ctx, g) -> PocketPanelRender.drawBackground(g, ctx.leftPos(), ctx.topPos()))
 *     .foreground((ctx, g) -> PocketPanelRender.drawButtons(g, ctx.leftPos(), ctx.topPos(),
 *                                                           ctx.mouseX(), ctx.mouseY()))
 *     .onClick((ctx, button) -> PocketButtons.handleClick(ctx, button))
 *     .register();
 * }</pre>
 *
 * <p>The grafted slot draw / hover / click themselves are <em>not</em> your
 * concern — they are the library's. You supply only what is yours: where the graft
 * sits on each screen (prepare), and its decoration.
 */
public final class GraftScreenPresence {

    // ── Consumer callbacks ──────────────────────────────────────────────────

    /** Per-frame prepare: update reveal state + reposition grafted slots for this screen. */
    @FunctionalInterface
    public interface Prepare {
        void prepare(GraftScreenContext ctx);
    }

    /** Draws decoration (a panel background, empty-slot icons, buttons, …). */
    @FunctionalInterface
    public interface Decorator {
        void draw(GraftScreenContext ctx, GuiGraphics graphics);
    }

    /** Handles a click over the graft region. Return true to consume it. */
    @FunctionalInterface
    public interface Click {
        boolean click(GraftScreenContext ctx, int button);
    }

    /** Handles a scroll over the graft region. Return true to consume it. */
    @FunctionalInterface
    public interface Scroll {
        boolean scroll(GraftScreenContext ctx, double scrollX, double scrollY);
    }

    /** Handles a mouse release over the graft region (e.g. finishing a drag). Return true to consume. */
    @FunctionalInterface
    public interface Release {
        boolean release(GraftScreenContext ctx, int button);
    }

    // ── Configuration ───────────────────────────────────────────────────────

    private final String panelId;
    private ScreenMatcher matcher = ScreenMatcher.all();
    private @Nullable Prepare prepare;
    private @Nullable Decorator background;
    private @Nullable Decorator foreground;
    private @Nullable Click click;
    private @Nullable Scroll scroll;
    private @Nullable Release release;
    private boolean registered = false;

    private GraftScreenPresence(String panelId) {
        this.panelId = panelId;
    }

    /** Begins a presence for the graft with the given panel id. */
    public static GraftScreenPresence forPanel(String panelId) {
        return new GraftScreenPresence(panelId);
    }

    /** Begins a presence for a {@link MenuKitGraft.Grafted} handle (reads its panel id). */
    public static GraftScreenPresence forGraft(MenuKitGraft.Grafted grafted) {
        return new GraftScreenPresence(grafted.panel().getId());
    }

    // ── Targeting (default-on, opt-out) ─────────────────────────────────────

    /** Sets an explicit screen matcher. Default is {@link ScreenMatcher#all()}. */
    public GraftScreenPresence on(ScreenMatcher matcher) {
        this.matcher = matcher;
        return this;
    }

    /** Everywhere except the named screens (and subclasses) — a deliberate opt-out. */
    public GraftScreenPresence exceptScreens(Class<?>... screens) {
        this.matcher = ScreenMatcher.allExcept(screens);
        return this;
    }

    /** Only the named screens (and subclasses) — full narrowing. */
    public GraftScreenPresence onlyScreens(Class<?>... screens) {
        this.matcher = ScreenMatcher.only(screens);
        return this;
    }

    // ── Callbacks ───────────────────────────────────────────────────────────

    public GraftScreenPresence onPrepare(Prepare prepare) {
        this.prepare = prepare;
        return this;
    }

    public GraftScreenPresence background(Decorator background) {
        this.background = background;
        return this;
    }

    public GraftScreenPresence foreground(Decorator foreground) {
        this.foreground = foreground;
        return this;
    }

    public GraftScreenPresence onClick(Click click) {
        this.click = click;
        return this;
    }

    public GraftScreenPresence onScroll(Scroll scroll) {
        this.scroll = scroll;
        return this;
    }

    public GraftScreenPresence onRelease(Release release) {
        this.release = release;
        return this;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Registers this presence with the screen dispatch. Call once. */
    public GraftScreenPresence register() {
        if (registered) {
            throw new IllegalStateException(
                    "GraftScreenPresence for panel '" + panelId + "' is already registered.");
        }
        registered = true;
        MenuKitGraftScreenHook.register(this);
        return this;
    }

    /** Removes this presence from the screen dispatch. Idempotent. */
    public void unregister() {
        MenuKitGraftScreenHook.unregister(this);
        registered = false;
    }

    // ── Accessors (read by the dispatch hook, same package) ─────────────────

    String panelId() {
        return panelId;
    }

    ScreenMatcher matcher() {
        return matcher;
    }

    @Nullable Prepare prepareHandler() {
        return prepare;
    }

    @Nullable Decorator backgroundHandler() {
        return background;
    }

    @Nullable Decorator foregroundHandler() {
        return foreground;
    }

    @Nullable Click clickHandler() {
        return click;
    }

    @Nullable Scroll scrollHandler() {
        return scroll;
    }

    @Nullable Release releaseHandler() {
        return release;
    }
}
