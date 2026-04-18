package com.trevorschoeny.menukit.state;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.core.PersistentContainerKey;
import com.trevorschoeny.menukit.core.SlotStateChannel;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Server-side persistence facade. Resolves a {@link PersistentContainerKey}
 * + viewing player to the correct attachment owner (Player / BlockEntity /
 * Entity) and reads/writes the library's {@link SlotStateBag}.
 *
 * <p>v1 coverage: PlayerInventory, EnderChest, BlockEntityKey. EntityKey and
 * Modded variants stub to no-op (reads return null, writes are silent).
 * F1 + F2 exercise the PlayerInventory + BlockEntity paths.
 */
public final class SlotStateServer {

    private SlotStateServer() {}

    // ── Codec helpers ───────────────────────────────────────────────────

    public static <T> Tag encode(SlotStateChannel<T> channel, T value) {
        Codec<T> codec = channel.codec();
        return codec.encodeStart(NbtOps.INSTANCE, value)
                    .getOrThrow(err -> new IllegalStateException(
                            "Codec failed to encode value for channel " + channel.id() + ": " + err));
    }

    public static <T> T decode(SlotStateChannel<T> channel, Tag tag) {
        Codec<T> codec = channel.codec();
        return codec.parse(NbtOps.INSTANCE, tag)
                    .getOrThrow(err -> new IllegalStateException(
                            "Codec failed to parse Tag for channel " + channel.id() + ": " + err));
    }

    // ── Core read/write ─────────────────────────────────────────────────

    /**
     * Reads the stored Tag for a channel + key + slot. Returns {@code null}
     * if no value is stored, the key can't be resolved, or the owner isn't
     * reachable on the server (player offline, chunk unloaded, etc.).
     */
    public static @Nullable Tag readTag(PersistentContainerKey key, @Nullable Player viewer,
                                         Identifier channelId, int containerSlotIndex) {
        SlotStateBag bag = resolveBagForRead(key, viewer);
        if (bag == null) return null;
        return bag.read(channelId, containerSlotIndex);
    }

    /**
     * Writes a Tag value for a channel + key + slot. Returns true if persisted
     * successfully, false if the owner couldn't be resolved.
     */
    public static boolean writeTag(PersistentContainerKey key, @Nullable Player viewer,
                                    Identifier channelId, int containerSlotIndex, Tag value) {
        SlotStateBag bag = resolveBagForWrite(key, viewer);
        if (bag == null) return false;
        bag.write(channelId, containerSlotIndex, value);
        return true;
    }

    // ── Owner resolution ────────────────────────────────────────────────

    private static @Nullable SlotStateBag resolveBagForRead(PersistentContainerKey key,
                                                             @Nullable Player viewer) {
        return resolveBag(key, viewer, false);
    }

    private static @Nullable SlotStateBag resolveBagForWrite(PersistentContainerKey key,
                                                              @Nullable Player viewer) {
        return resolveBag(key, viewer, true);
    }

    private static @Nullable SlotStateBag resolveBag(PersistentContainerKey key,
                                                      @Nullable Player viewer, boolean forWrite) {
        MinecraftServer server = null;
        if (viewer instanceof ServerPlayer sp) {
            Level lvl = sp.level();
            if (lvl instanceof ServerLevel sl) server = sl.getServer();
        }

        if (key instanceof PersistentContainerKey.PlayerInventory pi) {
            ServerPlayer target = findOnlinePlayer(server, pi.playerId());
            if (target == null) return null;
            return target.getAttachedOrCreate(SlotStateAttachments.PLAYER_INVENTORY);
        }

        if (key instanceof PersistentContainerKey.EnderChest ec) {
            ServerPlayer target = findOnlinePlayer(server, ec.playerId());
            if (target == null) return null;
            return target.getAttachedOrCreate(SlotStateAttachments.ENDER_CHEST);
        }

        if (key instanceof PersistentContainerKey.BlockEntityKey bek) {
            if (server == null) return null;
            ServerLevel level = server.getLevel(bek.dimension());
            if (level == null) return null;
            BlockEntity be = level.getBlockEntity(bek.pos());
            if (be == null) return null;
            UUID viewerId = viewer != null ? viewer.getUUID() : null;
            if (viewerId == null) return null;
            PerPlayerSlotStateBag perPlayer =
                    be.getAttachedOrCreate(SlotStateAttachments.BLOCK_ENTITY);
            // Mark the BE dirty so the attachment gets saved with the chunk.
            be.setChanged();
            return perPlayer.getOrCreate(viewerId);
        }

        if (key instanceof PersistentContainerKey.EntityKey) {
            // v1: stub. Entity-backed persistence is deferred — resolving
            // EntityKey requires either a scan across levels or stashing the
            // entity UUID→level mapping at open time. See M1 design doc §4.2
            // for the future-work note.
            return null;
        }

        if (key instanceof PersistentContainerKey.Modded) {
            // v1: stub. Modded resolver + attachment-binding paths aren't
            // wired through yet. Consumers that register a modded resolver
            // get the key constructed correctly; persistence for that key
            // falls through here until the binding path lands.
            return null;
        }

        return null;
    }

    private static @Nullable ServerPlayer findOnlinePlayer(@Nullable MinecraftServer server, UUID uuid) {
        if (server == null) return null;
        return server.getPlayerList().getPlayer(uuid);
    }
}
