package com.trevorschoeny.menukit.core;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The side-neutral registry of container-parity slot recipes — the single
 * source of truth both menu-build seams read so a registered slot appears on
 * <em>every</em> menu the player opens, identically on both sides.
 *
 * <h3>The two seams, one registry</h3>
 *
 * A registered {@link SlotSpec} must materialise as a real {@link MKCSlot} on
 * three kinds of menu, and this registry is what each path applies:
 * <ul>
 *   <li><b>The player's own {@code InventoryMenu}</b> — appended by the
 *       library-owned {@code MKCInventoryMenuMixin} at the menu constructor's
 *       TAIL, on both sides. This is the §0019 line container parity
 *       deliberately crosses: the inventory menu is built in the player
 *       constructor (never via {@code openMenu}), so the projection seam can't
 *       reach it; rather than make every consumer write the same
 *       {@code InventoryMenu.<init>} mixin, the library owns it once and drives
 *       it from this registry. (Foreign menus keep no consumer-ownable per-menu
 *       seam, so they were already library-owned via {@link MKCSlotProjection}.)</li>
 *   <li><b>Every foreign menu</b> (chest/furnace/donkey/modded) — appended by a
 *       single {@link MKCSlotProjection} source (registered once by
 *       {@link MKCContainerPanel}) whose factory calls {@link #applyTo}; its
 *       {@code appliesTo} excludes {@code InventoryMenu} so the inventory menu
 *       isn't double-served.</li>
 *   <li><b>The creative item-picker</b> — gets the slots for free: the existing
 *       {@code MKCCreativeSlotItemPickerMixin} wraps whatever {@link MKCSlot}s
 *       the {@code InventoryMenu} mixin already put on {@code player.inventoryMenu}.</li>
 * </ul>
 *
 * <h3>Sync safety</h3>
 *
 * Both real seams build through {@link MKCSlots} with the same recipe in the
 * same registry order, and the {@code storage} factory yields the same size per
 * player on both sides — so the appended slot blocks are byte-identical, the
 * invariant {@link MKCSlotProjection} spells out. {@link #applyTo} is called at
 * most once per menu instance: the inventory mixin fires once per construction;
 * the projection source is guarded per-menu by {@code MKCSlotProjection}.
 */
@ApiStatus.Internal
public final class ParitySlotRegistry {

    private ParitySlotRegistry() {}

    /** One registered recipe: the derived slot panel id plus its {@link SlotSpec}. */
    private record Entry(String slotPanelId, SlotSpec spec) {}

    // Strong refs — consumers register once at init. CopyOnWriteArrayList so the
    // build seams (which iterate during menu construction) never see a partial
    // registration and the iteration order is stable across both sides.
    private static final List<Entry> RECIPES = new CopyOnWriteArrayList<>();

    /**
     * Registers one parity slot recipe. The {@code slotPanelId} is the id the
     * built {@link MKCSlot}s carry (and the {@link SlotElement}s resolve against)
     * — {@link MKCContainerPanel} derives it from the container-panel id + group
     * so it's stable and collision-free.
     */
    public static void register(String slotPanelId, SlotSpec spec) {
        RECIPES.add(new Entry(slotPanelId, spec));
    }

    /** Whether any parity recipe is registered (lets the build seams short-circuit). */
    public static boolean isEmpty() {
        return RECIPES.isEmpty();
    }

    /**
     * Builds every registered recipe's slots onto {@code menu} for {@code player},
     * via the proven {@link MKCSlots} path (so render / click→Storage / creative /
     * sync all ride the same vanilla-slot machinery the inventory slot already
     * uses). Invoked by the inventory-menu mixin (every {@code InventoryMenu}) and
     * by the projection source (every foreign menu); each call site guarantees a
     * given menu instance is applied at most once.
     */
    public static void applyTo(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null || RECIPES.isEmpty()) return;
        for (Entry e : RECIPES) {
            SlotSpec spec = e.spec();
            var factory = spec.storageFactory();
            if (factory == null) continue;   // mis-declared recipe; skip defensively
            Storage storage = factory.apply(player);
            if (storage == null) continue;

            MKCSlots.Builder b = MKCSlots.onto(menu, player)
                    .panel(e.slotPanelId())
                    .group(spec.groupId())
                    .storage(storage)
                    // Seed layout from the spec's panel-local origin; the SlotElement
                    // repositions per frame to its panel spot, so this is only the
                    // initial off-panel seed (it never shows).
                    .layout(spec.childX(), spec.childY(), spec.columns());
            // Behavior-free creation (Phase 5): gating/quick-move/binding/mending are
            // set later through the window by the slot's address, not at creation.
            if (spec.label() != null) b.label(spec.label());     // MK naming (client-guarded in register)
            if (spec.revealWhen() != null) b.revealWhen(spec.revealWhen());
            b.register();
        }
    }
}
