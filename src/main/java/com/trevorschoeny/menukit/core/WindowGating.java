package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.VanillaAddressing;
import com.trevorschoeny.menukit.window.WindowEngine;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The reusable gating decision every server seam calls — resolve the
 * {@code GATING} {@link SlotGate} for a slot by its {@link Address} and apply it.
 * The six vanilla interaction seams (shift-click, pick-all, hopper×3, dispenser)
 * are thin {@code @WrapOperation}/{@code @Inject} wrappers over these methods,
 * generalizing Inventory Max's hand-rolled container locks (a lock is just a gate
 * that denies).
 *
 * <h2>The presence fast-path</h2>
 *
 * If the authoritative store holds NO bindings at all, every slot is un-gated and
 * these return immediately — so when nobody has declared any server behavior,
 * vanilla interaction incurs zero address-minting or resolution ("the slots
 * nobody touches stay exactly vanilla"). When some behavior exists, the per-slot
 * path resolves the gate (defaulting to {@link SlotGate#OPEN} = vanilla for an
 * un-gated slot).
 */
public final class WindowGating {

    private WindowGating() {}

    /** Whether {@code stack} may be placed into {@code slot} (gating), in the acting context. */
    public static boolean mayPlace(AbstractContainerMenu menu, Slot slot, ItemStack stack) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return true;
        SlotGate gate = gateFor(menu, slot);
        return gate == SlotGate.OPEN || gate.mayPlace(stack, GatingContext.current());
    }

    /** Whether {@code player} may take from {@code slot} (gating), in the acting context. */
    public static boolean mayPickup(AbstractContainerMenu menu, Slot slot, Player player) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return true;
        SlotGate gate = gateFor(menu, slot);
        return gate == SlotGate.OPEN || gate.mayPickup(player, GatingContext.current());
    }

    // ── automation seams (hopper/dispenser): (container, index), no menu/player ──

    /** Whether automation may insert {@code stack} into a placed-container slot. */
    public static boolean mayPlaceInto(Container container, int slotIndex, ItemStack stack) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return true;
        SlotGate gate = automationGate(container, slotIndex);
        return gate == SlotGate.OPEN || gate.mayPlace(stack, GatingContext.current());
    }

    /** Whether automation may extract from a placed-container slot (no player). */
    public static boolean mayExtractFrom(Container container, int slotIndex) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return true;
        SlotGate gate = automationGate(container, slotIndex);
        // null player: automation. A gate that bypasses for non-capable players
        // (a lock) still enforces here, because automation has no player to exempt.
        return gate == SlotGate.OPEN || gate.mayPickup(null, GatingContext.current());
    }

    private static SlotGate gateFor(AbstractContainerMenu menu, Slot slot) {
        // Kind-aware: a created slot resolves to its menu-independent created
        // address, a vanilla slot to its container/menu address. Both via the one
        // SlotAddresses entry, so a gate set on either is found here.
        Address address = SlotAddresses.of(menu, slot);
        return WindowEngine.resolve(address, MKCBehaviorKeys.GATING);
    }

    private static SlotGate automationGate(Container container, int slotIndex) {
        return VanillaAddressing.addressOf(container, slotIndex)
                .map(addr -> WindowEngine.resolve(addr, MKCBehaviorKeys.GATING))
                .orElse(SlotGate.OPEN); // unidentifiable container => vanilla
    }
}
