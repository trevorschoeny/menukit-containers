package com.trevorschoeny.menukit.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mending primitive's <b>consumer candidate hook</b> — lets a consumer
 * contribute EXTRA items into MKC's unified XP-repair pool, beyond the opt-in
 * registered slots ({@code SlotGroup.mendsFromXp}).
 *
 * <p>Why it exists: a consumer like Inventory Max wants a "mend any item in the
 * inventory" feature. Without this hook it would need its <em>own</em>
 * {@code ExperienceOrb} mixin, and the two would fight over the same per-orb
 * repair (double-dipping the XP). Registering a {@link Provider} here feeds those
 * extra items into the <em>same</em> pool MKC already unifies, so one orb repairs
 * one item — no conflict.
 *
 * <p><b>MKC stays out of policy.</b> MKC never decides to mend the vanilla
 * inventory itself — it only mends what a consumer hands it. The "which items"
 * decision (e.g. gated behind a broad-mending toggle) lives entirely in the
 * consumer's {@link Provider}.
 *
 * <p><b>Candidate contract.</b> MKC applies the vanilla predicate itself (damaged
 * AND carries the {@code REPAIR_WITH_XP} effect), so a provider may hand over a
 * whole inventory unfiltered. If a candidate is chosen, MKC repairs
 * {@link Candidate#stack()} <em>in place</em> with vanilla's math, then calls
 * {@link Candidate#onRepaired()} — that callback must persist + sync the source
 * (write the stack back / mark its storage dirty), since an in-place durability
 * change alone may not round-trip to disk.
 */
public final class MendingCandidates {

    /** One mendable item plus how to persist its repair. */
    public interface Candidate {
        /** The stack MKC repairs in place if this candidate is chosen. */
        ItemStack stack();

        /**
         * Called AFTER MKC repairs {@link #stack()} — persist + sync the source
         * (write back / mark dirty). Runs once per orb, only if this candidate
         * was the one chosen and repaired.
         */
        void onRepaired();
    }

    /** Supplies a player's extra mend candidates when an XP orb is collected. */
    @FunctionalInterface
    public interface Provider {
        /** Extra candidates for this player (server-side). Return empty for none. */
        List<Candidate> candidatesFor(ServerPlayer player);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Registers a candidate provider. Call once at mod init. */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    /** Gathers all registered providers' candidates for the player (empty if none). */
    public static List<Candidate> gather(ServerPlayer player) {
        if (PROVIDERS.isEmpty()) return List.of();
        List<Candidate> all = new ArrayList<>();
        for (Provider provider : PROVIDERS) {
            List<Candidate> c = provider.candidatesFor(player);
            if (c != null && !c.isEmpty()) all.addAll(c);
        }
        return all;
    }

    private MendingCandidates() {}
}
