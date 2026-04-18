package com.trevorschoeny.menukit.core;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.state.SlotStateClientCache;
import com.trevorschoeny.menukit.state.SlotStateRegistry;
import com.trevorschoeny.menukit.state.SlotStateServer;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Function;

/**
 * Public entry point for M1 per-slot persistent state. Consumers register
 * channels at mod init; read/write via the returned {@link SlotStateChannel}
 * handles.
 *
 * <p>See {@code menukit/Design Docs/Phase 12/M1_PER_SLOT_STATE.md}.
 */
public final class MKSlotState {

    private static final Logger LOGGER = LoggerFactory.getLogger("MenuKit-SlotState");

    private MKSlotState() {}

    // ── Channel registration ────────────────────────────────────────────

    /**
     * Registers a channel. First registration of an identifier wins;
     * subsequent calls with the same id return the already-registered channel
     * and log a warning. See M1 §4.6 for rationale.
     */
    @SuppressWarnings("unchecked")
    public static <T> SlotStateChannel<T> register(
            Identifier id, Codec<T> codec,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec, T defaultValue) {
        SlotStateChannel<T> existing = SlotStateRegistry.getChannelTyped(id);
        if (existing != null) {
            LOGGER.warn("[SlotState] channel {} already registered — returning existing instance", id);
            return existing;
        }
        SlotStateChannel<T> channel = new SlotStateChannel<>(id, codec, streamCodec, defaultValue);
        SlotStateRegistry.registerChannel(channel);
        LOGGER.info("[SlotState] registered channel {}", id);
        return channel;
    }

    /** Returns the channel with the given id, or empty if not registered. */
    public static Optional<SlotStateChannel<?>> getChannel(Identifier id) {
        return Optional.ofNullable(SlotStateRegistry.getChannel(id));
    }

    // ── Resolver registration (modded) ──────────────────────────────────

    /** Registers a resolver for a custom block-entity class. */
    public static <T extends BlockEntity> void registerContainerResolver(
            Class<T> clazz, Function<T, PersistentContainerKey> resolver) {
        SlotStateRegistry.registerBlockEntityResolver(clazz, resolver);
    }

    /** Registers a resolver for a custom entity class. */
    public static <T extends Entity> void registerEntityResolver(
            Class<T> clazz, Function<T, PersistentContainerKey> resolver) {
        SlotStateRegistry.registerEntityResolver(clazz, resolver);
    }

    // ── Internal read/write (called from SlotStateChannel) ──────────────

    static <T> T read(SlotStateChannel<T> channel, Slot slot, @Nullable Player player) {
        Player viewer = resolvePlayer(slot, player);
        if (isClientSide(viewer)) {
            return SlotStateClientCache.read(channel, slot);
        }
        Optional<PersistentContainerKey> keyOpt = SlotStateRegistry.resolve(slot.container);
        if (keyOpt.isEmpty()) return channel.defaultValue();
        int containerSlotIndex = slot.getContainerSlot();
        Tag tag = SlotStateServer.readTag(keyOpt.get(), viewer, channel.id(), containerSlotIndex);
        if (tag == null) return channel.defaultValue();
        return SlotStateServer.decode(channel, tag);
    }

    static <T> void write(SlotStateChannel<T> channel, Slot slot,
                           @Nullable Player player, T value) {
        Player viewer = resolvePlayer(slot, player);
        if (isClientSide(viewer)) {
            // Optimistic local update; fire C2S packet for server to persist + broadcast.
            SlotStateClientCache.write(channel, slot.container, slot.getContainerSlot(), value);
            com.trevorschoeny.menukit.network.SlotStateUpdateC2SPayload.sendFromClient(
                    channel, slot.index, SlotStateServer.encode(channel, value));
            return;
        }
        Optional<PersistentContainerKey> keyOpt = SlotStateRegistry.resolve(slot.container);
        if (keyOpt.isEmpty()) return;
        int containerSlotIndex = slot.getContainerSlot();
        Tag encoded = SlotStateServer.encode(channel, value);
        boolean ok = SlotStateServer.writeTag(keyOpt.get(), viewer, channel.id(), containerSlotIndex, encoded);
        if (!ok) return;
        // Broadcast back to the writing player so their cache stays in sync.
        if (viewer instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.trevorschoeny.menukit.network.SlotStateUpdateS2CPayload.sendTo(
                    sp, channel.id(), containerSlotIndex, encoded);
        }
    }

    static <T> T readPersistent(SlotStateChannel<T> channel, @Nullable Player player,
                                 PersistentContainerKey key, int containerSlotIndex) {
        Tag tag = SlotStateServer.readTag(key, player, channel.id(), containerSlotIndex);
        if (tag == null) return channel.defaultValue();
        return SlotStateServer.decode(channel, tag);
    }

    static <T> void writePersistent(SlotStateChannel<T> channel, @Nullable Player player,
                                     PersistentContainerKey key, int containerSlotIndex, T value) {
        Tag encoded = SlotStateServer.encode(channel, value);
        SlotStateServer.writeTag(key, player, channel.id(), containerSlotIndex, encoded);
    }

    // ── Side detection ──────────────────────────────────────────────────

    /**
     * Resolves a viewing player from the slot context if the caller didn't
     * provide one. For {@link Inventory} containers we pull from
     * {@code inv.player}. Callers that have an explicit player pass it in;
     * callers that don't and aren't on a player-scoped container end up with
     * null viewer and fall through to defaults.
     */
    private static @Nullable Player resolvePlayer(Slot slot, @Nullable Player explicit) {
        if (explicit != null) return explicit;
        if (slot.container instanceof Inventory inv) return inv.player;
        return tryClientPlayer();
    }

    private static boolean isClientSide(@Nullable Player viewer) {
        if (viewer != null) return viewer.level().isClientSide();
        // Fall back to thread-name detection when no viewer is available.
        return "Render thread".equals(Thread.currentThread().getName());
    }

    /**
     * Best-effort fetch of the client-side player. Called only when the slot
     * has no player in its container and the caller didn't pass one — happens
     * for BE/entity-backed slots on the client side.
     */
    private static @Nullable Player tryClientPlayer() {
        if (!"Render thread".equals(Thread.currentThread().getName())) return null;
        try {
            return Minecraft.getInstance().player;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Helpers used by packet receivers ────────────────────────────────

    /** Finds a slot in the given menu by container-relative index + container match. */
    public static @Nullable Slot findSlotByContainerIndex(
            net.minecraft.world.inventory.AbstractContainerMenu menu,
            PersistentContainerKey key, int containerSlotIndex) {
        for (Slot s : menu.slots) {
            if (s.getContainerSlot() != containerSlotIndex) continue;
            Container container = s.container;
            Optional<PersistentContainerKey> resolved = SlotStateRegistry.resolve(container);
            if (resolved.isEmpty()) continue;
            if (resolved.get().equals(key)) return s;
        }
        return null;
    }
}
