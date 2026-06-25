package com.trevorschoeny.menukit.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Set;

/**
 * §0052 floor refinement — detects whether any known <b>grave-container</b> mod
 * is installed (a mod that holds a dead player's items in a persistent grave,
 * chest, or bag).
 *
 * <p>Used by {@link com.trevorschoeny.menukit.core.attachment.PlayerDeathDropHandler}:
 * when such a mod is present, the floor's death-drops are flagged
 * <em>never-despawn</em> so they persist beside the grave instead of expiring in
 * the vanilla 5 minutes — matching the grave's own persistence. With <b>no</b>
 * grave mod present, drops keep vanilla despawn, so grafted slots behave exactly
 * like a normal death drop (no asymmetry where grafted items outlive vanilla ones).
 *
 * <p><b>Grave-container mods only.</b> Keep-inventory / void-rescue mods (e.g.
 * "Your Items Are Safe", "Lenient Death") are deliberately <em>excluded</em>: they
 * don't spawn a grave, so never-despawning floor items beside a non-existent grave
 * would reintroduce the very asymmetry this gate exists to prevent.
 *
 * <p>Detection is by mod id (the string {@link FabricLoader#isModLoaded(String)}
 * checks), computed once — mod presence is fixed for the run. The id list is
 * hand-maintained; keep it in sync with the grave-mod matrix in
 * {@code MOD_INTEGRATION_TRACKING.md}. Every id was read from the mod's actual
 * {@code fabric.mod.json} (slugs frequently differ from ids — e.g.
 * {@code pneumono_gravestones} ships id {@code gravestones}).
 *
 * <p><b>Fails safe, errs inclusive.</b> A wrong/missing id just means that grave
 * mod's users get vanilla despawn (the prior behavior), never a crash. Erring
 * toward more ids is correct: a false positive only makes floor items more
 * persistent (harmless); a false negative lets them expire beside a real grave
 * (the bug this closes).
 */
public final class GraveModPresence {

    /**
     * Known grave-container mod ids. Forge/NeoForge-only graves can never load on
     * Fabric ({@code isModLoaded} simply returns false) but are listed for
     * completeness. Several entries currently cap below 1.21.11 — kept so the
     * gate keeps working if those mods ship a newer build or on other MC versions.
     */
    private static final Set<String> GRAVE_MOD_IDS = Set.of(
            // ── true grave mods, live on 1.21.x Fabric ──
            "universal-graves",    // Universal Graves (Patbox)
            "gravestones",         // Pneumono's Gravestones  (slug pneumono_gravestones)
            "gravestone",          // "Gravestone" (n8-M4x and HKS-HNS both ship this id)
            "deathchest",          // Simple Death Chest      (slug simple-death-chest)
            "deadsimplebags",      // Dead Simple Bags (death backpack)
            // ── the mr_* datapack-mod grave family (ids never match their slugs) ──
            "mr_ly_graves",        // Graves (lullaby6)
            "mr_auto_graveslite",  // Auto Graves Lite
            "mr_vanilla_graves",   // Vanilla Graves
            "mr_simple_grave",     // Simple Graves
            "mr_conures_graves",   // Very Simple Gravestones (Conure)
            // ── verified Fabric grave ids that currently cap below 1.21.11 ──
            "yigd",                // You're in Grave Danger
            "workinggraves",       // Working Graves (StoneLabs)
            "forgottengraves",     // Forgotten Graves (Ginsm)
            // ── Fabric, legacy/pre-1.21 (harmless; future-proofing) ──
            "playergraves",        // Player Graves (OnyxStudios)
            "bettergraves",        // Better Graves (CerulanLumina)
            // ── Forge/NeoForge-only (never load on Fabric; completeness) ──
            "corpse",              // Corpse (henkelmax)
            "tombstone"            // Corail Tombstone (Corail31)
    );

    /** Cached at first access — mod presence cannot change after launch. */
    private static final boolean ANY_PRESENT = compute();

    private static boolean compute() {
        FabricLoader loader = FabricLoader.getInstance();
        for (String id : GRAVE_MOD_IDS) {
            if (loader.isModLoaded(id)) return true;
        }
        return false;
    }

    /** True if any known grave-container mod is installed. */
    public static boolean anyGraveModPresent() {
        return ANY_PRESENT;
    }

    private GraveModPresence() {}
}
