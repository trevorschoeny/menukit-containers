package com.trevorschoeny.menukit.core;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Pure behavioral description of what operations are allowed on a
 * {@link SlotGroup}'s slots. A record of predicates and callbacks.
 *
 * <p>Storage (where items live) and InteractionPolicy (what you can do
 * with them) are orthogonal axes. Changing one doesn't affect the other.
 *
 * <p>Named constructors cover the common cases. Compose with
 * {@link #withMaxStackSize(ToIntFunction)} or similar for variations.
 */
public record InteractionPolicy(
        Predicate<ItemStack> canAccept,
        Predicate<ItemStack> canRemove,
        Consumer<ItemStack> onInsert,
        BiConsumer<ItemStack, Player> onTake,
        ToIntFunction<ItemStack> maxStackSize
) {

    // ── Named Constructors ──────────────────────────────────────────────

    /** Accepts all items, free removal, no callbacks, vanilla max stack. */
    public static InteractionPolicy free() {
        return new InteractionPolicy(
                stack -> true,
                stack -> true,
                stack -> {},
                (stack, player) -> {},
                ItemStack::getMaxStackSize
        );
    }

    /** Rejects all insertion and removal. Display-only, but still holds items. */
    public static InteractionPolicy locked() {
        return new InteractionPolicy(
                stack -> false,
                stack -> false,
                stack -> {},
                (stack, player) -> {},
                ItemStack::getMaxStackSize
        );
    }

    /** Accepts items matching the filter, free removal. */
    public static InteractionPolicy input(Predicate<ItemStack> filter) {
        return new InteractionPolicy(
                filter,
                stack -> true,
                stack -> {},
                (stack, player) -> {},
                ItemStack::getMaxStackSize
        );
    }

    /** No insertion allowed. Fires callback on take. Like crafting output. */
    public static InteractionPolicy output(BiConsumer<ItemStack, Player> onTake) {
        return new InteractionPolicy(
                stack -> false,
                stack -> true,
                stack -> {},
                onTake,
                ItemStack::getMaxStackSize
        );
    }

    /** No insertion, no removal. Read-only view of the backing storage. */
    public static InteractionPolicy display() {
        return new InteractionPolicy(
                stack -> false,
                stack -> false,
                stack -> {},
                (stack, player) -> {},
                ItemStack::getMaxStackSize
        );
    }

    // ── Composable Modifiers ────────────────────────────────────────────

    /** Returns a copy with a different max stack size function. */
    public InteractionPolicy withMaxStackSize(ToIntFunction<ItemStack> maxStackSize) {
        return new InteractionPolicy(canAccept, canRemove, onInsert, onTake, maxStackSize);
    }

    /** Returns a copy with an additional accept filter (AND-composed). */
    public InteractionPolicy withFilter(Predicate<ItemStack> additionalFilter) {
        Predicate<ItemStack> combined = canAccept.and(additionalFilter);
        return new InteractionPolicy(combined, canRemove, onInsert, onTake, maxStackSize);
    }
}
