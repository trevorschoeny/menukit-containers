package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.inject.ScreenMatcher;
import com.trevorschoeny.menukit.inject.ScreenPanelAdapter;
import com.trevorschoeny.menukit.mixin.AbstractContainerMenuInvoker;
import com.trevorschoeny.menukit.window.SlotNames;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Slots real, server-synced, persistent slots onto a vanilla menu — the
 * library half of the §0045 "consumer-composed slot" pattern (IP Pockets,
 * IP Equipment Slots).
 *
 * <h3>Who calls this</h3>
 *
 * The <b>consumer</b> calls {@code MKCSlots} from inside <b>their own</b>
 * mixin, injected at {@code TAIL} on the vanilla menu's constructor — e.g.
 * {@code InventoryMenu.<init>}. The library ships no such mixin and no slot
 * registry (§0019 / §0045): owning a vanilla injection point, or providing a
 * hook consumers register into, is the platform-shaped move library-not-platform
 * forbids. This class is a <em>stateless helper</em> the consumer's mixin
 * invokes imperatively — it registers and applies nothing on its own.
 *
 * <p>Because the vanilla constructor re-runs on every menu reconstruction
 * (login, respawn, dimension change, on both sides), registering from there means
 * the slots re-appear automatically — no separate lifecycle hook.
 *
 * <h3>What you get</h3>
 *
 * The registered slots are {@link MKCSlot}s over a standalone {@link Panel} +
 * {@link SlotGroup} (neither needs a {@code MKCScreenHandler}). That buys
 * the canonical inertness contract (§0021): when the panel is hidden the slots
 * are invisible <em>and</em> inert — {@code getItem} returns EMPTY, they reject
 * placement/pickup, and vanilla skips them for render + hover. Toggle the
 * panel's visibility at runtime (§0022) to reveal/hide the whole group.
 *
 * <h3>Hover-reveal &amp; the sided-visibility subtlety</h3>
 *
 * {@link #revealWhen(BooleanSupplier)} drives the panel's visibility from a
 * <b>client-side</b> predicate (e.g. "is the associated hotbar slot hovered").
 * The helper wraps it side-aware: <b>on the server the panel is always
 * visible</b> so {@code getItem} returns real content and vanilla's
 * {@code broadcastChanges} keeps syncing it; <b>on the client</b> the panel
 * gates render + input on the predicate. This is why content survives even
 * while the panel is hidden: hiding is a client rendering decision, not a
 * server content state. The predicate MUST be client-safe — it is only ever
 * invoked client-side, but it should live in a client-only class so the JVM
 * never class-loads client types on a dedicated server.
 *
 * <h3>Persistence &amp; metadata</h3>
 *
 * Item <em>content</em> persists by giving the slot a player- (or block-)
 * attached {@link Storage} (see {@link StorageAttachment}); sync is free via
 * vanilla's slot protocol (§0034). For per-slot <em>metadata</em> (lock flags,
 * etc.), back the slot with a {@link KeyedStorage} that returns a stable
 * {@link PersistentContainerKey} — M1's {@code SlotStateChannel} API then works
 * against the registered slots (§0045).
 *
 * <h3>Creative/survival parity (§0051)</h3>
 *
 * RegisteredSlots slots round-trip in <b>both</b> game modes with no consumer code.
 * Survival edits reach the slot through vanilla's container-click path; a
 * creative edit that vanilla's {@code handleSetCreativeModeSlot} would silently
 * discard (the registered index is past vanilla's {@code [1,45]} slot range) is
 * routed to the registered slot's backing {@code Storage} by the library's
 * creative-set-slot bridge ({@code CreativeSetSlotMixin}). Direct
 * click-to-place works in both modes; per-slot metadata (M1) lands with it.
 *
 * <h3>Known limitation</h3>
 *
 * Shift-click (quick-move) <em>into</em> a registered slot is not free: vanilla's
 * {@code quickMoveStack} has no knowledge of registered slots. Direct click-to-place
 * works out of the box (in both modes, per above); full shift-click routing is
 * consumer-side work (the consumer may override {@code quickMoveStack} in their
 * own mixin) or deferred.
 *
 * <h3>Recipe</h3>
 *
 * <pre>{@code
 * // Mod init — declare the persistent storage once:
 * public static final StorageAttachment<Player, NonNullList<ItemStack>> POCKETS =
 *     StorageAttachment.playerAttached("inventory-plus", "pockets", 3);
 *
 * // Consumer mixin — @Inject(method = "<init>", at = @At("TAIL")) on InventoryMenu:
 * Storage storage = POCKETS.bind(player);              // or a KeyedStorage for metadata
 * MKCSlots.RegisteredSlots pockets = MKCSlots.onto((AbstractContainerMenu)(Object) this, player)
 *     .panel("inventory-plus:pockets")
 *     .group("pockets")
 *     .storage(storage)
 *     .layout(originX, originY, 3)                      // screen-relative origin + columns
 *     .revealWhen(() -> PocketHoverState.isRevealed())  // client-side predicate
 *     .register();
 *
 * // Client init (ONCE, NOT per menu construction) — wire the PRESENTATION half.
 * // register() above added the synced slot data; this draws the slots:
 * MKCSlots.renderGroup("inventory-plus:pockets", "pockets", 3, 3,
 *         MenuRegion.RIGHT_ALIGN_TOP, ScreenPanelAdapter.DEFAULT_PADDING,
 *         ScreenMatcher.all());
 * }</pre>
 */
public final class MKCSlots {

    /** Vanilla slot pitch in pixels (16px slot + 2px frame). */
    public static final int SLOT_PITCH = 18;

    /**
     * Off-screen sentinel for a registered slot's vanilla {@code Slot.x/y} (§0047).
     * RegisteredSlots slots are helper-rendered, so vanilla must not draw them at a fixed
     * coordinate — that would double-render the moment a slot's presentation
     * position moves. Parking the vanilla coords far off-screen makes vanilla's
     * render + hit a harmless no-op; the slot helpers own presentation via
     * {@code MKCSlot.renderX()/renderY()}.
     */
    private static final int OFFSCREEN = -10000;

    private MKCSlots() {}

    /** Capitalizes the first letter for a default display label from a group id. */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "Slot";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * The result of a slot: the live {@link Panel} and {@link SlotGroup} that
     * were created, plus the flat slot-index range appended to the menu (for
     * consumers that need to walk the registered slots directly).
     *
     * <p>{@code register()} adds only the synced slot DATA. To make the slots
     * actually draw + take input, call
     * {@link MKCSlots#renderGroup(String, String, int, int, MenuRegion, int, ScreenMatcher)}
     * ONCE at client init with the same {@code panelId} / {@code groupId} —
     * that is the PRESENTATION half this handle's {@code register()} omits.
     */
    public record RegisteredSlots(Panel panel, SlotGroup group, int flatStart, int flatEnd,
                          List<MKCSlot> slots) {}

    /**
     * Begins a slot onto {@code menu} owned by {@code player}. Call from the
     * consumer's {@code @Inject(at = TAIL)} on the vanilla menu's constructor,
     * where both the menu ({@code this}) and the owning player are in scope.
     */
    public static Builder onto(AbstractContainerMenu menu, Player player) {
        return new Builder(menu, player);
    }

    /**
     * Turnkey rendering for a registered {@code .onto} slot group — call ONCE at
     * client init (not per menu construction). {@link Builder#register()} adds the
     * synced slot DATA; this adds the PRESENTATION: a panel of {@link SlotElement}s
     * for the group, wired through a {@link ScreenPanelAdapter} so the slots actually
     * draw + take input on every matching screen. Reveal is already gated by the
     * {@code .onto} {@code revealWhen} predicate (each SlotElement self-hides while its
     * slot is inert), so it is not repeated here. The panel id is the SAME id passed to
     * {@link Builder#panel}; the SlotElements resolve the live MKCSlots by identity each
     * frame, so one registration works on every screen the slots appear on.
     */
    public static void renderGroup(String panelId, String groupId, int count, int columns,
            RegionAnchor<MenuRegion> anchor, int padding, ScreenMatcher screens) {
        // One SlotElement per logical slot, laid out from the panel origin on the
        // standard 18px pitch — matching the seed layout register() handed each
        // MKCSlot, so element and slot agree on where the slot sits. Mirrors
        // MKCContainerPanel.wireRegisteredChrome's element-build loop (the
        // container-parity path), which is the same data→presentation split done
        // here for the lower-level .onto path.
        int cols = Math.max(1, columns);
        List<PanelElement> elements = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int ex = (i % cols) * SLOT_PITCH;
            int ey = (i / cols) * SLOT_PITCH;
            elements.add(new SlotElement(panelId, groupId, i, ex, ey));
        }

        // A presentation-only panel: its id is suffixed (":present") so it never
        // collides with the .onto panel that drives slot inertness — that panel is
        // a data/visibility carrier with no elements; this one carries the
        // SlotElements. Style NONE + opaque(false): the slots are click-through
        // holes (vanilla owns the slot click; see SlotElement), so the panel must
        // not eat input over them.
        Panel panel = Panel.builder(panelId + ":present")
                .elements(elements)
                .visible(true)
                .style(PanelStyle.NONE)
                .position(PanelPosition.BODY)
                .build()
                .opaque(false);

        // The ScreenPanelAdapter is the presentation half register() omits: it
        // anchors the panel to a screen region and draws it on every screen the
        // matcher accepts. SlotElements resolve their live MKCSlot by identity
        // each frame, so this single registration covers every screen the slots
        // appear on (survival inventory, creative, etc.).
        new ScreenPanelAdapter(panel, anchor, padding).onMatching(screens);
    }

    /**
     * Bare-{@link MenuRegion} convenience overload of
     * {@link #renderGroup(String, String, int, int, RegionAnchor, int, ScreenMatcher)} —
     * wraps the region in a {@link RegionAnchor} at {@link RegionAnchor#DEFAULT_PRIORITY}
     * for consumers who don't care about sibling stacking order within the region.
     */
    public static void renderGroup(String panelId, String groupId, int count, int columns,
            MenuRegion region, int padding, ScreenMatcher screens) {
        renderGroup(panelId, groupId, count, columns,
                new RegionAnchor<>(region, RegionAnchor.DEFAULT_PRIORITY), padding, screens);
    }

    /** Fluent configuration for a single slot. Terminates in {@link #register()}. */
    public static final class Builder {
        private final AbstractContainerMenu menu;
        private final Player player;

        private String panelId = "menukit:slot";
        private String groupId = "slot";
        private Storage storage;                              // required
        private int columns = 9;
        private int originX = 0;
        private int originY = 0;
        private BooleanSupplier revealWhen = null;            // null => always client-visible
        private String displayLabel = null;                  // null => capitalized groupId

        Builder(AbstractContainerMenu menu, Player player) {
            this.menu = menu;
            this.player = player;
        }

        /** Panel id (unique per slot). Used for inertness + render anchoring. */
        public Builder panel(String id) { this.panelId = id; return this; }

        /** Slot-group id within the panel. */
        public Builder group(String id) { this.groupId = id; return this; }

        /**
         * Where items live. Required. Use a {@link StorageAttachment}-bound
         * storage for content persistence, or a {@link KeyedStorage} (which
         * declares its own {@link PersistentContainerKey}) for per-slot
         * metadata persistence on top.
         */
        public Builder storage(Storage storage) { this.storage = storage; return this; }

        /**
         * Slot layout: screen-relative origin (added to the screen's
         * {@code leftPos}/{@code topPos} at render time) and column count.
         * Slots fill left-to-right, top-to-bottom on an 18px grid.
         */
        public Builder layout(int originX, int originY, int columns) {
            this.originX = originX;
            this.originY = originY;
            this.columns = Math.max(1, columns);
            return this;
        }

        /**
         * Client-side reveal predicate. The panel (and therefore the whole
         * registered group) is visible + interactive on the client only while
         * this returns true; the server keeps the slots syncing regardless
         * (see class javadoc). MUST be client-safe. If unset, the group is
         * always visible on the client.
         */
        public Builder revealWhen(BooleanSupplier clientReveal) {
            this.revealWhen = clientReveal;
            return this;
        }

        /**
         * Display label for these slots' names (a window-display concern, §0042 MK-side).
         * Each slot is named {@code "<label> <ordinal>"} (or just {@code "<label>"} for a
         * single slot), registered against the slot's address so {@code WindowSignals
         * .hoveredName()} / a consumer's {@code SlotNames} lookup returns it. Defaults to
         * the capitalized {@code groupId}. A consumer wanting a specific per-slot name can
         * still call {@code SlotNames.override(address, name)} afterward.
         */
        public Builder label(String displayLabel) {
            this.displayLabel = displayLabel;
            return this;
        }

        /**
         * Builds the standalone {@link Panel}/{@link SlotGroup}, constructs the
         * inertness-aware {@link MKCSlot}s over a {@link StorageContainerAdapter},
         * and appends them to the menu (via the vanilla {@code addSlot} invoker,
         * which keeps the sync-tracking lists consistent). Returns the handle.
         */
        public RegisteredSlots register() {
            if (storage == null) {
                throw new IllegalStateException(
                        "MKCSlots: storage() is required before register()");
            }

            // 1. Standalone SlotGroup — storage + layout only. Behavior
            //    (gating/quick-move/binding/mending) is not carried here; it
            //    resolves from the engine by each slot's address (default = vanilla),
            //    armed by the consumer via Window.slot(address).set(...).
            SlotGroup group = new SlotGroup(
                    groupId, storage,
                    /*shiftClickPriority*/ 100, columns, /*rowGapAfter*/ -1, /*rowGapSize*/ 0);

            // 2. Standalone Panel — no PanelOwner (this isn't a MKCScreenHandler).
            //    Style NONE: the consumer's render adapter draws the frame; the
            //    Panel itself carries no elements, only the visibility flag the
            //    slots read for inertness.
            Panel panel = Panel.builder(panelId)
                    .visible(true)
                    .style(PanelStyle.NONE)
                    .position(PanelPosition.BODY)
                    .build();

            // Side-aware reveal: server always visible (so getItem returns real
            // content and broadcastChanges syncs it); client gates on the
            // consumer's hover predicate. The predicate is only evaluated when
            // isClientSide() is true, so a client-only predicate never runs
            // (nor class-loads) on the server.
            final BooleanSupplier reveal = this.revealWhen;
            panel.showWhen(() ->
                    !player.level().isClientSide()    // server side: always "visible"
                    || reveal == null                 // no predicate: always visible
                    || reveal.getAsBoolean());         // client side: gate on hover

            // 3. Build + append the registered slots.
            //
            // §0047: registered slots are presented by their SlotElement on the
            // panel pipeline (which renders the slot inline and keeps its
            // presentation position current), so their position is mutable
            // presentation, not frozen structure. The vanilla Slot.x/y
            // are parked OFF-SCREEN so vanilla never draws or hit-tests them at a
            // fixed spot (which would double-render once a slot moves); the real,
            // runtime-movable position lives in the slot's renderX/renderY — seeded
            // here from the layout, changeable later via setRenderPosition.
            StorageContainerAdapter adapter = new StorageContainerAdapter(storage);
            AbstractContainerMenuInvoker inv = (AbstractContainerMenuInvoker) menu;

            int flatStart = menu.slots.size();
            List<MKCSlot> mkSlots = new ArrayList<>();
            for (int local = 0; local < storage.size(); local++) {
                int x = originX + (local % columns) * SLOT_PITCH;
                int y = originY + (local / columns) * SLOT_PITCH;
                MKCSlot slot = new MKCSlot(
                        adapter, local, OFFSCREEN, OFFSCREEN, group, panel, groupId, local);
                slot.setRenderPosition(x, y);     // real (mutable) presentation position
                inv.mk$addSlot(slot);
                mkSlots.add(slot);
            }
            int flatEnd = menu.slots.size();
            group.setFlatIndexRange(flatStart, flatEnd);

            // Display names — client-only (naming is a window-display concern; the
            // server never reads them, and this keeps MK display types off the server
            // path). Keyed by the SAME address minter the window uses to resolve a
            // created slot, so WindowSignals.hoveredName() matches. §0042: MK never
            // sees MKCSlot — CreatedSlotAdapter maps (panel,group,index) → Address.
            if (player.level().isClientSide()) {
                String label = displayLabel != null ? displayLabel : capitalize(groupId);
                int size = storage.size();
                for (int local = 0; local < size; local++) {
                    SlotNames.override(
                            CreatedSlotAdapter.addressOf(panelId, groupId, local),
                            size <= 1 ? label : label + " " + (local + 1));
                }
            }

            return new RegisteredSlots(panel, group, flatStart, flatEnd, List.copyOf(mkSlots));
        }
    }
}
