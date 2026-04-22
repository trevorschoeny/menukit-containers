package com.trevorschoeny.menukit.core.attachment;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import com.mojang.serialization.Codec;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric attachment-type registry for M7. Each consumer-declared
 * {@code StorageAttachment} registers exactly one attachment per owner
 * type with a unique identifier — the library maintains this registry so
 * registrations are idempotent and inspectable.
 *
 * <p>Attachments store {@link ItemContainerContents} (vanilla's codec-backed
 * item-list type) and the Storage surface converts to/from mutable
 * {@link NonNullList} on each read/write. Using ItemContainerContents lets
 * M7 reuse vanilla's codec directly instead of composing a NonNullList codec.
 *
 * <p>Not public API — consumers go through {@code StorageAttachment}'s
 * factories, which call into this registry internally.
 */
public final class StorageAttachments {

    private StorageAttachments() {}

    // Cache attachment types by identifier. Fabric's registry throws on
    // duplicate registration; this cache lets the library return the same
    // type for repeated StorageAttachment declarations with the same id
    // (which is an error elsewhere in a typical mod, but not here — consumers
    // may reload static fields during hot-reload in dev).
    private static final ConcurrentHashMap<Identifier, AttachmentType<ItemContainerContents>> CACHE =
            new ConcurrentHashMap<>();

    // Tracks the per-attachment slot count — needed at drop-on-break time
    // so the library can iterate the attachment's content array correctly.
    private static final ConcurrentHashMap<AttachmentType<ItemContainerContents>, Integer>
            ATTACHMENT_SIZES = new ConcurrentHashMap<>();

    // Block-scoped attachments register here in addition to CACHE. The
    // drop-on-break mixin iterates this list, not CACHE, to skip
    // player-attached / item-attached variants whose lifecycle doesn't
    // involve block destruction. Populated by
    // {@link #registerBlockScopedAttachment(Identifier, int)}.
    private static final java.util.List<AttachmentType<ItemContainerContents>>
            BLOCK_SCOPED_ATTACHMENTS =
                    new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Register (or retrieve) an attachment storing {@link ItemContainerContents}.
     * Persistence is automatic via Fabric's {@code .persistent(Codec)}.
     *
     * <p>Use this variant for non-block-scoped attachments (player-attached,
     * item-attached, ephemeral, custom). For block-scoped attachments, use
     * {@link #registerBlockScopedAttachment(Identifier, int)} instead —
     * same underlying registration, plus the block-scoped registry entry
     * for drop-on-break dispatch.
     *
     * @param id           the attachment's unique identifier (shows up in NBT paths)
     * @param defaultSlots number of slots in the default empty content
     */
    public static AttachmentType<ItemContainerContents> registerContainerAttachment(
            Identifier id, int defaultSlots) {
        AttachmentType<ItemContainerContents> type = CACHE.computeIfAbsent(id, k ->
                AttachmentRegistry.<ItemContainerContents>builder()
                        .persistent(ItemContainerContents.CODEC)
                        .initializer(() -> defaultEmpty(defaultSlots))
                        .buildAndRegister(k));
        ATTACHMENT_SIZES.putIfAbsent(type, defaultSlots);
        return type;
    }

    /**
     * Register a block-scoped attachment. Same as
     * {@link #registerContainerAttachment(Identifier, int)} plus enrollment
     * in the block-scoped registry that the drop-on-break mixin dispatches
     * against. Called by
     * {@link com.trevorschoeny.menukit.core.StorageAttachment#blockScoped(String, String, int)}.
     */
    public static AttachmentType<ItemContainerContents> registerBlockScopedAttachment(
            Identifier id, int defaultSlots) {
        AttachmentType<ItemContainerContents> type =
                registerContainerAttachment(id, defaultSlots);
        if (!BLOCK_SCOPED_ATTACHMENTS.contains(type)) {
            BLOCK_SCOPED_ATTACHMENTS.add(type);
        }
        return type;
    }

    /** Snapshot of all registered block-scoped attachments, for drop-on-break iteration. */
    public static java.util.List<AttachmentType<ItemContainerContents>> blockScopedAttachments() {
        return java.util.List.copyOf(BLOCK_SCOPED_ATTACHMENTS);
    }

    /** Slot count for a registered attachment — used at drop-on-break to size the list. */
    public static int slotCountOf(AttachmentType<ItemContainerContents> type) {
        Integer size = ATTACHMENT_SIZES.get(type);
        return size == null ? 0 : size;
    }

    /** Produces an empty {@link ItemContainerContents} of the given size. */
    public static ItemContainerContents defaultEmpty(int slotCount) {
        NonNullList<ItemStack> empty = NonNullList.withSize(slotCount, ItemStack.EMPTY);
        return ItemContainerContents.fromItems(empty);
    }

    /**
     * Converts {@link ItemContainerContents} to a mutable {@link NonNullList}.
     * Every call allocates a fresh list — callers mutate their copy and write
     * back via {@link #listToContents}.
     */
    public static NonNullList<ItemStack> contentsToList(ItemContainerContents contents,
                                                         int slotCount) {
        NonNullList<ItemStack> list = NonNullList.withSize(slotCount, ItemStack.EMPTY);
        if (contents != null) contents.copyInto(list);
        return list;
    }

    /** Converts a {@link NonNullList} to an {@link ItemContainerContents}. */
    public static ItemContainerContents listToContents(NonNullList<ItemStack> list) {
        return ItemContainerContents.fromItems(list);
    }
}
