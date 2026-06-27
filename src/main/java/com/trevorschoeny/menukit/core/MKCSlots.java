package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.mixin.AbstractContainerMenuInvoker;

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
 * {@link SlotGroup} (neither needs a {@code MenuKitScreenHandler}). That buys
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
 * // Stash `pockets` (panel + group) so the client render adapter can draw it.
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

    /**
     * The result of a slot: the live {@link Panel} and {@link SlotGroup} that
     * were created, plus the flat slot-index range appended to the menu (for
     * consumers that need to walk the registered slots directly). The consumer
     * wires the client render adapter against {@code panel} / {@code group}.
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

    /** Fluent configuration for a single slot. Terminates in {@link #register()}. */
    public static final class Builder {
        private final AbstractContainerMenu menu;
        private final Player player;

        private String panelId = "menukit:slot";
        private String groupId = "slot";
        private Storage storage;                              // required
        private InteractionPolicy policy = InteractionPolicy.free();
        private QuickMoveParticipation qmp = QuickMoveParticipation.BOTH;
        private int columns = 9;
        private int originX = 0;
        private int originY = 0;
        private BooleanSupplier revealWhen = null;            // null => always client-visible
        private boolean bindsCursedItems = false;             // Curse of Binding enforcement (opt-in)

        private boolean mendsFromXp = false;                  // XP-orb repair participation (opt-in)

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

        /** What operations are allowed. Default {@link InteractionPolicy#free()}. */
        public Builder policy(InteractionPolicy policy) { this.policy = policy; return this; }

        /** How the group participates in shift-click routing. Default BOTH. */
        public Builder quickMove(QuickMoveParticipation qmp) { this.qmp = qmp; return this; }

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
         * Enables the vanilla Curse of Binding on the registered slots: an item
         * carrying the binding curse ({@code PREVENT_ARMOR_CHANGE}) cannot be
         * removed from the slot <em>while alive</em> — survival only; creative
         * bypasses (matching vanilla, and creative removal routes through the
         * §0051 set-slot bridge which never consults {@code mayPickup}). Off by
         * default; opt in for equipment-semantic slots. Death is orthogonal: a
         * bound item still drops/keeps at death per its {@code DropRule}.
         */
        public Builder bindsCursedItems() {
            this.bindsCursedItems = true;
            return this;
        }

        /**
         * Enables vanilla Mending on the registered slots: a damaged item carrying
         * the {@code REPAIR_WITH_XP} effect, sitting in one of these slots, joins
         * the XP-orb repair pool — so it mends from collected XP exactly like a
         * worn armor piece or held tool does. Off by default; opt in for
         * equipment-semantic slots. Generic registered-slot vanilla mechanic
         * (library-owned, same line as death-drop §0052 and binding §0053).
         */
        public Builder mendsFromXp() {
            this.mendsFromXp = true;
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

            // 1. Standalone SlotGroup — owns the behavioral policy for these slots.
            SlotGroup group = new SlotGroup(
                    groupId, storage, policy, qmp, /*shiftClickPriority*/ 100,
                    columns, /*rowGapAfter*/ -1, /*rowGapSize*/ 0);
            group.setBindsCursedItems(bindsCursedItems);
            group.setMendsFromXp(mendsFromXp);

            // 2. Standalone Panel — no PanelOwner (this isn't a MenuKitScreenHandler).
            //    Style NONE: the consumer's render adapter draws the frame; the
            //    Panel itself carries no elements, only the visibility flag the
            //    slots read for inertness.
            Panel panel = new Panel(panelId, List.of(), /*visible*/ true,
                    PanelStyle.NONE, PanelPosition.BODY, /*toggleKey*/ -1);

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
            // §0047: registered slots are helper-rendered (MKCSlotRender /
            // MKCSlotInput own their presentation), and their position is
            // mutable presentation, not frozen structure. The vanilla Slot.x/y
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
                inv.menukit$addSlot(slot);
                mkSlots.add(slot);
            }
            int flatEnd = menu.slots.size();
            group.setFlatIndexRange(flatStart, flatEnd);

            return new RegisteredSlots(panel, group, flatStart, flatEnd, List.copyOf(mkSlots));
        }
    }
}
