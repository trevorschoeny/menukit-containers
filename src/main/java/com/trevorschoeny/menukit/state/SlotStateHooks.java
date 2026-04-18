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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wiring for M1 network payloads + player-join snapshot delivery.
 * Registration happens in {@code MenuKit.initServer} / {@code initClient};
 * per-call flows are static helpers so mixins and event callbacks can invoke
 * them without instance management.
 */
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
                    || key instanceof PersistentContainerKey.EnderChest);
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
            Optional<PersistentContainerKey> keyOpt = SlotStateRegistry.resolve(slot.container);
            if (keyOpt.isEmpty()) continue;
            PersistentContainerKey key = keyOpt.get();
            if (keyFilter != null && !keyFilter.test(key)) continue;
            int containerSlotIndex = slot.getContainerSlot();
            for (SlotStateChannel<?> channel : SlotStateRegistry.allChannels()) {
                Tag tag = SlotStateServer.readTag(key, player, channel.id(), containerSlotIndex);
                if (tag == null) continue;
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
        Optional<PersistentContainerKey> keyOpt = SlotStateRegistry.resolve(slot.container);
        if (keyOpt.isEmpty()) return;
        int containerSlotIndex = slot.getContainerSlot();
        SlotStateServer.writeTag(keyOpt.get(), player, payload.channelId(),
                containerSlotIndex, payload.encodedValue());
        // v1: no echo broadcast. Client updated optimistically before sending.
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
