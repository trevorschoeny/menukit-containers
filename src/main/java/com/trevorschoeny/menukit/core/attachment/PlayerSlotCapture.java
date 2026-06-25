package com.trevorschoeny.menukit.core.attachment;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * §0052 Phase 2 — the grave-capture surface. Generic over every death-droppable
 * MKC player content slot (the {@code PLAYER_DEATH_DROP} registry). A grave-mod
 * adapter captures (reads + clears) the slots into the grave's data and restores
 * them on collect by calling <em>this surface</em> — never individual slots. So
 * an adapter is <b>write-once-per-grave-mod</b>; every consumer's slots (Pockets,
 * Equipment, future) ride the same adapter for free.
 *
 * <p><b>Ordering:</b> grave mods capture <em>before</em> vanilla drops the
 * inventory (Universal Graves injects ahead of {@code dropEquipment}), so
 * {@link #captureAndClear} runs upstream of the Phase-1 {@code dropEquipment}
 * floor — the floor then finds the slots empty and drops nothing. No dupes, no
 * explicit suppression. The clear here is the same write-back-empty Phase 1 uses;
 * respawn's {@code copyOnDeath} then carries an empty bag.
 */
public final class PlayerSlotCapture {

    private PlayerSlotCapture() {}

    /**
     * One captured slot: stable identity (the attachment's registered
     * {@link Identifier} + the local slot index) plus the stack. The id makes it
     * serializable for grave round-trips (NBT-backed grave mods).
     */
    public record CapturedSlot(Identifier attachmentId, int index, ItemStack stack) {
        public static final Codec<CapturedSlot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("attachment").forGetter(CapturedSlot::attachmentId),
                Codec.INT.fieldOf("slot").forGetter(CapturedSlot::index),
                ItemStack.CODEC.fieldOf("stack").forGetter(CapturedSlot::stack)
        ).apply(i, CapturedSlot::new));

        /** Codec for a whole capture — what an NBT-backed grave stores. */
        public static final Codec<List<CapturedSlot>> LIST_CODEC = CODEC.listOf();
    }

    /**
     * For every death-droppable player content slot on {@code player}, read the
     * non-empty stack and CLEAR the slot. Returns the captured slots for the
     * adapter to push into the grave. Clearing makes the Phase-1 floor stand down
     * (it finds the slots empty). Server-side.
     */
    public static List<CapturedSlot> captureAndClear(ServerPlayer player) {
        List<CapturedSlot> captured = new ArrayList<>();
        for (AttachmentType<ItemContainerContents> type : StorageAttachments.playerDeathDropAttachments()) {
            if (!player.hasAttached(type)) continue;
            ItemContainerContents contents = player.getAttached(type);
            if (contents == null) continue;
            int slotCount = StorageAttachments.slotCountOf(type);
            if (slotCount <= 0) continue;
            Identifier id = StorageAttachments.identifierOf(type);
            if (id == null) continue;

            NonNullList<ItemStack> items = StorageAttachments.contentsToList(contents, slotCount);
            boolean changed = false;
            for (int i = 0; i < slotCount; i++) {
                ItemStack stack = items.get(i);
                if (stack.isEmpty()) continue;
                captured.add(new CapturedSlot(id, i, stack.copy()));
                items.set(i, ItemStack.EMPTY);
                changed = true;
            }
            if (changed) {
                player.setAttached(type, StorageAttachments.listToContents(items));
            }
        }
        return captured;
    }

    /**
     * Writes a captured stack back to its slot on grave collect. No-ops (returns
     * false) if the attachment id is unknown or the index is out of range — the
     * slot layout changed since capture (consumer reconfigured / removed the
     * attachment); the caller handles such overflow (e.g. drops it).
     *
     * @return true if restored to the slot; false if it couldn't be placed
     */
    public static boolean restore(ServerPlayer player, CapturedSlot slot) {
        AttachmentType<ItemContainerContents> type = StorageAttachments.typeById(slot.attachmentId());
        if (type == null) return false;
        int slotCount = StorageAttachments.slotCountOf(type);
        if (slot.index() < 0 || slot.index() >= slotCount) return false;

        ItemContainerContents contents = player.hasAttached(type)
                ? player.getAttached(type)
                : StorageAttachments.defaultEmpty(slotCount);
        NonNullList<ItemStack> items = StorageAttachments.contentsToList(contents, slotCount);
        items.set(slot.index(), slot.stack());
        player.setAttached(type, StorageAttachments.listToContents(items));
        return true;
    }

    /**
     * Restores a whole capture; returns the stacks that could NOT be placed
     * (unknown attachment / out-of-range index) so the caller can drop them.
     */
    public static List<ItemStack> restoreAll(ServerPlayer player, List<CapturedSlot> slots) {
        List<ItemStack> overflow = new ArrayList<>();
        for (CapturedSlot s : slots) {
            if (!restore(player, s)) overflow.add(s.stack());
        }
        return overflow;
    }
}
