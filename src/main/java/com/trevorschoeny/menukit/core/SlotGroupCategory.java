package com.trevorschoeny.menukit.core;

/**
 * Pure identity tag identifying a slot group for SlotGroupContext panel
 * targeting. A category says <i>"treat this slot group as PLAYER_INVENTORY
 * (or whatever) for targeting purposes."</i> Categories carry no behavior;
 * where to anchor and what to render lives on the adapter / panel.
 *
 * <p>See {@code Design Docs/Phase 12.5/M8_FOUR_CONTEXT_MODEL.md} §5.2 for
 * the identity-not-behavior design and §6 for the vanilla-v1 coverage
 * inventory this class hosts as public constants.
 *
 * <p>Modded consumers define their own via
 * {@code new SlotGroupCategory("mymod", "custom_output")} and register a
 * resolver against their menu class via
 * {@code SlotGroupCategories.register(MyMenu.class, ...)}. First-registration-
 * wins semantics; see {@link com.trevorschoeny.menukit.inject.SlotGroupCategories}.
 */
public record SlotGroupCategory(String namespace, String path) {

    // Vanilla 1.21.11 categories — exhaustive coverage per M8 Principle 11's
    // exhaustive-coverage exception (low per-item cost + high migration cost
    // if a consumer discovers a category that wasn't shipped). Each has a
    // corresponding resolver registered in MenuKitClient; see §6 of the
    // M8 design doc for the full mapping table.

    // ── Player-scoped ──────────────────────────────────────────────────

    public static final SlotGroupCategory PLAYER_INVENTORY =
            new SlotGroupCategory("menukit", "player_inventory");
    public static final SlotGroupCategory PLAYER_HOTBAR =
            new SlotGroupCategory("menukit", "player_hotbar");
    public static final SlotGroupCategory PLAYER_ARMOR =
            new SlotGroupCategory("menukit", "player_armor");
    public static final SlotGroupCategory PLAYER_OFFHAND =
            new SlotGroupCategory("menukit", "player_offhand");

    // ── Storage containers ─────────────────────────────────────────────

    public static final SlotGroupCategory CHEST_STORAGE =
            new SlotGroupCategory("menukit", "chest_storage");
    public static final SlotGroupCategory SHULKER_STORAGE =
            new SlotGroupCategory("menukit", "shulker_storage");
    public static final SlotGroupCategory DISPENSER_STORAGE =
            new SlotGroupCategory("menukit", "dispenser_storage");
    public static final SlotGroupCategory HOPPER_STORAGE =
            new SlotGroupCategory("menukit", "hopper_storage");

    // ── Crafting family ────────────────────────────────────────────────

    public static final SlotGroupCategory CRAFTING_INPUT =
            new SlotGroupCategory("menukit", "crafting_input");
    public static final SlotGroupCategory CRAFTING_OUTPUT =
            new SlotGroupCategory("menukit", "crafting_output");
    public static final SlotGroupCategory CRAFTER_GRID =
            new SlotGroupCategory("menukit", "crafter_grid");
    public static final SlotGroupCategory CRAFTER_RESULT =
            new SlotGroupCategory("menukit", "crafter_result");

    // ── Furnace family (shared across furnace / smoker / blast furnace) ─

    public static final SlotGroupCategory FURNACE_INPUT =
            new SlotGroupCategory("menukit", "furnace_input");
    public static final SlotGroupCategory FURNACE_FUEL =
            new SlotGroupCategory("menukit", "furnace_fuel");
    public static final SlotGroupCategory FURNACE_OUTPUT =
            new SlotGroupCategory("menukit", "furnace_output");

    // ── Utility blocks with slots ──────────────────────────────────────

    public static final SlotGroupCategory ENCHANTING_INPUT =
            new SlotGroupCategory("menukit", "enchanting_input");
    public static final SlotGroupCategory ENCHANTING_LAPIS =
            new SlotGroupCategory("menukit", "enchanting_lapis");

    public static final SlotGroupCategory ANVIL_INPUT =
            new SlotGroupCategory("menukit", "anvil_input");
    public static final SlotGroupCategory ANVIL_OUTPUT =
            new SlotGroupCategory("menukit", "anvil_output");

    public static final SlotGroupCategory GRINDSTONE_INPUT =
            new SlotGroupCategory("menukit", "grindstone_input");
    public static final SlotGroupCategory GRINDSTONE_OUTPUT =
            new SlotGroupCategory("menukit", "grindstone_output");

    public static final SlotGroupCategory SMITHING_TEMPLATE =
            new SlotGroupCategory("menukit", "smithing_template");
    public static final SlotGroupCategory SMITHING_BASE =
            new SlotGroupCategory("menukit", "smithing_base");
    public static final SlotGroupCategory SMITHING_ADDITION =
            new SlotGroupCategory("menukit", "smithing_addition");
    public static final SlotGroupCategory SMITHING_OUTPUT =
            new SlotGroupCategory("menukit", "smithing_output");

    public static final SlotGroupCategory LOOM_BANNER =
            new SlotGroupCategory("menukit", "loom_banner");
    public static final SlotGroupCategory LOOM_DYE =
            new SlotGroupCategory("menukit", "loom_dye");
    public static final SlotGroupCategory LOOM_PATTERN =
            new SlotGroupCategory("menukit", "loom_pattern");
    public static final SlotGroupCategory LOOM_OUTPUT =
            new SlotGroupCategory("menukit", "loom_output");

    public static final SlotGroupCategory STONECUTTER_INPUT =
            new SlotGroupCategory("menukit", "stonecutter_input");
    public static final SlotGroupCategory STONECUTTER_OUTPUT =
            new SlotGroupCategory("menukit", "stonecutter_output");

    public static final SlotGroupCategory CARTOGRAPHY_MAP =
            new SlotGroupCategory("menukit", "cartography_map");
    public static final SlotGroupCategory CARTOGRAPHY_ADDITIONAL =
            new SlotGroupCategory("menukit", "cartography_additional");
    public static final SlotGroupCategory CARTOGRAPHY_OUTPUT =
            new SlotGroupCategory("menukit", "cartography_output");

    // ── Brewing ────────────────────────────────────────────────────────

    public static final SlotGroupCategory BREWING_POTIONS =
            new SlotGroupCategory("menukit", "brewing_potions");
    public static final SlotGroupCategory BREWING_INGREDIENT =
            new SlotGroupCategory("menukit", "brewing_ingredient");
    public static final SlotGroupCategory BREWING_FUEL =
            new SlotGroupCategory("menukit", "brewing_fuel");

    // ── Trading ────────────────────────────────────────────────────────

    public static final SlotGroupCategory MERCHANT_PAYMENT =
            new SlotGroupCategory("menukit", "merchant_payment");
    public static final SlotGroupCategory MERCHANT_RESULT =
            new SlotGroupCategory("menukit", "merchant_result");

    // ── Beacon ─────────────────────────────────────────────────────────

    public static final SlotGroupCategory BEACON_PAYMENT =
            new SlotGroupCategory("menukit", "beacon_payment");

    // ── Mount (shared via AbstractMountInventoryMenu) ──────────────────

    public static final SlotGroupCategory MOUNT_SADDLE =
            new SlotGroupCategory("menukit", "mount_saddle");
    public static final SlotGroupCategory MOUNT_BODY_ARMOR =
            new SlotGroupCategory("menukit", "mount_body_armor");
    public static final SlotGroupCategory MOUNT_STORAGE =
            new SlotGroupCategory("menukit", "mount_storage");

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
