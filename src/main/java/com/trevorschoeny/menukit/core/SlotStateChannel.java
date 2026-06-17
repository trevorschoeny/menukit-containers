package com.trevorschoeny.menukit.core;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

/**
 * Typed per-slot state channel — the consumer-facing handle for reading and
 * writing slot state. Created via
 * {@link MKSlotState#register(Identifier, Codec, StreamCodec, Object)} (PRIVATE)
 * or {@link MKSlotState#register(Identifier, Codec, StreamCodec, Object, Visibility)}.
 *
 * <p>Dual-codec design (per THESIS.md principle 6): {@link #codec} is NBT-bound
 * for persistence (encoded to {@code Tag} via {@code NbtOps.INSTANCE}),
 * {@link #streamCodec} is binary for wire transport. Persisted values are
 * legible via vanilla's {@code /data get}; wire traffic stays lightweight.
 *
 * <p>See {@code menukit/Design Docs/Phase 12/M1_PER_SLOT_STATE.md} §4.4.
 */
public record SlotStateChannel<T>(
        Identifier id,
        Codec<T> codec,
        StreamCodec<RegistryFriendlyByteBuf, T> streamCodec,
        T defaultValue,
        Visibility visibility) {

    /**
     * Channel visibility (§0049).
     *
     * <ul>
     *   <li><b>PRIVATE</b> (default) — each viewer has their own value, keyed by
     *       UUID. The shipped per-player model (§0034).</li>
     *   <li><b>SHARED</b> — one player-agnostic value per slot, synced to and
     *       writable by every viewer. For cross-player features like Inventory
     *       Plus's Container Locks (one player locks slot 5 → every player sees
     *       it locked).</li>
     * </ul>
     *
     * <p>SHARED is only meaningful on containers that are both multi-player and
     * fixed-slot (placed shulker / chest / barrel). It is degenerate on the
     * ender chest (inherently per-player) and inapplicable to bundles (no fixed
     * slots) — see §0049 bounds.
     */
    public enum Visibility { PRIVATE, SHARED }

    /**
     * Backward-compatible constructor — produces a PRIVATE channel. Keeps every
     * existing {@code new SlotStateChannel<>(id, codec, streamCodec, default)}
     * call site (and the 4-arg {@link MKSlotState#register}) byte-for-byte
     * unchanged (§0049: private is the default).
     */
    public SlotStateChannel(Identifier id, Codec<T> codec,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec, T defaultValue) {
        this(id, codec, streamCodec, defaultValue, Visibility.PRIVATE);
    }

    // ── Slot-based reads ────────────────────────────────────────────────

    /**
     * Reads this channel's value at {@code slot}. Works on both sides when
     * the slot belongs to a player-scoped container ({@code Inventory},
     * {@code PlayerEnderChestContainer}). On the server with a BE/entity-backed
     * slot and no resolvable player, returns {@link #defaultValue}. Consumers
     * needing server-side BE/entity reads should call
     * {@link #get(Player, Slot)} with explicit player.
     */
    public T get(Slot slot) {
        return MKSlotState.read(this, slot, null);
    }

    /**
     * Reads this channel's value at {@code slot} with explicit player context.
     * Required for server-side reads on BE/entity-backed containers (the
     * per-player-private model needs to know whose state to read; SHARED
     * channels ignore the player but the argument is still accepted).
     */
    public T get(Player player, Slot slot) {
        return MKSlotState.read(this, slot, player);
    }

    // ── Slot-based writes ───────────────────────────────────────────────

    /** Writes {@code value} at {@code slot}. See {@link #get(Slot)} for side semantics. */
    public void set(Slot slot, T value) {
        MKSlotState.write(this, slot, null, value);
    }

    /** Writes {@code value} at {@code slot} with explicit player context. */
    public void set(Player player, Slot slot, T value) {
        MKSlotState.write(this, slot, player, value);
    }

    // ── Slot-less (persistent-key) API — server-only ────────────────────

    /**
     * Reads this channel's value from persistent storage directly.
     * Server-only; the client cannot synthesize a persistent key outside a
     * menu session.
     *
     * @param player for per-player-private containers (BE, entity). May be
     *               {@code null} for player-scoped keys where the UUID is in the
     *               key itself, or for SHARED channels (player-agnostic).
     */
    public T get(Player player, PersistentContainerKey key, int containerSlotIndex) {
        return MKSlotState.readPersistent(this, player, key, containerSlotIndex);
    }

    /** Writes to persistent storage directly. Server-only. */
    public void set(Player player, PersistentContainerKey key, int containerSlotIndex, T value) {
        MKSlotState.writePersistent(this, player, key, containerSlotIndex, value);
    }

    // ── Menu-free shared read (§0050) — server-only ─────────────────────

    /**
     * Reads the <b>SHARED</b> value at {@code (container, containerSlotIndex)}
     * with no {@link Slot}, no open menu, and no viewer — for automation
     * (hopper / dropper / dispenser) that touches a placed container directly by
     * index. The library resolves the container internally (the resolver stays
     * internal) and composes with composite resolution, so a double chest
     * resolves to its owning half.
     *
     * <p>Returns {@link #defaultValue} off-server, for an unresolvable container,
     * or for a PRIVATE channel — a private value is viewer-scoped and has no
     * viewer on this path, so this is the shared-read primitive (§0050).
     * Read-only: writes stay owner/menu-driven (and shared writes broadcast,
     * §0049). The consumer composes this read into its own enforcement (e.g.
     * blocking a hopper extract) — the library reports state, it does not enforce.
     */
    public T get(Container container, int containerSlotIndex) {
        return MKSlotState.readShared(this, container, containerSlotIndex);
    }
}
