package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.ScreenMatcher;

import net.minecraft.client.gui.GuiGraphics;

import org.jspecify.annotations.Nullable;

/**
 * A consumer's declaration that a slot should manifest on a family of screens —
 * the public face of inventory-screen parity. Register one of these per slot and
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
 *   <li>draws your {@link #background} decoration, then the registered slot frames +
 *       items + hover (the library's own render), then your {@link #foreground}
 *       decoration (icons, buttons) — in that z-order;</li>
 *   <li>routes hover + click to the registered slot it covers, and eats clicks in the
 *       panel's empty space so a carried item can't fall through;</li>
 *   <li>offers your {@link #onClick} / {@link #onScroll} the event first, so your
 *       interactive decoration (resize buttons, etc.) can consume it.</li>
 * </ol>
 *
 * Creative is included for free — the library parks the creative slot wrappers and
 * draws/hit-tests the slot itself, so the same registration covers survival and
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
 * // After registering (client init), register the presence once:
 * SlotScreenPresence.forSlots(pockets)
 *     .onPrepare(ctx -> { PocketHover.update(ctx); PocketRow.reposition(ctx.menu()); })
 *     .background((ctx, g) -> PocketPanelRender.drawBackground(g, ctx.leftPos(), ctx.topPos()))
 *     .foreground((ctx, g) -> PocketPanelRender.drawButtons(g, ctx.leftPos(), ctx.topPos(),
 *                                                           ctx.mouseX(), ctx.mouseY()))
 *     .onClick((ctx, button) -> PocketButtons.handleClick(ctx, button))
 *     .register();
 * }</pre>
 *
 * <p>The registered slot draw / hover / click themselves are <em>not</em> your
 * concern — they are the library's. You supply only what is yours: where the slot
 * sits on each screen (prepare), and its decoration.
 */
public final class SlotScreenPresence {

    // ── Consumer callbacks ──────────────────────────────────────────────────

    /** Per-frame prepare: update reveal state + reposition registered slots for this screen. */
    @FunctionalInterface
    public interface Prepare {
        void prepare(SlotScreenContext ctx);
    }

    /** Draws decoration (a panel background, empty-slot icons, buttons, …). */
    @FunctionalInterface
    public interface Decorator {
        void draw(SlotScreenContext ctx, GuiGraphics graphics);
    }

    /** Handles a click over the slot region. Return true to consume it. */
    @FunctionalInterface
    public interface Click {
        boolean click(SlotScreenContext ctx, int button);
    }

    /** Handles a scroll over the slot region. Return true to consume it. */
    @FunctionalInterface
    public interface Scroll {
        boolean scroll(SlotScreenContext ctx, double scrollX, double scrollY);
    }

    /** Handles a mouse release over the slot region (e.g. finishing a drag). Return true to consume. */
    @FunctionalInterface
    public interface Release {
        boolean release(SlotScreenContext ctx, int button);
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

    private SlotScreenPresence(String panelId) {
        this.panelId = panelId;
    }

    /** Begins a presence for the slot with the given panel id. */
    public static SlotScreenPresence forPanel(String panelId) {
        return new SlotScreenPresence(panelId);
    }

    /** Begins a presence for a {@link MKCSlots.RegisteredSlots} handle (reads its panel id). */
    public static SlotScreenPresence forSlots(MKCSlots.RegisteredSlots registered) {
        return new SlotScreenPresence(registered.panel().getId());
    }

    // ── Targeting (default-on, opt-out) ─────────────────────────────────────

    /** Sets an explicit screen matcher. Default is {@link ScreenMatcher#all()}. */
    public SlotScreenPresence on(ScreenMatcher matcher) {
        this.matcher = matcher;
        return this;
    }

    /** Everywhere except the named screens (and subclasses) — a deliberate opt-out. */
    public SlotScreenPresence exceptScreens(Class<?>... screens) {
        this.matcher = ScreenMatcher.allExcept(screens);
        return this;
    }

    /** Only the named screens (and subclasses) — full narrowing. */
    public SlotScreenPresence onlyScreens(Class<?>... screens) {
        this.matcher = ScreenMatcher.only(screens);
        return this;
    }

    // ── Callbacks ───────────────────────────────────────────────────────────

    public SlotScreenPresence onPrepare(Prepare prepare) {
        this.prepare = prepare;
        return this;
    }

    public SlotScreenPresence background(Decorator background) {
        this.background = background;
        return this;
    }

    public SlotScreenPresence foreground(Decorator foreground) {
        this.foreground = foreground;
        return this;
    }

    public SlotScreenPresence onClick(Click click) {
        this.click = click;
        return this;
    }

    public SlotScreenPresence onScroll(Scroll scroll) {
        this.scroll = scroll;
        return this;
    }

    public SlotScreenPresence onRelease(Release release) {
        this.release = release;
        return this;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Registers this presence with the screen dispatch. Call once. */
    public SlotScreenPresence register() {
        if (registered) {
            throw new IllegalStateException(
                    "SlotScreenPresence for panel '" + panelId + "' is already registered.");
        }
        registered = true;
        MKCSlotScreenHook.register(this);
        return this;
    }

    /** Removes this presence from the screen dispatch. Idempotent. */
    public void unregister() {
        MKCSlotScreenHook.unregister(this);
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
