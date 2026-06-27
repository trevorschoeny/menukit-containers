package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.CreatedSlotResolver;
import com.trevorschoeny.menukit.window.KindTag;
import com.trevorschoeny.menukit.window.OwnerRef;
import com.trevorschoeny.menukit.window.OwnerScope;
import com.trevorschoeny.menukit.window.ScreenFamilyKey;
import com.trevorschoeny.menukit.window.Token;

import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * MKC's implementation of MK's {@link CreatedSlotResolver} port — resolves a
 * {@code CREATED_SLOT} {@link Address} to its live {@code MKCSlot} and frame
 * position. This is the MKC half of THE ONE WINDOW Phase 2, and the home of the
 * canonical created-slot {@link #addressOf} encoding that mint and resolve share.
 *
 * <h2>Created-slot identity is MENU-INDEPENDENT</h2>
 *
 * A created slot (e.g. a pocket) is the same logical slot wherever it is shown —
 * on the survival {@code InventoryMenu}, wrapped onto the creative item-picker
 * menu, or projected onto a foreign container. So its address must NOT depend on
 * the rendering menu's family, or the same pocket would get different addresses
 * across survival↔creative. Its address roots at a CONSTANT family
 * ({@link #CREATED_FAMILY}) and is identified purely by its panel + declaration
 * id ({@code panelId} / {@code groupId} / {@code localIndex}). Consequently
 * resolution does not apply a menu-family gate (that gate is for vanilla slots,
 * which ARE menu-intrinsic); the identity scan below IS the presence check.
 *
 * <h2>Replaces the per-frame scan with a session-bound cache (§3.7)</h2>
 *
 * The old {@code SlotElement.resolve()} did an O(n) identity scan over
 * {@code menu.slots} every frame per element. This keeps the same identity match
 * but binds {@code Address -> live menu index} in a cache keyed by the live menu
 * instance. A reopen produces a NEW menu instance, so the cache is invalidated
 * for free (and a GC'd menu drops its entry via the {@link WeakHashMap}). The
 * cache holds only {@code int} indices — NO {@code Slot} or container reference
 * (§3.7) — and the mutable draw position ({@code renderX/renderY}, §0047) is read
 * fresh on every resolve. Client-thread only.
 */
public final class CreatedSlotAdapter implements CreatedSlotResolver {

    /** Singleton — the adapter is stateless (cache + encoding are static). */
    public static final CreatedSlotAdapter INSTANCE = new CreatedSlotAdapter();

    private CreatedSlotAdapter() {}

    /** The constant root family of every created slot — see class doc (menu-independent). */
    private static final ScreenFamilyKey CREATED_FAMILY =
            ScreenFamilyKey.of(Identifier.fromNamespaceAndPath("menukit", "created"));

    // groupId<SEP>localIndex: a NUL separator a normal id never contains, so the
    // composite is injective over distinct (groupId, localIndex) pairs.
    private static final String SEP = String.valueOf((char) 0);  // NUL — never appears in a normal id

    // menu instance -> (address -> matching slot's menu index). Reopen = new menu
    // = fresh cache; GC'd menu drops its entry. Holds only indices (§3.7).
    private static final Map<AbstractContainerMenu, Map<Address, Integer>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public @Nullable CreatedResolution resolve(AbstractContainerMenu menu, Address address) {
        Map<Address, Integer> bindings = CACHE.computeIfAbsent(menu, m -> new HashMap<>());

        // Fast path: a cached index, re-validated against the live slot's identity.
        Integer cachedIndex = bindings.get(address);
        if (cachedIndex != null) {
            MKCSlot mk = mkcAt(menu, cachedIndex);
            if (mk != null && addressOf(mk).equals(address)) {
                return resolution(menu, cachedIndex, mk);
            }
            bindings.remove(address); // stale — fall through to rescan
        }

        // Scan once, populating the full binding map (every created slot, free),
        // and return the requested match if present.
        CreatedResolution hit = null;
        for (int i = 0; i < menu.slots.size(); i++) {
            MKCSlot mk = mkcAt(menu, i);
            if (mk == null) continue;
            Address a = addressOf(mk);
            bindings.put(a, i);
            if (a.equals(address)) {
                hit = resolution(menu, i, mk);
            }
        }
        return hit;
    }

    /**
     * The canonical {@link Address} of a created slot — the single source of
     * truth shared by resolution (here) and minting (Phase 5/6 creation). A pure
     * function of the slot's identity, independent of any rendering menu: a
     * constant root family, the slot's panel nested under it, and the slot's
     * {@code groupId + localIndex} as the token.
     */
    public static Address addressOf(MKCSlot mk) {
        OwnerRef owner = OwnerRef.nested(
                OwnerRef.root(CREATED_FAMILY, OwnerScope.primary()),
                Token.reg(panelKey(mk.getPanelId())));
        String declId = mk.getGroupId() + SEP + mk.getLocalIndex();
        return new Address(owner, Token.decl(declId), KindTag.CREATED_SLOT);
    }

    private static CreatedResolution resolution(AbstractContainerMenu menu, int index, MKCSlot mk) {
        // The in-menu slot (the creative wrapper on creative) for correct click
        // routing; the position from the unwrapped slot's mutable renderX/renderY.
        return new CreatedResolution(menu.slots.get(index), mk.renderX(), mk.renderY());
    }

    private static @Nullable MKCSlot mkcAt(AbstractContainerMenu menu, int index) {
        if (index < 0 || index >= menu.slots.size()) return null;
        return MKCSlotAccess.asMKCSlot(menu.slots.get(index));
    }

    /** A panel's String id as a deterministic {@link Identifier} (parse if valid, else synthetic). */
    private static Identifier panelKey(String panelId) {
        Identifier parsed = Identifier.tryParse(panelId);
        if (parsed != null) return parsed;
        return Identifier.fromNamespaceAndPath("menukit",
                "panel/" + panelId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
    }
}
