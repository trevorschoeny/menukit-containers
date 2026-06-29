package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.TriBool;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A side-neutral, declarative description of one registered-slot group that a
 * {@link MKCContainerPanel} projects onto every container the player opens —
 * the "recipe" half of container parity.
 *
 * <h3>Why a recipe, not a built slot</h3>
 *
 * A real {@link MKCSlot} is a per-menu object: the player's own
 * {@code InventoryMenu}, every chest/furnace they open, and the creative
 * item-picker each carry their <em>own</em> slot instance for the same logical
 * slot. To make a slot appear on all of them from one registration, the panel
 * can't hold a slot — it holds the <em>instructions to build one</em>, applied
 * fresh to each menu (and bound to that menu's player). That is this class: pure
 * data plus a {@code Player -> Storage} factory, evaluated identically on the
 * logical server and the client so the appended slots are byte-identical (the
 * sync-safety contract {@link MKCSlotProjection} documents).
 *
 * <p>It is the parity analogue of the per-call parameters on
 * {@link MKCSlots.Builder}: storage, layout, and the client reveal predicate,
 * expressed once as reusable data rather than imperatively against a single menu.
 * {@link MKCContainerPanel} turns each {@code SlotSpec} into both the real slots
 * (every menu, both sides) and the {@link SlotElement}s that present them (client).
 *
 * <h3>Inline behavior verbs are sugar over the by-address engine path</h3>
 *
 * Gating, quick-move, binding, and mending are armed in THE ONE WINDOW engine by
 * the slot's {@link com.trevorschoeny.menukit.window.Address} — that is where slot
 * behavior <em>lives</em>, identically for a vanilla slot and a created slot. The
 * inline verbs here ({@link #gate}, {@link #accepts}, {@link #binding},
 * {@link #mending}, {@link #quickMove}) do not introduce a second behavior store:
 * they record the consumer's intent on the spec, and
 * {@link MKCContainerPanel.Builder#register()} arms the engine by address for every
 * local index in the group — exactly {@code Window.slot(MKCContainerPanel.address(
 * panelId, groupId, i)).set(KEY, value)}, the same call a consumer could make by
 * hand. The win is purely ergonomic: behavior is declared where the slot group is
 * declared (no separate forgettable arming pass), and the consumer never computes
 * an address or hand-rolls a per-slot loop. The by-address path stays fully usable;
 * inline is sugar, not a replacement.
 *
 * <h3>Storage is a factory, not a bound storage</h3>
 *
 * {@link MKCSlots.Builder#storage} takes an already-{@code player}-bound
 * {@code Storage} because it runs inside a mixin where the player is in scope.
 * A recipe is registered once at init with no player in scope and is applied to
 * many players' menus, so it takes a {@code Function<Player, Storage>} — almost
 * always {@code player -> SOME_ATTACHMENT.bind(player)}. The factory MUST be
 * deterministic and yield the same storage <em>size</em> for a given player on
 * both sides, or the appended slot block desyncs.
 *
 * <p>Mutable fluent builder, consumed once by {@link MKCContainerPanel.Builder#addSlot}.
 */
public final class SlotSpec {

    private final String groupId;
    private final int childX;
    private final int childY;

    private int count = 1;                                // logical slots in this group
    private int columns = 1;                              // grid columns for multi-slot groups
    private @Nullable Function<Player, Storage> storageFactory;   // required
    private @Nullable BooleanSupplier revealWhen = null;  // client-side reveal; null => always
    private @Nullable String label = null;               // MK display name; null => capitalized groupId

    // ── Inline behavior intent (armed by Address in register(); null => default) ──
    // These hold the consumer's declared behavior so register() can arm the engine
    // by each slot's Address. null means "leave the engine default" (gating OPEN,
    // quick-move BOTH, binding/mending FALSE), so an un-declared slot is exactly
    // vanilla — same as never arming it.
    private @Nullable SlotGate gate = null;
    private @Nullable TriBool binding = null;
    private @Nullable TriBool mending = null;
    private @Nullable QuickMoveParticipation quickMove = null;

    private SlotSpec(String groupId, int childX, int childY) {
        this.groupId = groupId;
        this.childX = childX;
        this.childY = childY;
    }

    /**
     * Begins a slot-group spec.
     *
     * @param groupId slot-group id, unique within the owning {@link MKCContainerPanel}
     * @param childX  panel-local X of the first slot (within the panel content area)
     * @param childY  panel-local Y of the first slot
     */
    public static SlotSpec at(String groupId, int childX, int childY) {
        return new SlotSpec(groupId, childX, childY);
    }

    /**
     * Where items live, as a per-player factory. Required. Use a
     * {@link StorageAttachment}-bound factory for content persistence:
     * {@code .storage(player -> POCKETS.bind(player))}.
     */
    public SlotSpec storage(Function<Player, Storage> storageFactory) {
        this.storageFactory = storageFactory;
        return this;
    }

    /**
     * Number of logical slots in this group (default {@code 1}). The
     * {@code storage} factory MUST produce a storage of exactly this size for
     * every player, or the appended slot block desyncs across sides.
     */
    public SlotSpec count(int count) {
        this.count = Math.max(1, count);
        return this;
    }

    /** Grid columns for a multi-slot group (default {@code 1}). Slots fill left-to-right, top-to-bottom on an 18px pitch. */
    public SlotSpec columns(int columns) {
        this.columns = Math.max(1, columns);
        return this;
    }

    /**
     * Client-side reveal predicate. The group is visible + interactive on the
     * client only while this returns true; the server keeps it syncing
     * regardless (see {@link MKCSlots.Builder#revealWhen}). MUST be client-safe.
     * If unset, the group is always visible.
     */
    public SlotSpec revealWhen(BooleanSupplier clientReveal) {
        this.revealWhen = clientReveal;
        return this;
    }

    /**
     * MK display name for the group's slots, applied through the naming layer like
     * {@link MKCSlots.Builder#label}: each slot is named {@code "{label} {n}"}
     * (1-based) for a multi-slot group, or just {@code "{label}"} for a single
     * slot. If unset, the slots fall back to the capitalized group id. Naming is a
     * client-side display concern (the build seam applies it client-guarded).
     */
    public SlotSpec label(String label) {
        this.label = label;
        return this;
    }

    // ── Inline behavior verbs (sugar; armed by Address in register()) ───
    // See the class doc: each verb records intent that
    // MKCContainerPanel.Builder.register() arms onto the engine by the slot's
    // Address for every local index in this group. Behavior still lives in the
    // engine — this is exactly Window.slot(addr).set(KEY, value), just declared
    // where the group is declared.

    /**
     * Arms a {@link SlotGate} (what every slot in this group accepts / releases /
     * caps) — sugar for {@code Window.slot(addr).set(MKCBehaviorKeys.GATING, gate)}
     * on each local index. Default (unset) is {@link SlotGate#OPEN} — pure vanilla.
     */
    public SlotSpec gate(SlotGate gate) {
        this.gate = gate;
        return this;
    }

    /**
     * Convenience over {@link #gate}: a place-only filter. Builds a {@link SlotGate}
     * that admits a stack iff {@code accept} passes (pickup stays open, stack cap
     * stays vanilla). For a richer policy (pickup rule, stack cap) declare a full
     * {@link SlotGate} via {@link #gate}.
     */
    public SlotSpec accepts(Predicate<ItemStack> accept) {
        this.gate = new SlotGate() {
            @Override public boolean mayPlace(ItemStack stack, GatingContext context) {
                return accept.test(stack);
            }
            @Override public boolean mayPickup(Player player, GatingContext context) {
                return true;
            }
        };
        return this;
    }

    /**
     * Enrolls every slot in this group in Curse-of-Binding enforcement (a bound
     * item can't be taken out while alive, survival only; creative bypasses, §0051)
     * — sugar for {@code set(MKCBehaviorKeys.BINDING, ...)}. Default off.
     */
    public SlotSpec binding(boolean enabled) {
        this.binding = enabled ? TriBool.TRUE : TriBool.FALSE;
        return this;
    }

    /** Equivalent to {@code binding(true)}. */
    public SlotSpec binding() {
        return binding(true);
    }

    /**
     * Opts every slot in this group into the XP-orb Mending repair pool (§0053) —
     * sugar for {@code set(MKCBehaviorKeys.MENDING, ...)}. Default off.
     */
    public SlotSpec mending(boolean enabled) {
        this.mending = enabled ? TriBool.TRUE : TriBool.FALSE;
        return this;
    }

    /** Equivalent to {@code mending(true)}. */
    public SlotSpec mending() {
        return mending(true);
    }

    /**
     * Sets how every slot in this group participates in shift-click (quick-move)
     * routing on a foreign menu — sugar for {@code set(MKCBehaviorKeys.QUICK_MOVE, p)}.
     * Default {@link QuickMoveParticipation#BOTH}.
     */
    public SlotSpec quickMove(QuickMoveParticipation participation) {
        this.quickMove = participation;
        return this;
    }

    // ── Accessors (read by MKCContainerPanel / ParitySlotRegistry) ──────

    String groupId()       { return groupId; }
    int childX()           { return childX; }
    int childY()           { return childY; }
    int count()            { return count; }
    int columns()          { return columns; }
    @Nullable BooleanSupplier revealWhen() { return revealWhen; }
    @Nullable String label() { return label; }

    @Nullable Function<Player, Storage> storageFactory() { return storageFactory; }

    // Inline behavior intent (null => leave the engine default for that key).
    @Nullable SlotGate gateValue()                 { return gate; }
    @Nullable TriBool bindingValue()               { return binding; }
    @Nullable TriBool mendingValue()               { return mending; }
    @Nullable QuickMoveParticipation quickMoveValue() { return quickMove; }
}
