package com.trevorschoeny.menukit.state;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

/**
 * Library-owned Fabric attachments for M1 per-slot state. Four attachment
 * types are registered, one per natural owner class:
 *
 * <ul>
 *   <li>{@link #PLAYER_INVENTORY} — on {@code Player}, for
 *       {@code PlayerInventory(uuid)} keys.</li>
 *   <li>{@link #ENDER_CHEST} — on {@code Player}, for {@code EnderChest(uuid)}
 *       keys (distinct attachment so the two don't collide).</li>
 *   <li>{@link #BLOCK_ENTITY} — on {@code BlockEntity}, stores a per-player
 *       bag map so each viewing player has private marks.</li>
 *   <li>{@link #ENTITY} — on {@code Entity}, same per-player storage shape
 *       for donkey/minecart/etc.</li>
 * </ul>
 *
 * <p>No {@code syncWith(...)} — the library syncs via its own packet path
 * (snapshot + update), not Fabric's auto-sync. Persistence uses the bag's
 * own {@code CODEC}; no custom initializer is registered (the library's
 * server facade lazy-creates bags on first write).
 */
public final class SlotStateAttachments {

    private static final String MOD_ID = "menukit";

    public static final AttachmentType<SlotStateBag> PLAYER_INVENTORY =
            AttachmentRegistry.<SlotStateBag>builder()
                    .persistent(SlotStateBag.CODEC)
                    .initializer(SlotStateBag::new)
                    .buildAndRegister(Identifier.fromNamespaceAndPath(
                            MOD_ID, "slot_state_player_inventory"));

    public static final AttachmentType<SlotStateBag> ENDER_CHEST =
            AttachmentRegistry.<SlotStateBag>builder()
                    .persistent(SlotStateBag.CODEC)
                    .initializer(SlotStateBag::new)
                    .buildAndRegister(Identifier.fromNamespaceAndPath(
                            MOD_ID, "slot_state_ender_chest"));

    public static final AttachmentType<PerPlayerSlotStateBag> BLOCK_ENTITY =
            AttachmentRegistry.<PerPlayerSlotStateBag>builder()
                    .persistent(PerPlayerSlotStateBag.CODEC)
                    .initializer(PerPlayerSlotStateBag::new)
                    .buildAndRegister(Identifier.fromNamespaceAndPath(
                            MOD_ID, "slot_state_block_entity"));

    public static final AttachmentType<PerPlayerSlotStateBag> ENTITY =
            AttachmentRegistry.<PerPlayerSlotStateBag>builder()
                    .persistent(PerPlayerSlotStateBag.CODEC)
                    .initializer(PerPlayerSlotStateBag::new)
                    .buildAndRegister(Identifier.fromNamespaceAndPath(
                            MOD_ID, "slot_state_entity"));

    /**
     * Triggers class loading so the static field initializers above run.
     * Callable from both server- and client-init contexts.
     */
    public static void register() {
        // No-op body — registration is driven by the static fields above.
    }

    private SlotStateAttachments() {}
}
