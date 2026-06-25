package com.trevorschoeny.menukit.core;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Per-attachment death-drop policy for player-scoped slot content (death
 * handling, Phase 1). Conforms to the de-facto standard shared by Trinkets
 * (Fabric — our loader) and Curios (NeoForge): a small enum with a
 * {@code DEFAULT} sentinel that resolves to vanilla-identical behavior.
 *
 * <ul>
 *   <li><b>DEFAULT</b> — zero-config parity (§0051 philosophy applied to death):
 *       resolves per stack down the vanilla ladder ({@code keepInventory} → KEEP;
 *       else Curse of Vanishing → DESTROY; else DROP). A consumer who declares
 *       nothing gets vanilla behavior, both gamerule states under one
 *       implementation.</li>
 *   <li><b>KEEP</b> — soulbound: the item survives death regardless of
 *       {@code keepInventory} (carried across respawn by the attachment's
 *       {@code copyOnDeath}).</li>
 *   <li><b>DROP</b> — always drops at the death location, even with
 *       {@code keepInventory} on.</li>
 *   <li><b>DESTROY</b> — always vanishes (a cursed / bound slot).</li>
 * </ul>
 *
 * <p><b>Explicit rules are a floor that overrides {@code keepInventory}</b>
 * (matching Trinkets and Curios): a {@code KEEP} slot stays kept even with the
 * gamerule off — that is the whole point of soulbound. Only {@code DEFAULT}
 * consults the gamerule.
 */
public enum DropRule {
    KEEP, DROP, DESTROY, DEFAULT;

    /**
     * Resolves this (possibly {@code DEFAULT}) rule to a concrete action for a
     * specific stack. Explicit {@code KEEP}/{@code DROP}/{@code DESTROY} return
     * unchanged (overriding {@code keepInventory}); {@code DEFAULT} walks the
     * vanilla ladder. Never returns {@code DEFAULT}.
     */
    public DropRule resolve(ItemStack stack, boolean keepInventory) {
        if (this != DEFAULT) return this;
        if (keepInventory) return KEEP;
        // Curse of Vanishing — the item destroys itself on death. Vanilla's own
        // destroyVanishingCursedItems() uses this same PREVENT_EQUIPMENT_DROP check.
        if (EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
            return DESTROY;
        }
        return DROP;
    }
}
