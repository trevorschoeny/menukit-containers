package com.trevorschoeny.menukit.core;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Projects {@code InventoryMenu}-bound slots onto <b>foreign</b> menus — chests,
 * furnaces, donkeys, any {@link AbstractContainerMenu} — so a registered slot a
 * consumer registered for the player inventory <em>also</em> appears, clicks, and
 * syncs on a container screen. The second leg of inventory-screen parity, past
 * "every screen whose menu already carries the slot" to "every screen that shows
 * the player inventory."
 *
 * <h3>The §0019 line this deliberately crosses</h3>
 *
 * {@link MKCSlots} ships <b>no</b> registry on purpose: for the player's own
 * {@code InventoryMenu} the consumer owns the {@code <init>} mixin, so the library
 * injects nothing. Foreign menus have no such consumer-owned per-menu seam —
 * {@code AbstractContainerMenu}'s constructor doesn't carry the player, and
 * mixing every (incl. modded) menu class isn't generic. To cover them the library
 * must own a player-lifecycle seam ({@code ServerPlayer.initMenu} server-side,
 * {@code ScreenEvents.AFTER_INIT} client-side) and therefore a <b>registry of what
 * to project</b>. This is the one platform-shaped primitive, kept as narrow as
 * possible: a registry of slot <em>factories the library merely invokes</em>
 * (data + a deterministic build function), never a behavior callback that decides
 * interaction semantics. The slots it builds are ordinary {@link MKCSlot}s, so
 * render / click→Storage / creative / sync all ride the same vanilla-slot paths
 * the {@code InventoryMenu} slot already uses — the library adds a slot type to a
 * foreign menu's list, it invents no behavior.
 *
 * <h3>Sync safety — the load-bearing contract</h3>
 *
 * The slots must appear on the <b>server</b> menu and the <b>client</b> menu with
 * an <em>identical set and order</em>, established <em>before</em> the menu's first
 * content sync, or {@code remoteSlots}/{@code broadcastChanges} desync (item
 * flicker/dupe). Both seams therefore call this one {@link #appendProjectedSlots}
 * against the same registry:
 * <ul>
 *   <li><b>server:</b> {@code ServerPlayer.initMenu} HEAD — runs after
 *       {@code createMenu} but <em>before</em> {@code initMenu} sends the initial
 *       {@code ContainerSetContent}, so that packet already includes the slots;</li>
 *   <li><b>client:</b> {@code ScreenEvents.AFTER_INIT} — fires synchronously inside
 *       {@code handleOpenScreen}, <em>before</em> the content packet is processed,
 *       so the client menu carries the slots when it arrives.</li>
 * </ul>
 * Because both sides iterate the same registered sources in the same order and each
 * source's factory builds the same slot (same player-attached {@code Storage}
 * size, same layout), the appended tail blocks are byte-identical. Registry order,
 * reveal-predicate symmetry, and storage-size symmetry are the invariants to keep.
 *
 * <h3>Relationship to the rest of the kit</h3>
 *
 * Projection only puts the <em>slots</em> on the foreign menu. Drawing them is the
 * panel pipeline (each slot is a {@code SlotElement} on its {@code MKCContainerPanel},
 * which renders inline on whatever screen its parity matcher accepts); shift-click into
 * them is {@link MKCSlotQuickMove}; creative placement is the (now menu-general)
 * creative-set-slot bridge. A consumer that wants pockets on a chest registers a
 * projection source here <em>and</em> widens its panel's parity targeting to that screen.
 */
public final class MKCSlotProjection {

    private MKCSlotProjection() {}

    /**
     * Builds (appends) a slot onto a matching foreign menu. Called on BOTH sides
     * for each opened menu the {@link #register registered} predicate accepts, so
     * it <b>must be deterministic</b> — same slot set, same slot order, same
     * storage size regardless of side. Typically just
     * {@code MKCSlots.onto(menu, player)....register()} with the same parameters
     * the consumer uses for the player inventory.
     */
    @FunctionalInterface
    public interface ProjectionFactory {
        void project(AbstractContainerMenu menu, Player player);
    }

    private record Source(Predicate<AbstractContainerMenu> appliesTo, ProjectionFactory factory) {}

    // Strong refs — consumers register once at init and hold nothing.
    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    // Per-menu-instance guard so a menu projected once is never double-appended
    // (e.g. if a future path re-enters a seam for the same instance). Weak so a
    // closed menu is collected. Server and client menus are distinct instances,
    // each guarded on its own side.
    private static final Set<AbstractContainerMenu> PROJECTED =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Registers a projection source: project the slot built by {@code factory}
     * onto every opened menu {@code appliesTo} accepts. Call once at mod init.
     *
     * <p>Both {@code appliesTo} and {@code factory} MUST evaluate identically on
     * the logical server and the client (predicate on menu class/type/synced
     * content; factory over a player-attached {@code Storage}) — see the sync-safety
     * note in the class doc. Opt-in per slot: a slot that should stay
     * inventory-only simply isn't registered here.
     */
    public static void register(Predicate<AbstractContainerMenu> appliesTo,
                                ProjectionFactory factory) {
        SOURCES.add(new Source(appliesTo, factory));
    }

    /**
     * Appends every matching projected slot onto {@code menu}. Invoked by the
     * library's server ({@code initMenu} HEAD) and client ({@code AFTER_INIT})
     * seams; idempotent per menu instance. No-op when no source matches (so the
     * player's own {@code InventoryMenu}, never registered here, is untouched).
     */
    public static void appendProjectedSlots(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null || SOURCES.isEmpty()) return;
        synchronized (PROJECTED) {
            if (!PROJECTED.add(menu)) return; // this instance already projected
        }
        for (Source source : SOURCES) {
            if (source.appliesTo.test(menu)) {
                source.factory.project(menu, player);
            }
        }
    }
}
