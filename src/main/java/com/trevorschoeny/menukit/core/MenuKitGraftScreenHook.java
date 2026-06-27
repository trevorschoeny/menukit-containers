package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.GraftHoverResult;
import com.trevorschoeny.menukit.inject.GraftScreenHook;
import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MenuKit-Containers' implementation of MenuKit's neutral {@link GraftScreenHook}
 * — the one place the grafted-slot work plugs into MenuKit's library-owned screen
 * dispatch (§0042). Registered with {@code GraftScreenDispatcher.setHook} at MKC
 * client init.
 *
 * <p>Holds the consumer-registered {@link GraftScreenPresence}s in a static list
 * (so a presence may register before or after this hook is constructed — order
 * doesn't matter), and on each dispatch:
 * <ul>
 *   <li>selects the presences whose {@link com.trevorschoeny.menukit.inject.ScreenMatcher}
 *       accepts the opened screen (per-graft opt-out);</li>
 *   <li>fans the operation across them — for render, in strict z-order: all
 *       backgrounds, then all grafted slots, then all foregrounds, so decoration
 *       never paints over a sibling graft's slots;</li>
 *   <li>resolves hover/click against only those presences' panels.</li>
 * </ul>
 *
 * <p>Client-only — every method runs on the render/input thread; the server never
 * constructs this.
 */
public final class MenuKitGraftScreenHook implements GraftScreenHook {

    // Static so consumer presences can register independently of when MKC sets
    // the hook. Copy-on-write: rare writes (mod init), frequent reads (per frame).
    private static final List<GraftScreenPresence> PRESENCES = new CopyOnWriteArrayList<>();

    /** Registers a presence. Called from {@link GraftScreenPresence#register}. */
    static void register(GraftScreenPresence presence) {
        PRESENCES.add(presence);
    }

    /** Removes a presence. Called from {@link GraftScreenPresence#unregister}. Idempotent. */
    static void unregister(GraftScreenPresence presence) {
        PRESENCES.remove(presence);
    }

    // ── GraftScreenHook ─────────────────────────────────────────────────────

    @Override
    public void prepare(AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
        List<GraftScreenPresence> matching = matching(screen);
        if (matching.isEmpty()) return;
        GraftScreenContext ctx = context(screen, mouseX, mouseY);
        for (GraftScreenPresence p : matching) {
            GraftScreenPresence.Prepare h = p.prepareHandler();
            if (h != null) h.prepare(ctx);
        }
    }

    @Override
    public void render(AbstractContainerScreen<?> screen, GuiGraphics graphics,
                       int mouseX, int mouseY, float partialTick) {
        List<GraftScreenPresence> matching = matching(screen);
        if (matching.isEmpty()) return;
        GraftScreenContext ctx = context(screen, mouseX, mouseY);

        // z-order, across all matching grafts at once: backgrounds first (behind
        // every graft's slots), then the slots, then foregrounds (icons/buttons
        // over every graft's slots). A per-graft loop for the slot draw keeps each
        // graft's own panel id so an opted-out graft is simply absent.
        for (GraftScreenPresence p : matching) {
            GraftScreenPresence.Decorator bg = p.backgroundHandler();
            if (bg != null) bg.draw(ctx, graphics);
        }
        for (GraftScreenPresence p : matching) {
            MenuKitGraftRender.renderGraftedSlots(screen, graphics, mouseX, mouseY, p.panelId());
        }
        for (GraftScreenPresence p : matching) {
            GraftScreenPresence.Decorator fg = p.foregroundHandler();
            if (fg != null) fg.draw(ctx, graphics);
        }
    }

    @Override
    public GraftHoverResult resolveHover(AbstractContainerScreen<?> screen,
                                         double mouseX, double mouseY) {
        Set<String> ids = resolvablePanelIds(matching(screen));
        if (ids.isEmpty()) return GraftHoverResult.PASS;

        MenuKitGraftInput.Resolution r =
                MenuKitGraftInput.resolveHoveredSlot(screen, mouseX, mouseY, ids);
        if (!r.handled()) return GraftHoverResult.PASS;
        // handled with a slot → graft wins; handled with null → in-panel gap (block).
        return r.slot() == null ? GraftHoverResult.BLOCK : GraftHoverResult.of(r.slot());
    }

    @Override
    public boolean mouseClicked(AbstractContainerScreen<?> screen,
                                double mouseX, double mouseY, int button) {
        List<GraftScreenPresence> matching = matching(screen);

        // Consumer interactive decoration first (e.g. +/− buttons), explicit order
        // so a button always wins over the covered-click guard behind it.
        if (!matching.isEmpty()) {
            GraftScreenContext ctx = context(screen, mouseX, mouseY);
            for (GraftScreenPresence p : matching) {
                GraftScreenPresence.Click c = p.clickHandler();
                if (c != null && c.click(ctx, button)) return true;
            }
        }
        // Then eat clicks that land in a revealed panel's empty space (gap), so a
        // carried item can't fall through to the inert vanilla slot behind it. A
        // click on the slot itself is NOT eaten — getHoveredSlot routed it. For a
        // panel-hosted SlotElement in an opaque panel this is a backstop: Phase A
        // panel opacity already eats gap clicks at the MouseHandler level before
        // this fires; it still covers a non-opaque panel of bare slots.
        Set<String> ids = resolvablePanelIds(matching);
        if (ids.isEmpty()) return false;
        MenuKitGraftInput.Resolution r =
                MenuKitGraftInput.resolveHoveredSlot(screen, mouseX, mouseY, ids);
        return r.handled() && r.slot() == null;
    }

    @Override
    public boolean mouseScrolled(AbstractContainerScreen<?> screen, double mouseX,
                                 double mouseY, double scrollX, double scrollY) {
        List<GraftScreenPresence> matching = matching(screen);
        if (matching.isEmpty()) return false;
        GraftScreenContext ctx = context(screen, mouseX, mouseY);
        for (GraftScreenPresence p : matching) {
            GraftScreenPresence.Scroll s = p.scrollHandler();
            if (s != null && s.scroll(ctx, scrollX, scrollY)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(AbstractContainerScreen<?> screen, double mouseX,
                                 double mouseY, int button) {
        List<GraftScreenPresence> matching = matching(screen);
        if (matching.isEmpty()) return false;
        GraftScreenContext ctx = context(screen, mouseX, mouseY);
        for (GraftScreenPresence p : matching) {
            GraftScreenPresence.Release r = p.releaseHandler();
            if (r != null && r.release(ctx, button)) return true;
        }
        return false;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** The presences whose matcher accepts this screen's class. Empty list when none. */
    private static List<GraftScreenPresence> matching(AbstractContainerScreen<?> screen) {
        if (PRESENCES.isEmpty()) return List.of();
        Class<?> screenClass = screen.getClass();
        List<GraftScreenPresence> out = null;
        for (GraftScreenPresence p : PRESENCES) {
            if (p.matcher().matches(screenClass)) {
                if (out == null) out = new ArrayList<>(PRESENCES.size());
                out.add(p);
            }
        }
        return out == null ? List.of() : out;
    }

    /**
     * The panel ids to resolve hover/click against on this screen: the matching
     * {@link GraftScreenPresence}s' panels UNIONED with every panel that
     * currently hosts a {@link SlotElement} ({@link SlotElementRegistry}). Empty
     * when there's nothing to resolve — no presence and no panel-hosted slot — so
     * the caller can short-circuit to PASS without touching {@code menu.slots}.
     *
     * <p>The union is what lets a slot live in a panel with no
     * {@code GraftScreenPresence} at all: its panel id rides in via the registry,
     * and {@link MenuKitGraftInput#resolveHoveredSlot} resolves it through the
     * same creative-aware path as a presence-registered graft.
     */
    private static Set<String> resolvablePanelIds(List<GraftScreenPresence> presences) {
        Set<String> slotElementIds = SlotElementRegistry.activePanelIds();
        if (presences.isEmpty() && slotElementIds.isEmpty()) return Set.of();
        Set<String> ids = new HashSet<>(slotElementIds);
        for (GraftScreenPresence p : presences) ids.add(p.panelId());
        return ids;
    }

    /** Builds the per-frame context, deriving the frame origin from the screen. */
    private static GraftScreenContext context(AbstractContainerScreen<?> screen,
                                              double mouseX, double mouseY) {
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        return new GraftScreenContext(screen, acc.menuKit$getLeftPos(),
                acc.menuKit$getTopPos(), mouseX, mouseY);
    }
}
