package com.trevorschoeny.menukit.core;

import net.minecraft.world.entity.player.Player;

import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

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
 * {@link MKCSlots.Builder} — and, post Phase 5, just as behavior-free: storage,
 * layout, and the client reveal predicate, expressed once as reusable data rather
 * than imperatively against a single menu. Gating, quick-move, binding, and
 * mending are NOT set here; they're set later through the window by the slot's
 * address (the same path a vanilla slot uses). {@link MKCContainerPanel} turns
 * each {@code SlotSpec} into both the real slots (every menu, both sides) and the
 * {@link SlotElement}s that present them (client).
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

    // ── Accessors (read by MKCContainerPanel / ParitySlotRegistry) ──────

    String groupId()       { return groupId; }
    int childX()           { return childX; }
    int childY()           { return childY; }
    int count()            { return count; }
    int columns()          { return columns; }
    @Nullable BooleanSupplier revealWhen() { return revealWhen; }

    @Nullable Function<Player, Storage> storageFactory() { return storageFactory; }
}
