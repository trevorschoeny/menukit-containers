package com.trevorschoeny.menukit.inject;

import com.trevorschoeny.menukit.core.SlotGroupCategory;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.NautilusInventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.StonecutterMenu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Library-shipped {@link SlotGroupResolver} registrations for every vanilla
 * 1.21.11 {@link AbstractContainerMenu} whose slots map to named categories
 * in the M8 v1 coverage catalog. See
 * {@code Design Docs/Phase 12.5/M8_FOUR_CONTEXT_MODEL.md} §6 for the full
 * category list and §11.2 for the resolver count.
 *
 * <p>Slot-index ranges verified against vanilla source at
 * {@code net.minecraft.world.inventory.*Menu} in the Loom-cached decompile.
 * Most menus follow a "N specific slots + 27 player inventory + 9 player
 * hotbar" pattern; variable-size menus (ChestMenu,
 * HorseInventoryMenu) derive their specific-slot count from
 * {@code menu.slots.size() - 36}.
 *
 * <p>Each resolver returns an immutable map keyed by
 * {@link SlotGroupCategory}, whose values are read-only sub-lists of
 * {@code menu.slots}. Empty-list values are omitted from the returned map
 * per {@link SlotGroupResolver}'s contract.
 */
public final class VanillaSlotGroupResolvers {

    private VanillaSlotGroupResolvers() {}

    /**
     * Called once from {@code MenuKitClient.onInitializeClient}. Registers
     * a resolver for each vanilla menu class listed in M8 §6.11.
     */
    public static void registerAll() {
        registerPlayerAndStorage();
        registerCraftingFamily();
        registerFurnaceFamily();
        registerUtilityBlocks();
        registerBrewingTradingBeacon();
        registerMounts();
        registerCreativeItemPicker();
    }

    // ── Shared helpers ──────────────────────────────────────────────────

    /**
     * Adds the standard player-inventory-tail categories (27 inventory + 9
     * hotbar) starting at {@code startIndex}. Used by every menu that calls
     * vanilla's {@code addStandardInventorySlots} after its specific slots.
     */
    private static void addPlayerInvTail(Map<SlotGroupCategory, List<Slot>> out,
                                          List<Slot> slots, int startIndex) {
        out.put(SlotGroupCategory.PLAYER_INVENTORY,
                slots.subList(startIndex, startIndex + 27));
        out.put(SlotGroupCategory.PLAYER_HOTBAR,
                slots.subList(startIndex + 27, startIndex + 36));
    }

    // ── Player inventory (survival + creative INVENTORY tab) ────────────

