package com.trevorschoeny.menukit.inject;

import com.trevorschoeny.menukit.core.SlotGroupCategory;
import com.trevorschoeny.menukit.mixin.AbstractContainerScreenAccessor;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Library-owned registry of SlotGroupContext {@link SlotGroupPanelAdapter}s
 * that declare targeting via {@code .on(SlotGroupCategory...)}. Listens
 * once on {@link ScreenEvents#AFTER_INIT} and dispatches click + render to
 * the slot-group adapters whose targeting matches the slot groups present
 * on each opened {@link AbstractContainerScreen}.
 *
 * <p>Post-§0042 split companion to MenuKit's
 * {@link ScreenPanelRegistry}. The two registries are parallel — MenuKit's
 * handles MenuContext adapter dispatch on vanilla screens; this one handles
 * SlotGroupContext adapter dispatch. They register independent
 * {@code ScreenEvents.AFTER_INIT} listeners; both fire per screen-open;
 * each runs its own orphan checkpoint and dispatches its own input/render
 * pass.
 *
 * <p><b>Behavior change vs pre-split.</b> Before §0042, slot-group click
 * dispatch happened inside MenuKit-side ScreenPanelRegistry's click hook,
 * before the modal-eat decision — so slot-group clicks fired even when a
 * MenuContext modal was eating the click. After the split, MenuKit's
 * listener registers first; if MenuKit eats (modal up), Fabric's
 * {@code allowMouseClick} stops invoking and this registry's listener
 * never fires — slot-group click is skipped under modal. This is the
 * correct UX (modal blocks all interaction) and matches user expectations.
 * Documented in 16a REPORT §3.
 */
public final class SlotGroupPanelRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("menukit-containers");

    private SlotGroupPanelRegistry() {}

    // ── SlotGroupContext adapter tracking ───────────────────────────────
    //
    // Region-based slot-group adapters move from PENDING → REGISTERED when
    // they declare targeting via .on(SlotGroupCategory...). Strong
    // references — consumers typically hold adapters as static final fields.

    private static final Set<SlotGroupPanelAdapter> PENDING =
            Collections.synchronizedSet(new HashSet<>());

    private static final List<SlotGroupPanelAdapter> REGISTERED =
            Collections.synchronizedList(new ArrayList<>());

    private static volatile boolean checkpointRun = false;

    // ── API called by SlotGroupPanelAdapter ─────────────────────────────

    /** Called from {@link SlotGroupPanelAdapter}'s constructor. */
    public static void trackPending(SlotGroupPanelAdapter adapter) {
        PENDING.add(adapter);
    }

    /** Called from {@link SlotGroupPanelAdapter#on}. */
    public static void markTargetingDeclared(SlotGroupPanelAdapter adapter) {
        PENDING.remove(adapter);
        REGISTERED.add(adapter);
    }

    /** Returns an unmodifiable snapshot of orphan slot-group adapters. */
    public static Set<SlotGroupPanelAdapter> pendingSnapshot() {
        synchronized (PENDING) {
            return Set.copyOf(PENDING);
        }
    }

    /** Returns an unmodifiable snapshot of registered slot-group adapters. */
    public static List<SlotGroupPanelAdapter> registeredSnapshot() {
        synchronized (REGISTERED) {
            return List.copyOf(REGISTERED);
        }
    }

    // ── Initialization ──────────────────────────────────────────────────

    /**
     * Registers the library-owned {@link ScreenEvents#AFTER_INIT} listener.
     * Called once from {@code MenuKitContainersClient.onInitializeClient}.
     * After this, any region-based slot-group adapter that declared
     * targeting will dispatch on matching screens without the consumer
     * writing per-screen boilerplate.
     */
    public static void init() {
        ScreenEvents.AFTER_INIT.register(SlotGroupPanelRegistry::onScreenInit);
    }

    // ── Screen-open dispatch ────────────────────────────────────────────

    private static void onScreenInit(Minecraft client, Screen screen,
                                      int scaledWidth, int scaledHeight) {
        // Orphan checkpoint — runs once per client session. Independent of
        // MenuKit-side ScreenPanelRegistry's checkpoint; both fire on the
        // first screen-init event after init() and throw independently if
        // their respective pending sets are non-empty.
        if (!checkpointRun) {
            checkpointRun = true;
            validateTargetingDeclared();
        }

        // SlotGroupContext dispatch only for AbstractContainerScreen — slot
        // groups exist on container menus, not standalone screens.
        if (!(screen instanceof AbstractContainerScreen<?> acs)) return;

        // Click dispatch via Fabric's allowMouseClick hook. Re-resolve slot
        // groups per click — creative-tab transitions and any future dynamic
        // menu mutate menu.slots between clicks.
        //
        // Behavior change post-§0042: this registers AFTER MenuKit-side
        // ScreenPanelRegistry's listener (since MenuKit init runs first per
        // dependency order). When a MenuKit-side modal eats a click,
        // Fabric stops invoking listeners — so this slot-group dispatch is
        // skipped under modal. Modal blocks all interaction; correct UX.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            dispatchSlotGroupClicks(acs, event.x(), event.y(), event.button());
            return true; // passthrough — vanilla still processes
        });
    }

    /**
     * Re-resolves slot groups for the given screen's menu and dispatches a
     * click to every matching (adapter, category) pair. Called per click
     * from the {@code ScreenMouseEvents.allowMouseClick} hook.
     */
    private static void dispatchSlotGroupClicks(AbstractContainerScreen<?> screen,
                                                  double mouseX, double mouseY, int button) {
        Map<SlotGroupCategory, List<Slot>> resolved = SlotGroupCategories.of(screen.getMenu());
        if (resolved.isEmpty()) return;
        for (SlotGroupPanelAdapter adapter : registeredSnapshot()) {
            List<SlotGroupCategory> targets = adapter.getTargets();
            if (targets == null) continue;
            for (SlotGroupCategory category : targets) {
                List<Slot> slots = resolved.get(category);
                if (slots == null || slots.isEmpty()) continue;
                SlotGroupBounds bounds = computeSlotGroupBounds(slots, screen);
                adapter.mouseClicked(bounds, category, mouseX, mouseY, button, screen);
            }
        }
    }

    /**
     * Called from {@link com.trevorschoeny.menukit.mixin.SlotGroupPanelRenderMixin}
     * at the same injection point as MenuKit's {@code MenuKitPanelRenderMixin}.
     * Dispatches all matching SlotGroupContext adapters for the current
     * screen. Both mixins fire per render — MenuKit's first (renders
     * MenuContext panels), this one second (renders slot-group panels on
     * top).
     *
     * <p>Re-resolves slot groups per frame. Creative-tab switches and other
     * dynamic menu mutations change menu.slots; caching the resolved map at
     * screen-open would produce stale bounds. Per-frame resolution is cheap
     * (resolvers do slot-index subList slicing on menu.slots).
     *
     * <p>Public visibility required because the mixin is in a different
     * package ({@code mixin}) from this class ({@code inject}).
     */
    public static void renderMatchingPanels(AbstractContainerScreen<?> screen,
                                             net.minecraft.client.gui.GuiGraphics graphics,
                                             int mouseX, int mouseY) {
        Map<SlotGroupCategory, List<Slot>> resolved = SlotGroupCategories.of(screen.getMenu());
        if (resolved.isEmpty()) return;
        for (SlotGroupPanelAdapter adapter : registeredSnapshot()) {
            List<SlotGroupCategory> targets = adapter.getTargets();
            if (targets == null) continue;
            for (SlotGroupCategory category : targets) {
                List<Slot> slots = resolved.get(category);
                if (slots == null || slots.isEmpty()) continue;
                SlotGroupBounds bounds = computeSlotGroupBounds(slots, screen);
                adapter.render(graphics, bounds, category, mouseX, mouseY, screen);
            }
        }
    }

    /**
     * Computes the bounding rectangle enclosing the given slots in screen
     * space. Slots store {@code x}/{@code y} relative to the screen frame;
     * the returned bounds are absolute (includes {@code leftPos}/{@code topPos}).
     * Standard slot visual is 16×16.
     */
    private static SlotGroupBounds computeSlotGroupBounds(List<Slot> slots,
                                                           AbstractContainerScreen<?> screen) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Slot slot : slots) {
            int sx = slot.x;
            int sy = slot.y;
            if (sx < minX) minX = sx;
            if (sy < minY) minY = sy;
            if (sx + 16 > maxX) maxX = sx + 16;
            if (sy + 16 > maxY) maxY = sy + 16;
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        int frameLeft = acc.trevorMod$getLeftPos();
        int frameTop = acc.trevorMod$getTopPos();
        return new SlotGroupBounds(
                frameLeft + minX,
                frameTop + minY,
                maxX - minX,
                maxY - minY);
    }

    // ── Orphan enforcement ──────────────────────────────────────────────

    /**
     * Validates that every slot-group adapter constructed during init
     * declared its targeting via {@code .on(SlotGroupCategory...)}.
     * Throws {@link IllegalStateException} naming the orphan panel IDs,
     * failing the client boot visibly.
     */
    public static void validateTargetingDeclared() {
        Set<SlotGroupPanelAdapter> pendingSlotGroup = pendingSnapshot();
        if (pendingSlotGroup.isEmpty()) return;

        StringBuilder msg = new StringBuilder("MenuKit: Containers: ");
        msg.append(pendingSlotGroup.size())
           .append(" SlotGroupPanelAdapter(s) constructed but never declared " +
                   "targeting (.on(SlotGroupCategory...)). Panel IDs: ");
        boolean first = true;
        for (SlotGroupPanelAdapter adapter : pendingSlotGroup) {
            if (!first) msg.append(", ");
            msg.append(adapter.getPanel().getId());
            first = false;
        }
        msg.append(". Fix by adding the missing .on(...) call(s).");
        String message = msg.toString();
        LOGGER.error("[SlotGroupPanelRegistry] {}", message);
        throw new IllegalStateException(message);
    }
}
