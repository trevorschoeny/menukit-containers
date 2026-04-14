package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.screen.MenuKitScreenHandler;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Ordered registry of handler recognizers. Given an {@link AbstractContainerMenu},
 * produces a list of {@link VirtualSlotGroup}s describing the handler's slot
 * structure for consumer code that wants to work uniformly across MenuKit-native
 * and vanilla screens.
 *
 * <p>Recognition is stateless — no caching. The recognizer walks the slot list
 * (O(n) in slot count, typically under 100 slots) on each call. This eliminates
 * stale-cache and memory-leak concerns. If profiling later reveals recognizer
 * calls are hot, caching can be added with proper weak references.
 *
 * <p><b>Extension point:</b> consumers can register custom recognizers for their
 * own modded handlers via {@link #register(Recognizer)}. Custom recognizers run
 * before the default fallback — first match wins.
 *
 * <p><b>Entry point for consumers:</b>
 * {@link #findGroup(AbstractContainerMenu, Slot)} returns the group a slot
 * belongs to, regardless of whether the handler is MenuKit-native or vanilla.
 *
 * @see SlotGroupLike       The uniform interface consumers program against
 * @see VirtualSlotGroup    What recognizers produce
 */
public class HandlerRecognizerRegistry {

    // ── Recognizer Interface ───────────────────────────────────────────

    /**
     * A recognizer inspects a handler and returns its slot groups, or null
     * if it doesn't apply to this handler type.
     *
     * <p>When a recognizer returns a non-null, non-empty list, that list is
     * the authoritative grouping for the handler — no further recognizers run.
     * The list should cover ALL slots in the handler (including player inventory).
     */
    @FunctionalInterface
    public interface Recognizer {
        /**
         * Attempts to recognize the given handler.
         *
         * @param handler the handler to inspect
         * @return the recognized groups covering all slots, or null if this
         *         recognizer doesn't apply
         */
        @Nullable List<VirtualSlotGroup> recognize(AbstractContainerMenu handler);
    }

    // ── Registry ───────────────────────────────────────────────────────

    // Dedicated recognizers — checked before the default fallback.
    // Built-in ones are registered in the static initializer.
    // Consumer-registered ones are appended via register().
    private static final List<Recognizer> recognizers = new ArrayList<>();

    static {
        // Built-in recognizers for vanilla edge cases where
        // container-identity grouping produces wrong results.
        recognizers.add(HandlerRecognizerRegistry::recognizeFurnace);
        recognizers.add(HandlerRecognizerRegistry::recognizeBrewingStand);
    }

    /**
     * Registers a custom recognizer. Called at mod init by consumers who
     * need to describe their own modded handlers.
     *
     * <p>Custom recognizers are appended after built-in ones but before
     * the default fallback. First match wins.
     */
    public static void register(Recognizer recognizer) {
        recognizers.add(recognizer);
    }

    // ── Main Entry Points ──────────────────────────────────────────────

    /**
     * Recognizes the slot structure of a handler. Returns the list of
     * {@link VirtualSlotGroup}s describing the handler's groups.
     *
     * <p>For MenuKit-native handlers, returns an empty list — use the
     * handler's own {@link SlotGroup}s via
     * {@link MenuKitScreenHandler#getPanels()} instead.
     *
     * @param handler the handler to analyze
     * @return recognized groups (may be empty for MenuKit-native or empty handlers)
     */
    public static List<VirtualSlotGroup> recognize(AbstractContainerMenu handler) {
        // MenuKit-native handlers have real SlotGroups — don't recognize them.
        if (handler instanceof MenuKitScreenHandler) return List.of();
        if (handler.slots.isEmpty()) return List.of();

        // Try dedicated recognizers first (first match wins)
        for (Recognizer recognizer : recognizers) {
            List<VirtualSlotGroup> result = recognizer.recognize(handler);
            if (result != null && !result.isEmpty()) {
                return Collections.unmodifiableList(result);
            }
        }

        // Default: group all slots by container identity
        return Collections.unmodifiableList(
                groupSlotsByContainerIdentity(handler, 0, handler.slots.size()));
    }

    /**
     * Finds the {@link SlotGroupLike} that owns a given slot, regardless of
     * whether the handler is MenuKit-native or vanilla.
     *
     * <p>This is the primary consumer entry point. Consumers call this and
     * program against the returned {@link SlotGroupLike} — they never need
     * to know whether the screen is native or observed.
     *
     * @param handler the handler the slot belongs to
     * @param slot    the slot to look up
     * @return the owning group, or empty if not found
     */
    public static Optional<SlotGroupLike> findGroup(AbstractContainerMenu handler,
                                                     Slot slot) {
        // MenuKit-native: slot carries its group directly
        if (slot instanceof MenuKitSlot mkSlot) {
            return Optional.of(mkSlot.getGroup());
        }

        // Observed screen: run the recognizer and search
        List<VirtualSlotGroup> groups = recognize(handler);
        for (VirtualSlotGroup group : groups) {
            if (group.containsSlot(slot)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    // ── Shared Utility: Identity Grouping ──────────────────────────────
    // Both the default recognizer and dedicated recognizers use this to
    // group slots by Container reference identity. Extracted as a public
    // utility so custom recognizers can reuse it too.

    /**
     * Groups a range of slots by {@link Slot#container} reference identity.
     * Contiguous slots sharing the same Container become one
     * {@link VirtualSlotGroup}. Player inventory is detected via
     * {@code instanceof Inventory} and named {@code "player_inventory"}.
     *
     * <p>This is the default grouping strategy and also a building block
     * for dedicated recognizers that need to identity-group the non-special
     * portion of their handlers (typically the player inventory at the end).
     *
     * @param handler   the handler to read slots from
     * @param fromIndex start of the range (inclusive)
     * @param toIndex   end of the range (exclusive)
     * @return groups covering the specified range
     */
    public static List<VirtualSlotGroup> groupSlotsByContainerIdentity(
            AbstractContainerMenu handler, int fromIndex, int toIndex) {

        List<VirtualSlotGroup> groups = new ArrayList<>();
        if (fromIndex >= toIndex) return groups;

        // Walk the slot list, starting a new group each time the
        // Container reference changes. Skip MenuKit-injected slots —
        // both old architecture (MKSlot in widget package) and new
        // architecture (MenuKitSlot in core package). These are always
        // appended after vanilla slots and aren't part of the vanilla
        // handler's structure.
        Object currentContainer = null;  // reference-identity comparison
        List<Slot> currentSlots = new ArrayList<>();
        int containerGroupIndex = 0;

        for (int i = fromIndex; i < toIndex; i++) {
            Slot slot = handler.slots.get(i);

            // Skip MenuKit-managed slots — they belong to a MenuKitScreenHandler,
            // not to an observed vanilla screen, and shouldn't be grouped here.
            if (slot instanceof MenuKitSlot) continue;
            if (slot.container != currentContainer) {
                if (!currentSlots.isEmpty()) {
                    groups.add(buildIdentityGroup(
                            currentSlots, currentContainer, containerGroupIndex));
                    containerGroupIndex++;
                    currentSlots = new ArrayList<>();
                }
                currentContainer = slot.container;
            }
            currentSlots.add(slot);
        }

        // Final group
        if (!currentSlots.isEmpty()) {
            groups.add(buildIdentityGroup(
                    currentSlots, currentContainer, containerGroupIndex));
        }

        return groups;
    }

    /**
     * Builds a VirtualSlotGroup from a contiguous run of same-container slots.
     * Naming convention:
     * <ul>
     *   <li>Player inventory: {@code "player_inventory"} (detected via
     *       {@code container instanceof Inventory})</li>
     *   <li>Single non-player container: {@code "container"}</li>
     *   <li>Multiple non-player containers: {@code "container_0"},
     *       {@code "container_1"}, etc.</li>
     * </ul>
     */
    private static VirtualSlotGroup buildIdentityGroup(
            List<Slot> slots, Object container, int containerIndex) {

        // Detect player inventory by container type
        if (container instanceof Inventory) {
            return new VirtualSlotGroup("player_inventory", slots,
                    InteractionPolicy.free(), QuickMoveParticipation.BOTH, 0);
        }

        // Non-player container
        String id = containerIndex == 0 ? "container" : "container_" + containerIndex;
        return new VirtualSlotGroup(id, slots,
                InteractionPolicy.free(), QuickMoveParticipation.BOTH, 100);
    }

    // ── Built-in Recognizer: Furnace ───────────────────────────────────
    // Covers FurnaceMenu, BlastFurnaceMenu, SmokerMenu (all extend
    // AbstractFurnaceMenu). Without this, all 3 slots share one Container
    // (the block entity) and the default grouper lumps them together.

    private static @Nullable List<VirtualSlotGroup> recognizeFurnace(
            AbstractContainerMenu handler) {
        if (!(handler instanceof AbstractFurnaceMenu)) return null;
        if (handler.slots.size() < 3) return null;

        List<VirtualSlotGroup> groups = new ArrayList<>();

        // Slot 0: input — accepts items that can be smelted
        groups.add(new VirtualSlotGroup("input",
                List.of(handler.slots.get(0)),
                InteractionPolicy.free(),
                QuickMoveParticipation.IMPORTS, 100));

        // Slot 1: fuel — accepts fuel items
        groups.add(new VirtualSlotGroup("fuel",
                List.of(handler.slots.get(1)),
                InteractionPolicy.free(),
                QuickMoveParticipation.IMPORTS, 90));

        // Slot 2: output — items can only be taken out
        groups.add(new VirtualSlotGroup("output",
                List.of(handler.slots.get(2)),
                InteractionPolicy.output((stack, player) -> {}),
                QuickMoveParticipation.EXPORTS, 100));

        // Remaining slots: player inventory (identity-grouped)
        groups.addAll(groupSlotsByContainerIdentity(handler, 3, handler.slots.size()));

        return groups;
    }

    // ── Built-in Recognizer: Brewing Stand ─────────────────────────────
    // BrewingStandMenu has 5 container slots sharing one Container.
    // Slots 0-2: potion bottles, Slot 3: ingredient, Slot 4: blaze powder fuel.

    private static @Nullable List<VirtualSlotGroup> recognizeBrewingStand(
            AbstractContainerMenu handler) {
        if (!(handler instanceof BrewingStandMenu)) return null;
        if (handler.slots.size() < 5) return null;

        List<VirtualSlotGroup> groups = new ArrayList<>();

        // Slots 0-2: potion bottles
        groups.add(new VirtualSlotGroup("potions",
                List.of(handler.slots.get(0),
                        handler.slots.get(1),
                        handler.slots.get(2)),
                InteractionPolicy.free(),
                QuickMoveParticipation.BOTH, 100));

        // Slot 3: ingredient (e.g., nether wart, glowstone dust)
        groups.add(new VirtualSlotGroup("ingredient",
                List.of(handler.slots.get(3)),
                InteractionPolicy.free(),
                QuickMoveParticipation.IMPORTS, 100));

        // Slot 4: fuel (blaze powder)
        groups.add(new VirtualSlotGroup("fuel",
                List.of(handler.slots.get(4)),
                InteractionPolicy.free(),
                QuickMoveParticipation.IMPORTS, 90));

        // Remaining slots: player inventory (identity-grouped)
        groups.addAll(groupSlotsByContainerIdentity(handler, 5, handler.slots.size()));

        return groups;
    }

}
