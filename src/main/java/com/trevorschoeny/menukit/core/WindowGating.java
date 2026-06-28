package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.VanillaAddressing;
import com.trevorschoeny.menukit.window.WindowEngine;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

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

    // ── slot-level seams (no menu): the direct-click path gates here ─────────────
    // Slot.mayPlace/mayPickup/getMaxStackSize have no menu in hand, only the slot.
    // A vanilla slot's gate is found from its CONTAINER address (the same §0050
    // address the set-time path mints via VanillaAddressing.addressOf(container,
    // index)), so a gate set on the player inventory by index is found here. Used by
    // MKCVanillaSlotGatingMixin; created slots gate in MKCSlot itself (skipped there).

    /** Whether {@code stack} may be placed into {@code slot} (gating), by container address. */
    public static boolean mayPlaceAt(Slot slot, ItemStack stack) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return true;
        SlotGate gate = slotGate(slot);
        return gate == SlotGate.OPEN || gate.mayPlace(stack, GatingContext.current());
    }

    /** Whether {@code player} may take from {@code slot} (gating), by container address. */
    public static boolean mayPickupAt(Slot slot, Player player) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return true;
        SlotGate gate = slotGate(slot);
        return gate == SlotGate.OPEN || gate.mayPickup(player, GatingContext.current());
    }

    /** The per-item stack cap for {@code slot} (gating), clamped to {@code vanillaMax}. */
    public static int maxStackAt(Slot slot, ItemStack stack, int vanillaMax) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return vanillaMax;
        SlotGate gate = slotGate(slot);
        return gate == SlotGate.OPEN ? vanillaMax
                : Math.min(gate.maxStackSize(stack, vanillaMax), vanillaMax);
    }

    private static SlotGate slotGate(Slot slot) {
        return VanillaAddressing.addressOf(slot.container, slot.getContainerSlot())
                .map(addr -> WindowEngine.resolve(addr, MKCBehaviorKeys.GATING))
                .orElse(SlotGate.OPEN); // unidentifiable container => vanilla
    }

    /**
     * Whether Curse of Binding (BINDING set on this slot) blocks taking the item out —
     * a bound item ({@code PREVENT_ARMOR_CHANGE}) can't be removed while the player is
     * alive, survival only (creative bypasses). The vanilla-slot twin of
     * {@code MKCSlot.mayPickup}'s binding check, addressed by container.
     */
    public static boolean bindingDeniesPickup(Slot slot, Player player) {
        if (BehaviorBindingTable.INSTANCE.isEmpty()) return false;
        Address a = VanillaAddressing.addressOf(slot.container, slot.getContainerSlot()).orElse(null);
        if (a == null || !WindowEngine.resolve(a, MKCBehaviorKeys.BINDING).asBoolean()) return false;
        if (player == null || player.hasInfiniteMaterials()) return false; // creative bypass (§0051)
        return EnchantmentHelper.has(slot.getItem(), EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
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
