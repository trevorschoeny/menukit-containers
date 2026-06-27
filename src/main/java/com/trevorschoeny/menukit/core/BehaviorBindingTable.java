package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.AuthoritativeDeclarations;
import com.trevorschoeny.menukit.window.BehaviorKey;
import com.trevorschoeny.menukit.window.Decl;
import com.trevorschoeny.menukit.window.GroupKey;
import com.trevorschoeny.menukit.window.ServerTierBridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MKC's server-authoritative behavior store — the implementation of MK's two
 * server-tier ports ({@link ServerTierBridge} write side, {@link
 * AuthoritativeDeclarations} read/AXIS-1 side). Registered with
 * {@code ServerTier.install(...)} at MKC init so the engine routes server-tier
 * declarations here and consults this above the client cascade.
 *
 * <h2>Authority + the presence fast-path</h2>
 *
 * This holds the SERVER-tier declarations (gating, drop-rule, …) keyed by
 * {@link Address}, with the same per-slot &gt; per-group walk the client engine
 * uses, but at server authority. {@link #resolve} returns the server's winning
 * {@link Decl.Set}, or {@link Decl#inherit()} when the server does not override
 * (so the engine falls through to the client tier / library default).
 *
 * <p>{@link #isEmpty()} / {@link #hasBinding(Address)} back the "the slots nobody
 * touches stay exactly vanilla" guarantee: the Phase-4 server seam early-outs on
 * any address with no binding, so an un-addressed slot incurs zero library logic.
 *
 * <p>(The store/walk mirrors the MK client engine's — the cascade algorithm is
 * the same; the two tiers differ only in authority and side, not in shape.)
 */
public final class BehaviorBindingTable implements ServerTierBridge, AuthoritativeDeclarations {

    public static final BehaviorBindingTable INSTANCE = new BehaviorBindingTable();

    private BehaviorBindingTable() {}

    private final Map<Address, Map<BehaviorKey<?>, Decl<?>>> perAddress = new ConcurrentHashMap<>();
    private final List<GroupBinding> groups = new CopyOnWriteArrayList<>();

    private record GroupBinding(GroupKey group, Map<BehaviorKey<?>, Decl<?>> decls) {}

    // ── ServerTierBridge (write) ────────────────────────────────────────

    @Override
    public <V> void declare(Address address, BehaviorKey<V> key, Decl<V> decl) {
        perAddress.computeIfAbsent(address, a -> new ConcurrentHashMap<>()).put(key, decl);
    }

    @Override
    public <V> void declareGroup(GroupKey group, BehaviorKey<V> key, Decl<V> decl) {
        bindingFor(group).put(key, decl);
    }

    // ── AuthoritativeDeclarations (read; AXIS-1) ────────────────────────

    @Override
    public <V> Decl<V> resolve(Address address, BehaviorKey<V> key) {
        Decl<V> slot = declAt(address, key);
        if (slot instanceof Decl.Set<V>) return slot;           // server overrides at slot
        Decl<V> group = declForGroups(address, key);
        if (group instanceof Decl.Set<V>) return group;         // server overrides at group
        return Decl.inherit();                                  // server does not override
    }

    // ── presence (Phase-4 seam fast-path) ───────────────────────────────

    /** Whether the table holds any binding at all. */
    public boolean isEmpty() {
        return perAddress.isEmpty() && groups.isEmpty();
    }

    /** Whether {@code address} has any server binding (per-slot or via a group). */
    public boolean hasBinding(Address address) {
        if (perAddress.containsKey(address)) return true;
        for (GroupBinding b : groups) {
            if (b.group().contains(address)) return true;
        }
        return false;
    }

    // ── internals (parallel to the MK client engine) ────────────────────

    @SuppressWarnings("unchecked")
    private <V> Decl<V> declAt(Address address, BehaviorKey<V> key) {
        Map<BehaviorKey<?>, Decl<?>> m = perAddress.get(address);
        return m == null ? null : (Decl<V>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private <V> Decl<V> declForGroups(Address address, BehaviorKey<V> key) {
        Decl<V> result = null;
        for (GroupBinding b : groups) {                 // registration order
            if (!b.group().contains(address)) continue;
            Decl<?> d = b.decls().get(key);
            if (d != null) result = (Decl<V>) d;        // last matching group wins
        }
        return result;
    }

    private synchronized Map<BehaviorKey<?>, Decl<?>> bindingFor(GroupKey group) {
        for (GroupBinding b : groups) {
            if (b.group().equals(group)) return b.decls();
        }
        Map<BehaviorKey<?>, Decl<?>> m = new ConcurrentHashMap<>();
        groups.add(new GroupBinding(group, m));
        return m;
    }
}
