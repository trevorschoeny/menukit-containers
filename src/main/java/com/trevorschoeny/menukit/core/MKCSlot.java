package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.WindowEngine;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * A vanilla {@link Slot} to the outside world. Carries its coordinates
 * in the hierarchy as final fields and delegates behavior to its owning
 * {@link SlotGroup}.
 *
 * <p>This is the substitutability contract in code. Every override either
 * calls {@code super} or delegates in a way that preserves vanilla's
 * behavioral contract:
 * <ul>
 *   <li>{@code mayPlace} — group policy AND super (mixin chain composes)</li>
 *   <li>{@code mayPickup} — group policy AND super</li>
 *   <li>{@code getMaxStackSize} — min of group limit and super</li>
 *   <li>{@code getItem} — returns EMPTY when inert, otherwise super</li>
 *   <li>{@code isActive} — returns false when panel is hidden</li>
 * </ul>
 *
 * <p>A mod that mixins into {@code Slot.mayPlace} to add a global filter
 * sees its filter run on MenuKit slots via the {@code super.mayPlace} call.
 * The most restrictive answer wins. A mod iterating slots sees inert
 * MenuKit slots as empty, which is a valid state for any slot.
 *
 * <p>Part of the canonical MenuKit hierarchy:
 * Screen → Panel → SlotGroup → MKCSlot
 */
public class MKCSlot extends Slot {

    // ── Coordinates in the hierarchy (final — never desync) ─────────────
    private final String panelId;
    private final String groupId;
    private final int localIndex;

    // ── Owning group (final — behavior delegation target) ───────────────
    private final SlotGroup group;

    // ── Owning panel (final — visibility query target for inertness) ────
    private final Panel panel;

    // ── Presentation position (§0047 — mutable; identity stays frozen) ──
    // The slot render + input helpers read these instead of the final vanilla
    // Slot.x/y, so a registered panel can move at runtime. They default to the
    // constructed coords; for vanilla-rendered (non-slot) MKCSlots they
    // stay equal to Slot.x/y and are dormant.
    private int renderX;
    private int renderY;

    /**
     * @param container      vanilla Container adapter (from handler construction)
     * @param containerIndex index within the container
     * @param x              screen x position
     * @param y              screen y position
     * @param group          owning SlotGroup (behavior delegation target)
     * @param panel          owning Panel (visibility query target for inertness)
     * @param groupId        group identifier within the panel
     * @param localIndex     slot index within the group (0-based)
     */
    public MKCSlot(Container container, int containerIndex, int x, int y,
                       SlotGroup group, Panel panel, String groupId,
                       int localIndex) {
        super(container, containerIndex, x, y);
        this.renderX = x;
        this.renderY = y;
        this.group = group;
        this.panel = panel;
        this.panelId = panel.getId();
        this.groupId = groupId;
        this.localIndex = localIndex;
    }

    // ── Coordinate Accessors ────────────────────────────────────────────

    /** Panel this slot belongs to. */
    public String getPanelId() { return panelId; }

    /** Group this slot belongs to within its panel. */
    public String getGroupId() { return groupId; }

    /** Index within the group (0-based). */
    public int getLocalIndex() { return localIndex; }

    /** The SlotGroup that owns this slot's behavior. */
    public SlotGroup getGroup() { return group; }

    // ── Address (the side-table key for ALL this slot's behavior) ────────
    // THE ONE WINDOW: behavior lives in the engine keyed by this address, never
    // on the slot. Identity fields are final, so the address is immutable — cache
    // it lazily (a benign double-compute is harmless; the value is equal either way).
    private Address cachedAddress;

    /** This slot's menu-independent created {@link Address} — the key behavior is resolved by. */
    public Address address() {
        Address a = cachedAddress;
        if (a == null) {
            a = CreatedSlotAdapter.addressOf(this);
            cachedAddress = a;
        }
        return a;
    }

    // ── Presentation position (§0047) ───────────────────────────────────

    /**
     * Current presentation x — where the slot render + input helpers draw and
     * hit-test this slot. Equals the constructed x until {@link #setRenderPosition}.
     */
    public int renderX() { return renderX; }

    /** Current presentation y. @see #renderX() */
    public int renderY() { return renderY; }

    /**
     * Moves this slot's presentation position at runtime (§0047 — position is
     * mutable presentation; the slot's vanilla {@code Slot.x/y} identity and its
     * sync are untouched). Client-side: the slot render + input helpers follow
     * this immediately. Call per frame to drive a layout that depends on runtime
     * state — e.g. a row that re-centers as its count changes. The server neither
     * renders nor needs it.
     */
    public void setRenderPosition(int x, int y) {
        this.renderX = x;
        this.renderY = y;
    }

    // ── Inertness ───────────────────────────────────────────────────────

    /**
     * Returns true when this slot's panel is hidden. An inert slot is
     * indistinguishable from a non-existent one to the outside world:
     * getItem returns EMPTY, canInsert returns false, quick-move skips it.
     *
     * <p>Exposed for well-behaved third parties that want to check
     * explicitly rather than relying on the behavioral methods.
     */
    public boolean isInert() {
        return !panel.isVisible();
    }

