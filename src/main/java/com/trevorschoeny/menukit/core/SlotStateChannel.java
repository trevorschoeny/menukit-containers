package com.trevorschoeny.menukit.core;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

/**
 * Typed per-slot state channel — the consumer-facing handle for reading and
 * writing slot state. Created via
 * {@link MKSlotState#register(Identifier, Codec, StreamCodec, Object)}.
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
        T defaultValue) {

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
     * Required for server-side reads on BE/entity-backed containers (v1 is
     * per-player private; the library needs to know whose state to read).
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
     *               {@code null} for player-scoped keys where the UUID is in the key itself.
     */
    public T get(Player player, PersistentContainerKey key, int containerSlotIndex) {
        return MKSlotState.readPersistent(this, player, key, containerSlotIndex);
    }

    /** Writes to persistent storage directly. Server-only. */
    public void set(Player player, PersistentContainerKey key, int containerSlotIndex, T value) {
        MKSlotState.writePersistent(this, player, key, containerSlotIndex, value);
    }
}
