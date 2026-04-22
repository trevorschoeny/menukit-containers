package com.trevorschoeny.menukit.core.attachment;

import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Drops all block-scoped M7 attachment contents at a given BlockEntity
 * position before the BE is destroyed. Called from
 * {@code com.trevorschoeny.menukit.mixin.M7BlockDropMixin} on block
 * removal, gated on "actually removed" (not just state change).
 *
 * <p>Fulfills M7's vanilla-container drop-on-break contract (see
 * {@code Mechanisms/M7_STORAGE_ATTACHMENT.md} §4.6). Consumers who want
 * silent-loss instead of drop-on-break opt out by declaring their
 * attachment via {@code StorageAttachment.custom(...)} rather than
 * {@code blockScoped(...)} — the custom path bypasses this registry.
 */
public final class BlockScopedDropHandler {

    private BlockScopedDropHandler() {}

    /**
     * For every block-scoped attachment registered on this BE, drop its
     * content as item entities at the given position. Attachments without
     * stored content (default empty) contribute no drops.
     *
     * <p>Called with a live {@link Level} before the BE is destroyed —
     * attachments are still readable. Safe to call from {@code onRemove}
     * HEAD injection.
     */
    public static void dropAllAt(Level level, BlockPos pos, BlockEntity be) {
        if (level == null || be == null) return;
        // Server-side only — drops are ItemEntity spawns that the server
        // is authoritative for. Client-side block-removal prediction
        // should not spawn ghost entities.
        if (level.isClientSide()) return;

        for (AttachmentType<ItemContainerContents> type : StorageAttachments.blockScopedAttachments()) {
            if (!be.hasAttached(type)) continue;
            ItemContainerContents contents = be.getAttached(type);
            if (contents == null) continue;

            int slotCount = StorageAttachments.slotCountOf(type);
            if (slotCount <= 0) continue;

            // Expand the attachment's stored contents back to a size-N
            // list of stacks (ItemContainerContents trims trailing empties,
            // so size may be less than slotCount; contentsToList fills
            // missing trailing slots with EMPTY).
            NonNullList<ItemStack> items =
                    StorageAttachments.contentsToList(contents, slotCount);

            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level,
                            pos.getX() + 0.5,
                            pos.getY() + 0.5,
                            pos.getZ() + 0.5,
                            stack);
                }
            }
        }
    }
}