    // ── Behavioral Overrides (the substitutability contract) ────────────
    //
    // Each override composes with super so that other mods' mixins into
    // Slot run in the chain. MenuKit's additions layer ON TOP of vanilla
    // behavior, never replace it.

    /**
     * Can this stack be placed in this slot?
     *
     * <p>Resolves the {@code GATING} behavior from the engine by this slot's
     * {@link #address()} and AND-composes with {@code super} — so a mixin on
     * {@code Slot.mayPlace} still runs via the super call, and the most restrictive
     * answer wins. The gate (not this slot) holds the behavior; an un-gated slot
     * resolves to {@link SlotGate#OPEN} = pure vanilla. This is the direct-click
     * insertion point for a created slot (the menu seams cover shift-click/automation).
     */
    @Override
    public boolean mayPlace(ItemStack stack) {
        if (isInert()) return false;
        SlotGate gate = WindowEngine.resolve(address(), MKCBehaviorKeys.GATING);
        return gate.mayPlace(stack, GatingContext.current()) && super.mayPlace(stack);
    }

    /**
     * Can items be taken from this slot?
     *
     * <p>Resolves {@code GATING} from the engine and AND-composes with {@code super}.
     */
    @Override
    public boolean mayPickup(Player player) {
        if (isInert()) return false;
        // Curse of Binding: still read from the group here; 5c moves it to the
        // BINDING engine key. Survival only; creative bypasses (set-slot bridge
        // never calls mayPickup). Mirrors vanilla's armor-slot binding check.
        ItemStack stack = super.getItem();
        if (group.bindsCursedItems() && !player.hasInfiniteMaterials()
                && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            return false;
        }
        SlotGate gate = WindowEngine.resolve(address(), MKCBehaviorKeys.GATING);
        return gate.mayPickup(player, GatingContext.current()) && super.mayPickup(player);
    }

    /**
     * Max stack size for this slot, given a specific item.
     *
     * <p>The {@code GATING} gate may cap it (absorbing the old policy stack limit);
     * re-clamped to vanilla's own limit (including any mixin modifications via
     * super), so a gate can lower but never raise the cap.
     */
    @Override
    public int getMaxStackSize(ItemStack stack) {
        int vanillaMax = super.getMaxStackSize(stack);
        SlotGate gate = WindowEngine.resolve(address(), MKCBehaviorKeys.GATING);
        return Math.min(gate.maxStackSize(stack, vanillaMax), vanillaMax);
    }

    // No override for getMaxStackSize() (no-arg). The policy's maxStackSize
    // function is item-specific — calling it with ItemStack.EMPTY returns 1,
    // which would break everything. Vanilla's Slot.getMaxStackSize() returns
    // container.getMaxStackSize() (99), which is correct as the slot-level
    // capacity limit. Item-specific limits are handled by the ItemStack overload above.

    /**
     * Returns the item in this slot, or {@link ItemStack#EMPTY} when the
     * panel is inert. This is the data-level inertness — hidden panels'
     * backing storage is never exposed to the world.
     *
     * <p><b>Why this override is load-bearing.</b> Behavioral inertness
     * ({@link #isActive} false, {@link #mayPlace} false, {@link #mayPickup}
     * false) blocks interaction but not observation. Without this override,
     * any caller that reads {@code slot.getItem()} on a hidden slot — vanilla
     * {@code broadcastChanges} syncing to the client, foreign mixins on
     * {@code Slot.getItem}, consumer code iterating {@code menu.slots} —
     * sees the real backing content. That leaks per-player state held in
     * hidden-panel storage (e.g., lock metadata, private inventories, sort
     * state) across the client-server boundary and through arbitrary mod
     * hooks.
     *
     * <p>Falsifying to EMPTY while inert closes that seam. When the panel
     * becomes visible, {@code broadcastChanges} detects the change and
     * pushes real content to the client. This is a deliberate, localized
     * falsification — the backing {@link com.trevorschoeny.menukit.core.Storage}
     * still holds the truth, accessible via the storage directly for
     * consumer code that owns the panel.
     *
     * <p>Contract: inertness is "hidden == invisible to the world" —
     * interaction <em>and</em> observation. Both layers are load-bearing
     * for the library's canonical inertness guarantee (see
     * {@code /mkverify all}'s Inertness + SyncSafety probes).
     */
    @Override
    public ItemStack getItem() {
        if (isInert()) return ItemStack.EMPTY;
        return super.getItem();
    }

    /**
     * Returns whether this slot is active (visible and interactable).
     *
     * <p>When false, vanilla skips this slot for rendering and hover
     * detection. Combined with {@link #getItem} returning EMPTY while
     * inert, this makes hidden slots truly invisible to the world.
     */
    @Override
    public boolean isActive() {
        if (isInert()) return false;
        return super.isActive();
    }
}