    private static void registerPlayerAndStorage() {
        // InventoryMenu: 1 result + 4 crafting (2×2) + 4 armor + 27 inv + 9 hotbar + 1 offhand
        SlotGroupCategories.register(InventoryMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.CRAFTING_OUTPUT, s.subList(0, 1));
            out.put(SlotGroupCategory.CRAFTING_INPUT, s.subList(1, 5));
            out.put(SlotGroupCategory.PLAYER_ARMOR, s.subList(5, 9));
            out.put(SlotGroupCategory.PLAYER_INVENTORY, s.subList(9, 36));
            out.put(SlotGroupCategory.PLAYER_HOTBAR, s.subList(36, 45));
            out.put(SlotGroupCategory.PLAYER_OFFHAND, s.subList(45, 46));
            return Map.copyOf(out);
        });

        // ChestMenu: N storage + 27 inv + 9 hotbar (N = 9, 18, 27, 36, 45, 54)
        // Includes chests, barrels (Barrel uses ChestMenu with size 27).
        SlotGroupCategories.register(ChestMenu.class, menu -> {
            List<Slot> s = menu.slots;
            int storage = s.size() - 36;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.CHEST_STORAGE, s.subList(0, storage));
            addPlayerInvTail(out, s, storage);
            return Map.copyOf(out);
        });

        // ShulkerBoxMenu: 27 shulker + 27 inv + 9 hotbar = 63 slots
        SlotGroupCategories.register(ShulkerBoxMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.SHULKER_STORAGE, s.subList(0, 27));
            addPlayerInvTail(out, s, 27);
            return Map.copyOf(out);
        });

        // DispenserMenu: 9 dispenser (3×3) + 27 inv + 9 hotbar = 45 slots
        // Used by dispenser + dropper (both MenuType.GENERIC_3x3).
        SlotGroupCategories.register(DispenserMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.DISPENSER_STORAGE, s.subList(0, 9));
            addPlayerInvTail(out, s, 9);
            return Map.copyOf(out);
        });

        // HopperMenu: 5 hopper + 27 inv + 9 hotbar = 41 slots
        SlotGroupCategories.register(HopperMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.HOPPER_STORAGE, s.subList(0, 5));
            addPlayerInvTail(out, s, 5);
            return Map.copyOf(out);
        });
    }

    // ── Crafting family ────────────────────────────────────────────────

    private static void registerCraftingFamily() {
        // CraftingMenu: 1 result + 9 crafting (3×3) + 27 inv + 9 hotbar = 46 slots
        SlotGroupCategories.register(CraftingMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.CRAFTING_OUTPUT, s.subList(0, 1));
            out.put(SlotGroupCategory.CRAFTING_INPUT, s.subList(1, 10));
            addPlayerInvTail(out, s, 10);
            return Map.copyOf(out);
        });

        // CrafterMenu: 9 crafter grid + 27 inv + 9 hotbar + 1 non-interactive result = 46
        // Note: the result slot is at slot 45 (after player inventory), not at slot 0.
        SlotGroupCategories.register(CrafterMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.CRAFTER_GRID, s.subList(0, 9));
            addPlayerInvTail(out, s, 9);
            out.put(SlotGroupCategory.CRAFTER_RESULT, s.subList(45, 46));
            return Map.copyOf(out);
        });
    }

    // ── Furnace family ──────────────────────────────────────────────────

    private static void registerFurnaceFamily() {
        // AbstractFurnaceMenu: slot 0 input, 1 fuel, 2 output. Then 27 inv + 9 hotbar.
        // Same layout for FurnaceMenu, SmokerMenu, BlastFurnaceMenu — three
        // resolvers register identically.
        SlotGroupResolver furnaceResolver = menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.FURNACE_INPUT, s.subList(0, 1));
            out.put(SlotGroupCategory.FURNACE_FUEL, s.subList(1, 2));
            out.put(SlotGroupCategory.FURNACE_OUTPUT, s.subList(2, 3));
            addPlayerInvTail(out, s, 3);
            return Map.copyOf(out);
        };
        SlotGroupCategories.register(FurnaceMenu.class, furnaceResolver);
        SlotGroupCategories.register(SmokerMenu.class, furnaceResolver);
        SlotGroupCategories.register(BlastFurnaceMenu.class, furnaceResolver);
    }

    // ── Utility blocks with slots ───────────────────────────────────────

    private static void registerUtilityBlocks() {
        // EnchantmentMenu: slot 0 input, slot 1 lapis. Then 27 inv + 9 hotbar.
        SlotGroupCategories.register(EnchantmentMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.ENCHANTING_INPUT, s.subList(0, 1));
            out.put(SlotGroupCategory.ENCHANTING_LAPIS, s.subList(1, 2));
            addPlayerInvTail(out, s, 2);
            return Map.copyOf(out);
        });

        // AnvilMenu (via ItemCombinerMenu): slots 0-1 inputs, slot 2 output. Then inv.
        SlotGroupCategories.register(AnvilMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.ANVIL_INPUT, s.subList(0, 2));
            out.put(SlotGroupCategory.ANVIL_OUTPUT, s.subList(2, 3));
            addPlayerInvTail(out, s, 3);
            return Map.copyOf(out);
        });

        // GrindstoneMenu: slots 0-1 inputs, slot 2 output. Then inv.
        SlotGroupCategories.register(GrindstoneMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.GRINDSTONE_INPUT, s.subList(0, 2));
            out.put(SlotGroupCategory.GRINDSTONE_OUTPUT, s.subList(2, 3));
            addPlayerInvTail(out, s, 3);
            return Map.copyOf(out);
        });

        // SmithingMenu: slot 0 template, 1 base, 2 addition, 3 output. Then inv.
        SlotGroupCategories.register(SmithingMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.SMITHING_TEMPLATE, s.subList(0, 1));
            out.put(SlotGroupCategory.SMITHING_BASE, s.subList(1, 2));
            out.put(SlotGroupCategory.SMITHING_ADDITION, s.subList(2, 3));
            out.put(SlotGroupCategory.SMITHING_OUTPUT, s.subList(3, 4));
            addPlayerInvTail(out, s, 4);
            return Map.copyOf(out);
        });

        // LoomMenu: slot 0 banner, 1 dye, 2 pattern, 3 output. Then inv (INV_SLOT_START=4).
        SlotGroupCategories.register(LoomMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.LOOM_BANNER, s.subList(0, 1));
            out.put(SlotGroupCategory.LOOM_DYE, s.subList(1, 2));
            out.put(SlotGroupCategory.LOOM_PATTERN, s.subList(2, 3));
            out.put(SlotGroupCategory.LOOM_OUTPUT, s.subList(3, 4));
            addPlayerInvTail(out, s, 4);
            return Map.copyOf(out);
        });

        // StonecutterMenu: slot 0 input, slot 1 output. Then inv (INV_SLOT_START=2).
        SlotGroupCategories.register(StonecutterMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.STONECUTTER_INPUT, s.subList(0, 1));
            out.put(SlotGroupCategory.STONECUTTER_OUTPUT, s.subList(1, 2));
            addPlayerInvTail(out, s, 2);
            return Map.copyOf(out);
        });

        // CartographyTableMenu: slot 0 map, 1 additional, 2 result. Then inv.
        SlotGroupCategories.register(CartographyTableMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.CARTOGRAPHY_MAP, s.subList(0, 1));
            out.put(SlotGroupCategory.CARTOGRAPHY_ADDITIONAL, s.subList(1, 2));
            out.put(SlotGroupCategory.CARTOGRAPHY_OUTPUT, s.subList(2, 3));
            addPlayerInvTail(out, s, 3);
            return Map.copyOf(out);
        });
    }

    // ── Brewing / Trading / Beacon ──────────────────────────────────────

    private static void registerBrewingTradingBeacon() {
        // BrewingStandMenu: slots 0-2 potions, 3 ingredient, 4 fuel. Then inv.
        SlotGroupCategories.register(BrewingStandMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.BREWING_POTIONS, s.subList(0, 3));
            out.put(SlotGroupCategory.BREWING_INGREDIENT, s.subList(3, 4));
            out.put(SlotGroupCategory.BREWING_FUEL, s.subList(4, 5));
            addPlayerInvTail(out, s, 5);
            return Map.copyOf(out);
        });

        // MerchantMenu: slots 0-1 payment, slot 2 result. Then inv.
        SlotGroupCategories.register(MerchantMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.MERCHANT_PAYMENT, s.subList(0, 2));
            out.put(SlotGroupCategory.MERCHANT_RESULT, s.subList(2, 3));
            addPlayerInvTail(out, s, 3);
            return Map.copyOf(out);
        });

        // BeaconMenu: slot 0 payment. Then inv.
        SlotGroupCategories.register(BeaconMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.BEACON_PAYMENT, s.subList(0, 1));
            addPlayerInvTail(out, s, 1);
            return Map.copyOf(out);
        });
    }

    // ── Mounts (horse + nautilus via AbstractMountInventoryMenu) ────────

    private static void registerMounts() {
        // HorseInventoryMenu: slot 0 saddle, 1 body armor, then optional storage
        // (3 * j slots, j = 0 for horse/mule, 3 for donkey/mule-with-chest, 5 for
        // llama). Then 27 inv + 9 hotbar. Total = 2 + 3j + 36.
        SlotGroupCategories.register(HorseInventoryMenu.class, menu -> {
            List<Slot> s = menu.slots;
            int storage = s.size() - 38; // 38 = 2 saddle/armor + 36 player
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.MOUNT_SADDLE, s.subList(0, 1));
            out.put(SlotGroupCategory.MOUNT_BODY_ARMOR, s.subList(1, 2));
            if (storage > 0) {
                out.put(SlotGroupCategory.MOUNT_STORAGE, s.subList(2, 2 + storage));
            }
            addPlayerInvTail(out, s, 2 + storage);
            return Map.copyOf(out);
        });

        // NautilusInventoryMenu: slot 0 saddle, 1 body armor. Then 27 inv + 9 hotbar.
        // No storage grid for nautilus.
        SlotGroupCategories.register(NautilusInventoryMenu.class, menu -> {
            List<Slot> s = menu.slots;
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            out.put(SlotGroupCategory.MOUNT_SADDLE, s.subList(0, 1));
            out.put(SlotGroupCategory.MOUNT_BODY_ARMOR, s.subList(1, 2));
            addPlayerInvTail(out, s, 2);
            return Map.copyOf(out);
        });
    }

    // ── Creative ItemPickerMenu — dynamic (per-tab) resolution ──────────

    /**
     * Creative inventory uses its own {@code ItemPickerMenu} which rebuilds
     * {@code menu.slots} on every tab change. ScreenPanelRegistry re-resolves
     * slot groups per frame (M8 §5.4), so the resolver discriminates by the
     * current slot count:
     *
     * <ul>
     *   <li><b>Non-INVENTORY tab — always 54 slots.</b> 45 creative items at
     *       indices 0-44, player hotbar at 45-53. The HOTBAR, SEARCH, and
     *       category tabs all re-use this layout (they change which items
     *       are displayed in the creative-item slots, not the slot count).
     *       Only {@code PLAYER_HOTBAR} resolves — the hotbar is always
     *       visually present in creative.</li>
     *   <li><b>INVENTORY tab — size ≠ 54.</b> Vanilla's {@code selectTab}
     *       clears slots and re-adds wrappers around every slot in
     *       {@code player.inventoryMenu}, then appends a
     *       {@code destroyItemSlot} trash bin. Without consumer mods, this
     *       is 46 wrappers + 1 destroy = 47 slots. With a mod grafting N
     *       extra slots onto {@code InventoryMenu} (e.g., inventory-plus's
     *       equipment slots), it's (46 + N) wrappers + 1 destroy.</li>
     * </ul>
     *
     * <p><b>Why not match on a specific INVENTORY-tab count?</b> Early
     * drafts tried size == 46 (vanilla InventoryMenu slot count, forgetting
     * the destroy slot) and size == 47 (vanilla including destroy). Both
     * failed in dev because inventory-plus grafts 2 slots into
     * {@code InventoryMenu}, producing size == 49. Any mod doing similar
     * grafting changes the count. The slot layout at indices 0-45 — the
     * player-inventory categories — is invariant under such grafting
     * because vanilla's rebuild loop preserves InventoryMenu's slot order.
     * Using {@code size != 54} captures INVENTORY-tab state correctly across
     * mod-extended inventories, at the cost of ambiguity if a future modded
     * creative tab ever produces a non-54 slot count outside the INVENTORY
     * tab. No such case observed in vanilla or common mods; re-narrow the
     * check if one surfaces.
     */
    private static void registerCreativeItemPicker() {
        SlotGroupCategories.register(CreativeModeInventoryScreen.ItemPickerMenu.class, menu -> {
            List<Slot> s = menu.slots;
            int size = s.size();
            Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
            if (size == 54) {
                // Non-INVENTORY tab: 45 creative-item slots + 9 hotbar.
                // PLAYER_INVENTORY absent (main inventory isn't visible here);
                // PLAYER_HOTBAR resolves because the hotbar IS always visible.
                out.put(SlotGroupCategory.PLAYER_HOTBAR, s.subList(45, 54));
            } else if (size >= 46) {
                // INVENTORY tab — size varies with mod-grafted InventoryMenu
                // slots. Indices 0-45 are stable per vanilla's rebuild loop
                // order; claim only those for the player-inventory layout.
                // Index 46 and beyond may include mod-grafted slots and the
                // destroyItemSlot trash bin — not named categories; skipped.
                out.put(SlotGroupCategory.CRAFTING_OUTPUT, s.subList(0, 1));
                out.put(SlotGroupCategory.CRAFTING_INPUT, s.subList(1, 5));
                out.put(SlotGroupCategory.PLAYER_ARMOR, s.subList(5, 9));
                out.put(SlotGroupCategory.PLAYER_INVENTORY, s.subList(9, 36));
                out.put(SlotGroupCategory.PLAYER_HOTBAR, s.subList(36, 45));
                out.put(SlotGroupCategory.PLAYER_OFFHAND, s.subList(45, 46));
            }
            // Any other slot count (< 46, likely transient rebuild state)
            // returns empty — silent skip rather than partial match.
            return Map.copyOf(out);
        });
    }
}
