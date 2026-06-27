package com.trevorschoeny.menukit.state;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.core.KeyedStorages;
import com.trevorschoeny.menukit.core.PersistentContainerKey;
import com.trevorschoeny.menukit.core.SlotStateChannel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jspecify.annotations.Nullable;

import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-side persistence facade. Resolves a {@link PersistentContainerKey}
 * + viewing player to the correct attachment owner (Player / BlockEntity /
 * Entity) and reads/writes the library's {@link SlotStateBag}.
 *
 * <p>v1 coverage: PlayerInventory, EnderChest, BlockEntityKey. EntityKey and
 * Modded variants stub to no-op (reads return null, writes are silent).
 * F1 + F2 exercise the PlayerInventory + BlockEntity paths.
 */
@ApiStatus.Internal
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
        SlotStateBag bag = resolveBagForRead(key, viewer, isShared(channelId));
        if (bag == null) return null;
        return bag.read(channelId, containerSlotIndex);
    }

    /**
     * §0050 — menu-free read overload. {@code explicitServer} supplies the
     * server when there is no viewer to derive it from (automation reading a
     * placed container by index, with no menu). The shipped 4-arg form is
     * unchanged (server derived from the viewer). Read-only by design — there is
     * no menu-free write path (§0050: writes stay owner/menu-driven).
     */
    public static @Nullable Tag readTag(PersistentContainerKey key, @Nullable Player viewer,
                                         @Nullable MinecraftServer explicitServer,
                                         Identifier channelId, int containerSlotIndex) {
        SlotStateBag bag = resolveBag(key, viewer, false, isShared(channelId), explicitServer);
        if (bag == null) return null;
        return bag.read(channelId, containerSlotIndex);
    }

    /**
     * Writes a Tag value for a channel + key + slot. Returns true if persisted
     * successfully, false if the owner couldn't be resolved.
     */
    public static boolean writeTag(PersistentContainerKey key, @Nullable Player viewer,
                                    Identifier channelId, int containerSlotIndex, Tag value) {
        SlotStateBag bag = resolveBagForWrite(key, viewer, isShared(channelId));
        if (bag == null) return false;
        bag.write(channelId, containerSlotIndex, value);
        return true;
    }

    /** §0049 — true if the channel with this id is registered SHARED. */
    private static boolean isShared(Identifier channelId) {
        SlotStateChannel<?> ch = SlotStateRegistry.getChannel(channelId);
        return ch != null && ch.visibility() == SlotStateChannel.Visibility.SHARED;
    }

    // ── Owner resolution ────────────────────────────────────────────────

    private static @Nullable SlotStateBag resolveBagForRead(PersistentContainerKey key,
                                                             @Nullable Player viewer, boolean shared) {
        return resolveBag(key, viewer, false, shared);
    }

    private static @Nullable SlotStateBag resolveBagForWrite(PersistentContainerKey key,
                                                              @Nullable Player viewer, boolean shared) {
        return resolveBag(key, viewer, true, shared);
    }

    private static @Nullable SlotStateBag resolveBag(PersistentContainerKey key,
                                                      @Nullable Player viewer, boolean forWrite, boolean shared) {
        return resolveBag(key, viewer, forWrite, shared, null);
    }

    private static @Nullable SlotStateBag resolveBag(PersistentContainerKey key,
                                                      @Nullable Player viewer, boolean forWrite, boolean shared,
                                                      @Nullable MinecraftServer explicitServer) {
        // §0050: prefer an explicit server (menu-free reads carry no viewer to
        // derive it from); otherwise derive it from the viewer, exactly as the
        // shipped path does (explicitServer null → byte-for-byte unchanged).
        MinecraftServer server = explicitServer;
        if (server == null && viewer instanceof ServerPlayer sp) {
            Level lvl = sp.level();
            if (lvl instanceof ServerLevel sl) server = sl.getServer();
        }

        // Persistence note for all player/BE branches below: writes go through a
        // FRESH value + setAttached, not in-place mutation of the existing
        // attachment instance — Fabric only tracks attachments updated to a new
        // value, so bare in-place mutation saves to disk but fails to round-trip
        // on load (docs.fabricmc.net/develop/data-attachments). And player-scoped
        // keys resolve the owner via resolvePlayer (prefers the held viewer),
        // because at connection JOIN the player isn't in the server player-list
        // yet and a UUID lookup returns null. Both first surfaced in §0045.

        if (key instanceof PersistentContainerKey.PlayerInventory pi) {
            ServerPlayer target = resolvePlayer(server, viewer, pi.playerId());
            if (target == null) return null;
            SlotStateBag bag = target.getAttachedOrCreate(SlotStateAttachments.PLAYER_INVENTORY);
            if (forWrite) {
                bag = new SlotStateBag(bag.backing().copy());
                target.setAttached(SlotStateAttachments.PLAYER_INVENTORY, bag);
            }
            return bag;
        }

        if (key instanceof PersistentContainerKey.EnderChest ec) {
            ServerPlayer target = resolvePlayer(server, viewer, ec.playerId());
            if (target == null) return null;
            SlotStateBag bag = target.getAttachedOrCreate(SlotStateAttachments.ENDER_CHEST);
            if (forWrite) {
                bag = new SlotStateBag(bag.backing().copy());
                target.setAttached(SlotStateAttachments.ENDER_CHEST, bag);
            }
            return bag;
        }

        if (key instanceof PersistentContainerKey.BlockEntityKey bek) {
            if (server == null) return null;
            ServerLevel level = server.getLevel(bek.dimension());
            if (level == null) return null;
            BlockEntity be = level.getBlockEntity(bek.pos());
            if (be == null) return null;
            // PRIVATE needs a known viewer (its UUID keys the bag); SHARED is
            // player-agnostic (§0049). Resolve the viewer UUID up front for the
            // private path so a missing viewer short-circuits BEFORE we touch
            // the attachment — keeping the shipped private behavior unchanged.
            UUID viewerId = null;
            if (!shared) {
                viewerId = viewer != null ? viewer.getUUID() : null;
                if (viewerId == null) return null;
            }
            PerPlayerSlotStateBag perPlayer =
                    be.getAttachedOrCreate(SlotStateAttachments.BLOCK_ENTITY);
            if (forWrite) {
                perPlayer = new PerPlayerSlotStateBag(perPlayer.backing().copy());
                be.setAttached(SlotStateAttachments.BLOCK_ENTITY, perPlayer);
                // Mark the BE dirty so the attachment gets saved with the chunk.
                be.setChanged();
            }
            // §0049: SHARED resolves the player-agnostic bag (drop the UUID
            // layer); PRIVATE keeps the per-viewer bag (the shipped model).
            return shared ? perPlayer.getOrCreateShared() : perPlayer.getOrCreate(viewerId);
        }

        if (key instanceof PersistentContainerKey.EntityKey ek) {
            if (server == null) return null;
            // EntityKey carries only the entity UUID (no dimension) — minecarts
            // cross dimensions, so we resolve across all of them.
            // getEntityInAnyDimension searches every loaded level, so a single
            // level reference (overworld, always present) suffices.
            Entity entity = server.overworld().getEntityInAnyDimension(ek.entityId());
            if (entity == null) return null;
            // Mirror the BlockEntityKey path: PRIVATE needs a known viewer (its
            // UUID keys the bag); SHARED is player-agnostic (§0049).
            UUID viewerId = null;
            if (!shared) {
                viewerId = viewer != null ? viewer.getUUID() : null;
                if (viewerId == null) return null;
            }
            PerPlayerSlotStateBag perPlayer =
                    entity.getAttachedOrCreate(SlotStateAttachments.ENTITY);
            if (forWrite) {
                // FRESH value + setAttached so Fabric round-trips the write on
                // load (same rule as every player/BE branch above). Entities
                // persist attachments with their own save cycle — there is no
                // BlockEntity-style setChanged() to call.
                perPlayer = new PerPlayerSlotStateBag(perPlayer.backing().copy());
                entity.setAttached(SlotStateAttachments.ENTITY, perPlayer);
            }
            return shared ? perPlayer.getOrCreateShared() : perPlayer.getOrCreate(viewerId);
        }

        if (key instanceof PersistentContainerKey.Modded modded) {
            // §0045: player-scoped registered slots (IP Pockets / Equipment Slots).
            // Keys produced by KeyedStorages.player(...) encode the owning
            // player in the payload; resolve to a resolver-id-namespaced bag on
            // that player. Consumer-rolled Modded keys without the library scope
            // marker still fall through to null (the general modded-resolver
            // path remains future work — see §0045 review trigger).
            CompoundTag payload = modded.payload();
            String scope = payload.getStringOr(KeyedStorages.SCOPE_KEY, "");
            String ownerStr = payload.getStringOr(KeyedStorages.OWNER_KEY, "");
            if (!KeyedStorages.SCOPE_PLAYER.equals(scope)) return null;
            if (ownerStr.isEmpty()) return null;
            UUID ownerId;
            try {
                ownerId = UUID.fromString(ownerStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
            ServerPlayer target = resolvePlayer(server, viewer, ownerId);
            if (target == null) return null;
            NamespacedSlotStateBag bags =
                    target.getAttachedOrCreate(SlotStateAttachments.MODDED_PLAYER);
            if (forWrite) {
                // Match the M7 content pattern (which persists+loads correctly):
                // replace the attached value with a FRESH copy and re-register
                // it via setAttached. Fabric only tracks attachments updated to
                // a new value — bare in-place mutation of the existing instance
                // (or setAttached of the same reference) is not seen as a change
                // and fails to round-trip on load
                // (docs.fabricmc.net/develop/data-attachments). The subsequent
                // write lands on this now-attached fresh copy.
                bags = new NamespacedSlotStateBag(bags.backing().copy());
                target.setAttached(SlotStateAttachments.MODDED_PLAYER, bags);
            }
            return bags.getOrCreate(modded.resolverId());
        }

        return null;
    }

    /**
     * Resolves the owning player for a player-scoped key. Prefers the viewer we
     * already hold when its UUID matches — at connection JOIN (the player-join
     * snapshot read) the player isn't in the server player-list yet, so a bare
     * UUID lookup returns null and the read silently fails (§0045).
     */
    private static @Nullable ServerPlayer resolvePlayer(@Nullable MinecraftServer server,
                                                        @Nullable Player viewer, UUID ownerId) {
        if (viewer instanceof ServerPlayer sp && sp.getUUID().equals(ownerId)) return sp;
        return findOnlinePlayer(server, ownerId);
    }

    private static @Nullable ServerPlayer findOnlinePlayer(@Nullable MinecraftServer server, UUID uuid) {
        if (server == null) return null;
        return server.getPlayerList().getPlayer(uuid);
    }
}
