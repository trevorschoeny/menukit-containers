package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.ScreenMatcher;
import com.trevorschoeny.menukit.inject.ScreenPanelAdapter;
import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.TriBool;
import com.trevorschoeny.menukit.window.Window;

import net.minecraft.world.inventory.InventoryMenu;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The one-call, no-mixin registration for a panel whose slots have <b>container
 * parity</b> — they appear, click, and sync on <em>every</em> screen that shows
 * the player inventory (survival, creative, and every chest/furnace/modded
 * container), default-on, opt-out per screen.
 *
 * <h3>What one registration drives</h3>
 *
 * <pre>{@code
 * // Consumer COMMON initializer (runs both sides):
 * MKCContainerPanel.define("inventory-plus:pockets")
 *     .at(MenuRegion.LEFT_ALIGN_TOP, 7)
 *     .style(PanelStyle.RAISED)
 *     .parity(ScreenMatcher.all())                       // default; opt out per screen
 *     .chrome(() -> List.of(new Button(...)))             // client-only, built lazily
 *     .addSlot(SlotSpec.at("pockets").count(9)             // slots flow + wrap to width
 *             .storage(player -> POCKETS.bind(player)))
 *     .register();
 * }</pre>
 *
 * From that single declaration the library:
 * <ol>
 *   <li>builds the real, synced {@link MKCSlot}s on the player's own
 *       {@code InventoryMenu} (the library-owned {@code MKCInventoryMenuMixin},
 *       both sides) — <b>no consumer mixin</b>;</li>
 *   <li>projects the same slots onto every foreign container menu
 *       ({@link MKCSlotProjection}, both sides);</li>
 *   <li>gets the creative item-picker for free (the existing creative wrapper
 *       wraps whatever {@link MKCSlot}s sit on {@code player.inventoryMenu});</li>
 *   <li>on the client, renders the panel's chrome + slot presentation on every
 *       screen the {@link ScreenMatcher} accepts (a {@link ScreenPanelAdapter}
 *       wired via {@code onMatching}).</li>
 * </ol>
 *
 * <h3>The client/server split this honestly reflects</h3>
 *
 * The server must build a menu's slots <em>at construction time, before any
 * client exists</em>, so the slot <b>data</b> is declared side-neutrally (the
 * {@link SlotSpec} recipes) and travels onto every container menu
 * unconditionally — that is the only sync-correct default. The per-screen
 * {@link #parity} scope therefore gates the client <b>presentation</b> (whether
 * the panel + its slots are drawn and interactive on a given screen), not
 * whether the slot data exists. A slot you opt a screen out of is still present
 * on that menu; it simply isn't shown there. The chrome ({@link #chrome}) is a
 * client-only {@link Supplier} so no GUI objects are constructed on a dedicated
 * server.
 *
 * <p>{@link #register} is safe to call from the consumer's common initializer:
 * it touches only side-neutral data (recipes, the projection source, the stored
 * definition). The client chrome is materialised later, on the client only, from
 * {@link #wireRegisteredChrome} (invoked by {@code MKCClient}).
 */
public final class MKCContainerPanel {

    private MKCContainerPanel() {}

    // ── Registered definitions (read on the client to wire chrome) ──────
    private static final List<Definition> DEFINITIONS = new CopyOnWriteArrayList<>();

    // The single foreign-menu projection source is registered lazily on the
    // first define().register() — one source covers every parity panel because
    // its factory applies the whole ParitySlotRegistry.
    private static volatile boolean projectionSourceRegistered = false;

    /** Immutable snapshot of one registered container panel. */
    private record Definition(String panelId,
                              RegionAnchor<MenuRegion> placement,
                              int padding,
                              PanelStyle style,
                              boolean opaque,
                              ScreenMatcher parityScope,
                              Supplier<List<PanelElement>> chrome,
                              @Nullable BooleanSupplier visibleWhen,
                              int pinnedHeight,
                              int pinnedWidth,
                              List<SlotSpec> slots) {}

    /** Begins a container-parity panel registration with the given panel id. */
    public static Builder define(String panelId) {
        return new Builder(panelId);
    }

    /** Fluent configuration; terminates in {@link #register()}. */
    public static final class Builder {
        private final String panelId;
        private @Nullable RegionAnchor<MenuRegion> placement = null;   // required
        private int padding = ScreenPanelAdapter.DEFAULT_PADDING;
        private PanelStyle style = PanelStyle.NONE;
        private boolean opaque = true;
        private ScreenMatcher parityScope = ScreenMatcher.all();       // default-on everywhere
        private Supplier<List<PanelElement>> chrome = List::of;        // no chrome by default
        private @Nullable BooleanSupplier visibleWhen = null;          // null = always visible
        private int pinnedHeight = -1;                                 // -1 = auto-size to visible content
        private int pinnedWidth = -1;                                  // -1 = auto-size to visible content
        private final List<SlotSpec> slots = new ArrayList<>();

        Builder(String panelId) {
            this.panelId = panelId;
        }

        /** Region placement + explicit content padding (default-priority stacking). */
        public Builder at(MenuRegion region, int padding) {
            this.placement = new RegionAnchor<>(region, RegionAnchor.DEFAULT_PRIORITY);
            this.padding = padding;
            return this;
        }

        /** Region placement with an explicit stacking priority + padding. */
        public Builder at(RegionAnchor<MenuRegion> anchor, int padding) {
            this.placement = anchor;
            this.padding = padding;
            return this;
        }

        /** Panel background style. Default {@link PanelStyle#NONE} (flush — the slots draw their own frames). */
        public Builder style(PanelStyle style) {
            this.style = style;
            return this;
        }

        /** Interaction opacity (default {@code true} — the panel eats clicks over its bounds). */
        public Builder opaque(boolean opaque) {
            this.opaque = opaque;
            return this;
        }

        /**
         * Screen scope for the panel's <em>presentation</em>. Default
         * {@link ScreenMatcher#all()} — shown on every container screen. Opt a
         * screen out with {@link ScreenMatcher#allExcept}. Does NOT affect where
         * the slot data lives (that's every container menu); see the class doc.
         */
        public Builder parity(ScreenMatcher scope) {
            this.parityScope = scope;
            return this;
        }

        /**
         * The panel's display elements (buttons, labels, decorations), built
         * lazily on the client only. The slots are added automatically as
         * {@link SlotElement}s from {@link #addSlot}; this supplies the rest of
         * the panel's chrome. MUST NOT be invoked on a dedicated server — it
         * isn't (the library calls it client-side from {@code MKCClient}).
         */
        public Builder chrome(Supplier<List<PanelElement>> chrome) {
            this.chrome = chrome;
            return this;
        }

        /**
         * Gates the WHOLE panel's visibility (chrome + slot presentation) on a
         * client-side predicate — the entire parity panel appears/disappears as
         * one. Default (unset): always visible. When the supplier returns false,
         * the chrome, background, and slot elements all stop rendering together;
         * per-group {@code revealWhen} still applies on top when the panel is
         * shown. Client-side presentation gate (the underlying
         * {@link Panel#showWhen}); does not by itself drive server slot-sync, so
         * pair it with per-group {@code revealWhen} when the slots are
         * sync-critical (as the validator pool does).
         */
        public Builder showWhen(BooleanSupplier visibleWhen) {
            this.visibleWhen = visibleWhen;
            return this;
        }

        /** Adds a slot group to this panel (its recipe + its on-screen presentation). */
        public Builder addSlot(SlotSpec spec) {
            this.slots.add(spec);
            return this;
        }

        /**
         * Pins the panel's height to a fixed pixel extent (default: auto-size to
         * visible content). Use when the panel is bottom/right-anchored AND its
         * visible content fluctuates between frames — e.g. a group revealed by a
         * per-frame {@code revealWhen} predicate. Without a pin, an anchored
         * panel's auto-size changes when a group reveals, which moves the
         * already-visible slots; if a reveal predicate reads cursor-over-slot
         * state, that motion can un-trigger the reveal and oscillate (a flash).
         * Pinning the height reserves the panel's full extent so reveals never
         * shift existing slots. Mirrors {@link Panel#pinnedHeight(int)}; width
         * stays adaptive.
         *
         * @param h pinned content height in pixels
         */
        public Builder pinnedHeight(int h) {
            this.pinnedHeight = h;
            return this;
        }

        /**
         * Pins the panel's width to a fixed pixel extent (default: auto-size to
         * visible content). The width twin of {@link #pinnedHeight(int)} — and
         * the one that matters for a RIGHT-anchored, hover-reveal panel: when a
         * revealed group is WIDER than the already-visible ones, an adaptive
         * width grows the panel, and a right anchor turns that growth into a
         * LEFTWARD shift of the existing slots. If the reveal predicate reads
         * cursor-over-slot state, that shift slides the hover target out from
         * under the cursor and oscillates (the flash). Pinning the width
         * reserves the panel's full horizontal extent so reveals never shift
         * existing slots sideways. Mirrors {@link Panel#pinnedWidth(int)}.
         *
         * @param w pinned content width in pixels
         */
        public Builder pinnedWidth(int w) {
            this.pinnedWidth = w;
            return this;
        }

        /** Pins BOTH axes — convenience for {@link #pinnedWidth(int)} +
         *  {@link #pinnedHeight(int)}, the bulletproof "footprint never changes
         *  on reveal" form. */
        public Builder size(int w, int h) {
            this.pinnedWidth = w;
            this.pinnedHeight = h;
            return this;
        }

        /**
         * Finalises the registration. Side-neutral: registers each slot recipe,
         * ensures the foreign-menu projection source exists, and stores the
         * definition for client chrome wiring. Call once at mod init (common
         * initializer).
         */
        public void register() {
            if (placement == null) {
                throw new IllegalStateException(
                        "MKCContainerPanel '" + panelId + "': .at(region, padding) is "
                        + "required before register().");
            }

            // 1. Register each slot's recipe under a derived, collision-free panel
            //    id (container panel id + group). Both build seams read these.
            //    Then ARM any inline behavior the spec declared, by Address — this
            //    is sugar over Window.slot(addr).set: behavior still lives in the
            //    engine (keyed by Address, resolved when the slot appears), it's
            //    just declared where the group is declared instead of in a separate,
            //    forgettable pass. Side-neutral (the engine declarations are pure
            //    data), matching the consumer's own common-init arming.
            for (SlotSpec spec : slots) {
                if (spec.storageFactory() == null) {
                    throw new IllegalStateException(
                            "MKCContainerPanel '" + panelId + "': slot group '"
                            + spec.groupId() + "' needs .storage(...) before register().");
                }
                ParitySlotRegistry.register(slotPanelId(panelId, spec.groupId()), spec);
                armInlineBehavior(panelId, spec);
            }

            // 2. Ensure the one foreign-menu projection source exists. Its factory
            //    applies the whole registry, so a single source covers every parity
            //    panel; appliesTo excludes the player's own InventoryMenu (served by
            //    the library inventory mixin, so it must not be double-appended).
            ensureProjectionSource();

            // 3. Stash for client chrome wiring (read in MKCClient).
            DEFINITIONS.add(new Definition(panelId, placement, padding, style, opaque,
                    parityScope, chrome, visibleWhen, pinnedHeight, pinnedWidth,
                    List.copyOf(slots)));
        }
    }

    /**
     * Arms a {@link SlotSpec}'s inline behavior onto the engine by Address, for every
     * local index in the group. A null inline value leaves the engine default for that
     * key (so an un-declared slot stays exactly vanilla). This is the library half of
     * the inline sugar — identical to the consumer calling
     * {@code Window.slot(address(panelId, groupId, i)).set(KEY, value)} themselves.
     */
    private static void armInlineBehavior(String containerPanelId, SlotSpec spec) {
        SlotGate gate = spec.gateValue();
        TriBool binding = spec.bindingValue();
        TriBool mending = spec.mendingValue();
        QuickMoveParticipation quickMove = spec.quickMoveValue();
        // Nothing declared → nothing to arm.
        if (gate == null && binding == null && mending == null && quickMove == null) return;

        for (int i = 0; i < spec.count(); i++) {
            Address a = address(containerPanelId, spec.groupId(), i);
            if (gate != null)      Window.slot(a).set(MKCBehaviorKeys.GATING, gate);
            if (binding != null)   Window.slot(a).set(MKCBehaviorKeys.BINDING, binding);
            if (mending != null)   Window.slot(a).set(MKCBehaviorKeys.MENDING, mending);
            if (quickMove != null) Window.slot(a).set(MKCBehaviorKeys.QUICK_MOVE, quickMove);
        }
    }

    private static synchronized void ensureProjectionSource() {
        if (projectionSourceRegistered) return;
        projectionSourceRegistered = true;
        MKCSlotProjection.register(
                menu -> !(menu instanceof InventoryMenu),
                ParitySlotRegistry::applyTo);
    }

    /** The id the built {@link MKCSlot}s carry and the {@link SlotElement}s resolve against. */
    private static String slotPanelId(String containerPanelId, String groupId) {
        return containerPanelId + ":" + groupId;
    }

    // ── Created-slot address minter (THE public way to name a parity slot) ──

    /**
     * THE canonical {@link Address} of one slot created by a container-parity
     * {@code define(...)} — use this to name a {@code define()}-created slot when
     * arming behavior or reading state by address. Never reconstruct the address by
     * hand.
     *
     * <p><b>Why a dedicated minter:</b> a parity slot does NOT carry the container
     * panel id passed to {@link #define(String)} as its address panel id — it carries
     * the library-derived id {@code containerPanelId + ":" + groupId} (so each group
     * lives in its own collision-free address sub-space). That derivation is an
     * internal rule; this method applies it for you. The raw
     * {@code CreatedSlotAdapter.addressOf} encoding is {@code @Internal} precisely so a
     * consumer can't hand it the bare {@code define()} id and silently mint a
     * non-resolving address — each slot-creation path has its own public minter
     * ({@code MKCContainerPanel.address} for parity slots; {@code MKCScreenHandler.address}
     * for custom-menu slots). So:
     *
     * <pre>{@code
     * // Container-parity slot — derived id applied internally:
     * Window.slot(MKCContainerPanel.address("inventory-plus:pockets", "pockets", i))
     *       .set(MKCBehaviorKeys.GATING, gate);
     *
     * // Custom-menu slot (different path) — bare declared id, its own minter:
     * Window.slot(MKCScreenHandler.address("inventory-plus:menu:main", "main", i))
     *       .set(MKCBehaviorKeys.GATING, gate);
     * }</pre>
     *
     * <p>For most consumers the {@link SlotSpec} inline verbs ({@code .gate(...)},
     * {@code .binding()}, {@code .mending()}, {@code .quickMove(...)}) arm behavior
     * for you and you never call this directly; it remains the explicit seam for
     * by-address arming and for reading per-slot state by address.
     *
     * @param containerPanelId the id passed to {@link #define(String)}
     * @param groupId          the slot group id (the {@link SlotSpec#at} group id)
     * @param localIndex       the slot's index within its group
     */
    public static Address address(String containerPanelId, String groupId, int localIndex) {
        return CreatedSlotAdapter.addressOf(slotPanelId(containerPanelId, groupId), groupId, localIndex);
    }

    // ── Client chrome wiring ────────────────────────────────────────────

    /**
     * Builds each registered panel's chrome {@link Panel} (chrome elements +
     * auto-generated {@link SlotElement}s) and wires a {@link ScreenPanelAdapter}
     * scoped by the panel's parity matcher. Client-only — invoked once from
     * {@code MKCClient.onInitializeClient}, after all consumer common-init
     * {@code register()} calls have populated {@link #DEFINITIONS} (Fabric runs
     * all main entrypoints before any client entrypoint).
     */
    @ApiStatus.Internal
    public static void wireRegisteredChrome() {
        for (Definition def : DEFINITIONS) {
            List<PanelElement> elements = new ArrayList<>(def.chrome().get());

            // Movement ④ — slots flow + wrap reactively, not a fixed grid. One
            // SlotElement per logical slot, built at the flow's origin (0,0); the
            // SlotFlowElement owns their per-frame positions, flowing only the
            // currently-visible ones into the panel's width and hugging the result
            // (no fixed columns, no reserved empty space). Declared order across
            // groups is the flow order — a group's slots are a contiguous run, so
            // revealing a group simply joins its slots to the stream.
            List<SlotElement> slotElements = new ArrayList<>();
            for (SlotSpec spec : def.slots()) {
                String sid = slotPanelId(def.panelId(), spec.groupId());
                for (int i = 0; i < spec.count(); i++) {
                    SlotElement el = new SlotElement(sid, spec.groupId(), i, 0, 0);
                    // Group-level hover tooltip flows from the spec onto each built
                    // SlotElement (the consumer can't reach these library-built instances).
                    if (spec.tooltip() != null) {
                        el.tooltip(spec.tooltip());
                    }
                    slotElements.add(el);
                }
            }
            if (!slotElements.isEmpty()) {
                elements.add(new SlotFlowElement(slotElements, 0, 0));
            }

            Panel panel = Panel.builder(def.panelId())
                    .elements(elements)
                    .visible(true)
                    .style(def.style())
                    .position(PanelPosition.BODY)
                    .build()
                    .opaque(def.opaque());
            // Whole-panel visibility gate (chrome + slots toggle as one).
            if (def.visibleWhen() != null) {
                panel.showWhen(def.visibleWhen());
            }
            // Pinned height (anchored-panel reveal stability) when declared.
            if (def.pinnedHeight() >= 0) {
                panel.pinnedHeight(def.pinnedHeight());
            }
            if (def.pinnedWidth() >= 0) {
                panel.pinnedWidth(def.pinnedWidth());
            }

            new ScreenPanelAdapter(panel, def.placement(), def.padding())
                    .onMatching(def.parityScope());
        }
    }
}
