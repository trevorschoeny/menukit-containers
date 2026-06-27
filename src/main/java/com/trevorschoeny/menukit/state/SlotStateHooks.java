package com.trevorschoeny.menukit.state;

import com.trevorschoeny.menukit.core.MKSlotState;
import com.trevorschoeny.menukit.core.PersistentContainerKey;
import com.trevorschoeny.menukit.core.SlotStateChannel;
import com.trevorschoeny.menukit.network.SlotStateSnapshotS2CPayload;
import com.trevorschoeny.menukit.network.SlotStateUpdateC2SPayload;
import com.trevorschoeny.menukit.network.SlotStateUpdateS2CPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/**
 * Wiring for M1 network payloads + player-join snapshot delivery.
 * Registration happens in {@code MenuKit.initServer} / {@code initClient};
 * per-call flows are static helpers so mixins and event callbacks can invoke
 * them without instance management.
 */
@ApiStatus.Internal
public final class SlotStateHooks {

    private SlotStateHooks() {}

    // ── Registration ────────────────────────────────────────────────────

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(
                SlotStateSnapshotS2CPayload.TYPE, SlotStateSnapshotS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(
                SlotStateUpdateS2CPayload.TYPE, SlotStateUpdateS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(
                SlotStateUpdateC2SPayload.TYPE, SlotStateUpdateC2SPayload.STREAM_CODEC);
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(SlotStateUpdateC2SPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() ->
                        handleUpdateC2S(payload, ctx.player())));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> sendPlayerJoinSnapshot(handler.getPlayer())));
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(SlotStateSnapshotS2CPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> handleSnapshot(payload)));

        ClientPlayNetworking.registerGlobalReceiver(SlotStateUpdateS2CPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> handleUpdateS2C(payload)));
    }

    // ── Server-side snapshot builders ───────────────────────────────────

    /**
     * Builds and sends a snapshot for every supported slot in the player's
     * currently-opened menu. Called from the {@code ServerPlayer.openMenu}
     * mixin after the new menu is assigned.
     */
    public static void sendSnapshotForOpenedMenu(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return;
        List<SlotStateSnapshotS2CPayload.Entry> entries = buildEntries(menu, player, null);
        if (entries.isEmpty()) return;
        ServerPlayNetworking.send(player,
                new SlotStateSnapshotS2CPayload(menu.containerId, entries));
    }

    /**
     * Builds and sends a snapshot for player-scoped channels on the player's
     * {@code inventoryMenu}. Fires on connection join and after respawn —
     * both paths construct {@code InventoryMenu} without routing through
     * {@code openMenu}, so the menu-open hook misses them.
     */
    public static void sendPlayerJoinSnapshot(ServerPlayer player) {
        AbstractContainerMenu menu = player.inventoryMenu;
        if (menu == null) return;
        List<SlotStateSnapshotS2CPayload.Entry> entries = buildEntries(menu, player,
                key -> key instanceof PersistentContainerKey.PlayerInventory
                    || key instanceof PersistentContainerKey.EnderChest
                    // §0045: player-scoped registered slots (Pockets / Equipment)
                    // live on the inventory menu and resolve to Modded keys;
                    // include them so their metadata reaches the client on
                    // join/respawn (the inventory menu never routes through
                    // openMenu, so this is their only snapshot path).
                    || key instanceof PersistentContainerKey.Modded);
        if (entries.isEmpty()) return;
        ServerPlayNetworking.send(player,
                new SlotStateSnapshotS2CPayload(menu.containerId, entries));
    }

    /**
     * Iterates the menu's slots, resolves each to a persistent key, and
     * bundles every non-default channel value. {@code keyFilter} if non-null
     * restricts which key variants contribute — used by the player-join path
     * to skip BE/entity-scoped channels.
     */
    private static List<SlotStateSnapshotS2CPayload.Entry> buildEntries(
            AbstractContainerMenu menu, ServerPlayer player,
            java.util.function.Predicate<PersistentContainerKey> keyFilter) {
        List<SlotStateSnapshotS2CPayload.Entry> entries = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            // §0050: slot-aware resolution — a composite (double chest) splits
            // each slot to its owning half's key + local index; single-owner is
            // the identity case.
            Optional<ResolvedSlot> resolved =
                    SlotStateRegistry.resolve(slot.container, slot.getContainerSlot());
            if (resolved.isEmpty()) continue;
            PersistentContainerKey key = resolved.get().key();
            if (keyFilter != null && !keyFilter.test(key)) continue;
            int localSlotIndex = resolved.get().localSlotIndex();
            for (SlotStateChannel<?> channel : SlotStateRegistry.allChannels()) {
                Tag tag = SlotStateServer.readTag(key, player, channel.id(), localSlotIndex);
                if (tag == null) continue;
                // Entry carries the MENU slot index i — the client maps it to its
                // own menu slot and caches by the flat-container global index.
                entries.add(new SlotStateSnapshotS2CPayload.Entry(i, channel.id(), tag));
            }
        }
        return entries;
    }

    // ── Server receive: client write ────────────────────────────────────

    private static void handleUpdateC2S(SlotStateUpdateC2SPayload payload, ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return;
        int idx = payload.menuSlotIndex();
        if (idx < 0 || idx >= menu.slots.size()) return;
        Slot slot = menu.slots.get(idx);
        // §0050: slot-aware resolution stores under the owning (half-)key at the
        // owner-local index. A double chest splits here; single-owner is identity.
        Optional<ResolvedSlot> resolved =
                SlotStateRegistry.resolve(slot.container, slot.getContainerSlot());
        if (resolved.isEmpty()) return;
        SlotStateServer.writeTag(resolved.get().key(), player, payload.channelId(),
                resolved.get().localSlotIndex(), payload.encodedValue());
        // §0049: SHARED channels broadcast to every OTHER viewer (the writer
        // already applied the value optimistically). PRIVATE stays no-echo.
        SlotStateChannel<?> ch = SlotStateRegistry.getChannel(payload.channelId());
        if (ch != null && ch.visibility() == SlotStateChannel.Visibility.SHARED) {
            broadcastToViewers(player, resolved.get().key(), resolved.get().localSlotIndex(),
                    payload.channelId(), payload.encodedValue(), false);
        }
    }

    // ── Shared broadcast (§0049) ────────────────────────────────────────

    /**
     * Sends a slot-state update to every player currently viewing the slot
     * identified by {@code key} + {@code localSlotIndex}. Used for SHARED
     * channels so a write by one viewer reaches the others live.
     * {@code includeOrigin} controls whether {@code origin} (the writer) also
     * receives it — false when the writer already applied the value
     * optimistically (client-initiated), true for server-initiated writes.
     *
     * <p>§0050: viewers are matched by <em>persistent slot identity</em>
     * (resolved owner key + local index), not by container-instance reference.
     * This spans co-viewers of a double chest — each holds a distinct
     * {@code CompoundContainer} wrapper over the same two block-entity halves, so
     * a reference match would miss them — while staying exact for single
     * containers (every viewer resolves the same block entity to the same key).
     * Each matched viewer is sent <em>their own</em> menu's global slot index for
     * that physical slot. The server is derived from {@code origin}'s level.
     */
    public static void broadcastToViewers(ServerPlayer origin, PersistentContainerKey key,
            int localSlotIndex, Identifier channelId, Tag encoded, boolean includeOrigin) {
        if (origin == null || !(origin.level() instanceof ServerLevel level)) return;
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (!includeOrigin && p == origin) continue;
            AbstractContainerMenu menu = p.containerMenu;
            if (menu == null) continue;
            for (Slot s : menu.slots) {
                Optional<ResolvedSlot> rs =
                        SlotStateRegistry.resolve(s.container, s.getContainerSlot());
                if (rs.isPresent() && rs.get().key().equals(key)
                        && rs.get().localSlotIndex() == localSlotIndex) {
                    // This viewer's own flat-container global index for the slot.
                    SlotStateUpdateS2CPayload.sendTo(p, channelId, s.getContainerSlot(), encoded);
                    break;
                }
            }
        }
    }

    // ── Client receive: snapshot + update ───────────────────────────────

    private static void handleSnapshot(SlotStateSnapshotS2CPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null) return;
        if (menu.containerId != payload.menuId()) return;
        for (SlotStateSnapshotS2CPayload.Entry entry : payload.entries()) {
            if (entry.menuSlotIndex() < 0 || entry.menuSlotIndex() >= menu.slots.size()) continue;
            Slot slot = menu.slots.get(entry.menuSlotIndex());
            Optional<SlotStateChannel<?>> chOpt = MKSlotState.getChannel(entry.channelId());
            if (chOpt.isEmpty()) {
                SlotStateClientCache.writeRaw(entry.channelId(), slot.container,
                        slot.getContainerSlot(), entry.value());
                continue;
            }
            Object decoded = SlotStateServer.decode(chOpt.get(), entry.value());
            SlotStateClientCache.writeDecoded(entry.channelId(), slot.container,
                    slot.getContainerSlot(), decoded);
        }
    }

    private static void handleUpdateS2C(SlotStateUpdateS2CPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null) return;
        Identifier channelId = payload.channelId();
        int containerSlotIndex = payload.containerSlotIndex();
        // Scan the current menu's slots; update every match.
        for (Slot slot : menu.slots) {
            if (slot.getContainerSlot() != containerSlotIndex) continue;
            Optional<SlotStateChannel<?>> chOpt = MKSlotState.getChannel(channelId);
            if (chOpt.isEmpty()) {
                SlotStateClientCache.writeRaw(channelId, slot.container, containerSlotIndex, payload.encodedValue());
                continue;
            }
            Object decoded = SlotStateServer.decode(chOpt.get(), payload.encodedValue());
            SlotStateClientCache.writeDecoded(channelId, slot.container, containerSlotIndex, decoded);
        }
    }
}
