package com.trevorschoeny.menukit.compat;

import com.trevorschoeny.menukit.core.attachment.PlayerSlotCapture;
import com.trevorschoeny.menukit.core.attachment.PlayerSlotCapture.CapturedSlot;

import eu.pb4.graves.GravesApi;
import eu.pb4.graves.grave.GraveInventoryMask;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * §0052 Phase 2 — Universal Graves adapter. Makes MKC player-slot contents land
 * <em>in</em> a UG grave (and restore on collect), generic over every
 * death-droppable player content slot via {@link PlayerSlotCapture}.
 *
 * <p><b>Optional dependency.</b> This class references UG types
 * ({@code eu.pb4.graves.*}, {@code modCompileOnly}) and is loaded ONLY behind a
 * {@code FabricLoader.isModLoaded("universal-graves")} guard (in
 * {@code MenuKitContainers.init}). When UG is absent the class never loads, and
 * the Phase-1 floor drops the slots beside the death spot. MKC hard-depends on UG
 * not at all.
 *
 * <p><b>Ordering.</b> UG's mask captures <em>before</em> vanilla's inventory drop
 * (it injects ahead of {@code dropEquipment}), so {@link PlayerSlotCapture#captureAndClear}
 * here clears the slots upstream of the Phase-1 {@code dropEquipment} floor — the
 * floor finds them empty and drops nothing (no dupes).
 *
 * <p><b>Identity.</b> A captured slot's real identity is {@code (attachmentId,
 * index)}; that rides UG's per-item {@link Tag} (UG's own {@code slot} int is just
 * a unique per-item key, since one mask spans multiple MKC attachments). On
 * collect, restore decodes the tag and writes each stack back to the exact slot.
 */
public final class UniversalGravesAdapter implements GraveInventoryMask {

    private static final Identifier MASK_ID =
            Identifier.fromNamespaceAndPath("menukit", "player_slots");
    private static final String K_ATTACHMENT = "MenuKitAttachment";
    private static final String K_INDEX = "MenuKitIndex";

    private UniversalGravesAdapter() {}

    /** Registers the mask with UG. Call ONLY when {@code universal-graves} is loaded. */
    public static void register() {
        GravesApi.registerInventoryMask(MASK_ID, new UniversalGravesAdapter());
    }

    @Override
    public void addToGrave(ServerPlayer player, ItemConsumer consumer) {
        List<CapturedSlot> captured = PlayerSlotCapture.captureAndClear(player);
        int graveSlot = 0;
        for (CapturedSlot cs : captured) {
            CompoundTag tag = new CompoundTag();
            tag.putString(K_ATTACHMENT, cs.attachmentId().toString());
            tag.putInt(K_INDEX, cs.index());
            consumer.addItem(cs.stack(), graveSlot++, tag);
        }
    }

    @Override
    public boolean moveToPlayerExactly(ServerPlayer player, ItemStack stack, int slot,
                                       @Nullable Tag optionalData) {
        return restore(player, stack, optionalData);
    }

    @Override
    public boolean moveToPlayerClosest(ServerPlayer player, ItemStack stack, int slot,
                                       @Nullable Tag optionalData) {
        return restore(player, stack, optionalData);
    }

    private static boolean restore(ServerPlayer player, ItemStack stack, @Nullable Tag optionalData) {
        if (!(optionalData instanceof CompoundTag tag)) return false;
        Identifier id = Identifier.tryParse(tag.getStringOr(K_ATTACHMENT, ""));
        int index = tag.getIntOr(K_INDEX, -1);
        if (id == null || index < 0) return false;
        return PlayerSlotCapture.restore(player, new CapturedSlot(id, index, stack));
    }
}
