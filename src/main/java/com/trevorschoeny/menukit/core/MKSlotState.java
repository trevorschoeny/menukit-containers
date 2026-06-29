package com.trevorschoeny.menukit.core;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.mixin.CompoundContainerAccessor;
import com.trevorschoeny.menukit.window.Address;
import com.trevorschoeny.menukit.window.CreatedSlotResolver;
import com.trevorschoeny.menukit.network.SlotStateSnapshotS2CPayload;
import com.trevorschoeny.menukit.state.ResolvedSlot;
import com.trevorschoeny.menukit.state.SlotStateClientCache;
import com.trevorschoeny.menukit.state.SlotStateRegistry;
import com.trevorschoeny.menukit.state.SlotStateServer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
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
    public static <T> SlotStateChannel<T> register(
            Identifier id, Codec<T> codec,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec, T defaultValue) {
        return register(id, codec, streamCodec, defaultValue, SlotStateChannel.Visibility.PRIVATE);
    }

    /**
     * Registers a channel with explicit visibility (§0049). PRIVATE is the
     * shipped per-player model; SHARED gives one player-agnostic value per slot,
     * synced to and writable by every viewer — for cross-player features like
     * Inventory Plus's Container Locks. SHARED is only meaningful on multi-player,
     * fixed-slot containers (placed shulker/chest/barrel); see
     * {@link SlotStateChannel.Visibility}.
     */
    @SuppressWarnings("unchecked")
    public static <T> SlotStateChannel<T> register(
            Identifier id, Codec<T> codec,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec, T defaultValue,
            SlotStateChannel.Visibility visibility) {
        SlotStateChannel<T> existing = SlotStateRegistry.getChannelTyped(id);
        if (existing != null) {
            LOGGER.warn("[SlotState] channel {} already registered — returning existing instance", id);
            return existing;
        }
        SlotStateChannel<T> channel = new SlotStateChannel<>(id, codec, streamCodec, defaultValue, visibility);
        SlotStateRegistry.registerChannel(channel);
        LOGGER.info("[SlotState] registered channel {} ({})", id, visibility);
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

    // ── Client capability (§0050) ───────────────────────────────────────

    /**
     * Reports whether {@code player}'s client can receive slot-state sync — i.e.
     * has MenuKit-Containers' slot-state S2C channel registered (a vanilla or
     * otherwise non-MenuKit client returns {@code false}). Backed by Fabric's
     * channel handshake ({@code ServerPlayNetworking.canSend}) for the slot-state
     * snapshot channel.
     *
     * <p><b>Reports only — never acts.</b> Whether a non-capable client is bound
     * by, or bypasses, a consumer's per-slot feature (e.g. Inventory Plus's
     * Container Locks letting non-modded players bypass a lock they cannot see —
     * Trev's product call) is the <em>consumer's</em> enforcement policy. The
     * library exposes the capability; it does not skip or bind players itself
     * (§0019 / §0045 — the library off enforcement behavior).
     */
    public static boolean isSlotStateCapable(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, SlotStateSnapshotS2CPayload.TYPE);
    }

    // ── Internal read/write (called from SlotStateChannel) ──────────────

    static <T> T read(SlotStateChannel<T> channel, Slot slot, @Nullable Player player) {
        Player viewer = resolvePlayer(slot, player);
        if (isClientSide(viewer)) {
            return SlotStateClientCache.read(channel, slot);
        }
        // §0050: slot-aware resolution — a composite container (double chest)
        // resolves this slot to its owning half's key + the index local to that
        // half; single-owner containers are the identity case (local == global).
        Optional<ResolvedSlot> resolved = SlotStateRegistry.resolve(slot.container, slot.getContainerSlot());
        if (resolved.isEmpty()) return channel.defaultValue();
        Tag tag = SlotStateServer.readTag(
                resolved.get().key(), viewer, channel.id(), resolved.get().localSlotIndex());
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
        // §0050: store under the owning (half-)key at the owner-local index. The
        // GLOBAL index still drives sync — the client backs the menu with a flat
        // container and indexes it globally; the half-split is server-only.
        int globalIndex = slot.getContainerSlot();
        Optional<ResolvedSlot> resolved = SlotStateRegistry.resolve(slot.container, globalIndex);
        if (resolved.isEmpty()) return;
        Tag encoded = SlotStateServer.encode(channel, value);
        boolean ok = SlotStateServer.writeTag(
                resolved.get().key(), viewer, channel.id(), resolved.get().localSlotIndex(), encoded);
        if (!ok) return;
        if (viewer instanceof net.minecraft.server.level.ServerPlayer sp) {
            if (channel.visibility() == SlotStateChannel.Visibility.SHARED) {
                // §0049/§0050: a shared write reaches every current viewer of this
                // slot — matched by persistent owner key + local index, which spans
                // co-viewers of a double chest (each holds a distinct
                // CompoundContainer wrapper over the same two halves).
                com.trevorschoeny.menukit.state.SlotStateHooks.broadcastToViewers(
                        sp, resolved.get().key(), resolved.get().localSlotIndex(),
                        channel.id(), encoded, true);
            } else {
                // Private write: echo back to the writing player so their cache stays in sync.
                // Global index — the writer's client indexes its flat container globally.
                com.trevorschoeny.menukit.network.SlotStateUpdateS2CPayload.sendTo(
                        sp, channel.id(), globalIndex, encoded);
            }
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

    // ── Address-keyed read/write (THE ONE WINDOW twin) ──────────────────

    /**
     * Reads this channel's value at the slot named by {@code address}, resolving
     * the address to a live slot through the same created-slot resolver THE ONE
     * WINDOW uses, then delegating to the {@link Slot}-keyed read. Returns the
     * channel default when the address resolves to no live slot in the (resolved)
     * viewer's open menu.
     *
     * <p><b>Resolution boundary.</b> An {@link Address} names an identity, not a
     * live slot — turning it into one requires the menu it currently appears in.
     * This resolves that menu from the viewing player's {@code containerMenu}
     * ({@code player} when given, else the client player), so an Address read/write
     * is meaningful exactly where the addressed slot is open. Outside an open menu
     * holding that slot (e.g. a pure server-automation context with no viewer),
     * there is no live slot to resolve and this returns the default — use the
     * {@code (Container, int)} / {@code (PersistentContainerKey, int)} overloads for
     * menu-free server automation.
     */
    static <T> T readByAddress(SlotStateChannel<T> channel, @Nullable Player player, Address address) {
        Slot slot = resolveSlotByAddress(player, address);
        if (slot == null) return channel.defaultValue();
        return read(channel, slot, player);
    }

    /**
     * Writes this channel's value at the slot named by {@code address}. Resolves
     * the address to a live slot exactly as {@link #readByAddress}; a no-op when
     * the address resolves to no live slot in the viewer's open menu (see that
     * method's resolution boundary).
     */
    static <T> void writeByAddress(SlotStateChannel<T> channel, @Nullable Player player,
                                   Address address, T value) {
        Slot slot = resolveSlotByAddress(player, address);
        if (slot == null) return;
        write(channel, slot, player, value);
    }

    /**
     * The live in-menu {@link Slot} an {@link Address} names in the viewing
     * player's open menu, or {@code null} if none. The viewer is {@code player}
     * when given, else the client player (render thread); the menu is that player's
     * {@code containerMenu}.
     *
     * <ul>
     *   <li><b>Created slot</b> — resolved through {@link CreatedSlotAdapter}, the
     *       engine's own {@code Address -> live MKCSlot} resolver (the in-menu slot,
     *       so a creative wrapper still routes correctly).</li>
     *   <li><b>Vanilla slot</b> — found by scanning the menu for the slot whose
     *       minted address matches (the same {@link SlotAddresses#of} encoding the
     *       engine mints with), so a vanilla-addressed slot resolves too.</li>
     *   <li><b>Panel / element</b> — not a slot; no live slot to resolve.</li>
     * </ul>
     */
    private static @Nullable Slot resolveSlotByAddress(@Nullable Player player, Address address) {
        Player viewer = player != null ? player : tryClientPlayer();
        if (viewer == null) return null;
        net.minecraft.world.inventory.AbstractContainerMenu menu = viewer.containerMenu;
        if (menu == null) return null;
        switch (address.kind()) {
            case CREATED_SLOT -> {
                CreatedSlotResolver.CreatedResolution r =
                        CreatedSlotAdapter.INSTANCE.resolve(menu, address);
                return r != null ? r.slot() : null;
            }
            case VANILLA_SLOT -> {
                for (Slot s : menu.slots) {
                    if (SlotAddresses.of(menu, s).equals(address)) return s;
                }
                return null;
            }
            default -> { // PANEL / PANEL_ELEMENT — not a slot
                return null;
            }
        }
    }

    // ── Menu-free shared read (§0050) ───────────────────────────────────

    /**
     * Menu-free server-side read of the SHARED value at {@code (container,
     * containerSlotIndex)} — no {@link Slot}, no open menu, no viewer. Serves
     * automation (hopper / dropper / dispenser) that touches a placed container
     * by index. The server is derived from the container's block entity (a
     * placed container is server-side here). Returns the channel default
     * off-server, for an unresolvable container, or for a PRIVATE channel — which
     * has no viewer on this path, so it resolves nothing meaningful; this is the
     * shared-read primitive (§0050). Composes with composite resolution: a double
     * chest resolves to its owning half.
     */
    static <T> T readShared(SlotStateChannel<T> channel, Container container, int containerSlotIndex) {
        MinecraftServer server = serverOf(container);
        if (server == null) return channel.defaultValue();
        Optional<ResolvedSlot> resolved = SlotStateRegistry.resolve(container, containerSlotIndex);
        if (resolved.isEmpty()) return channel.defaultValue();
        Tag tag = SlotStateServer.readTag(
                resolved.get().key(), null, server, channel.id(), resolved.get().localSlotIndex());
        if (tag == null) return channel.defaultValue();
        return SlotStateServer.decode(channel, tag);
    }

    /**
     * Derives the server from a placed container for the menu-free read. The
     * container is a server-side block entity (single chest) or a composite over
     * block-entity halves (double chest); either way its level yields the server.
     * Null off-server or for a non-block-entity container.
     */
    private static @Nullable MinecraftServer serverOf(Container container) {
        BlockEntity be = blockEntityOf(container);
        if (be != null && be.getLevel() instanceof ServerLevel level) {
            return level.getServer();
        }
        return null;
    }

    /** The backing block entity of a placed container — itself, or a composite's first half. */
    private static @Nullable BlockEntity blockEntityOf(Container container) {
        if (container instanceof BlockEntity be) return be;
        if (container instanceof CompoundContainer compound) {
            // Both halves share a level; the first half is enough to reach the server.
            Container first = ((CompoundContainerAccessor) (Object) compound).mk$getContainer1();
            if (first instanceof BlockEntity be) return be;
        }
        return null;
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
