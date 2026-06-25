package com.trevorschoeny.menukit.core.attachment;

import com.trevorschoeny.menukit.compat.GraveModPresence;
import com.trevorschoeny.menukit.core.DropRule;

import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Drops (or keeps / destroys) all death-droppable player-scoped attachment
 * contents when a player dies — the player-death twin of
 * {@link BlockScopedDropHandler}. Called from
 * {@code com.trevorschoeny.menukit.mixin.PlayerDeathDropMixin} at the TAIL of
 * {@code Player.dropEquipment}, co-located with vanilla's own
 * {@code keepInventory}-gated {@code destroyVanishingCursedItems()} +
 * {@code inventory.dropAll()}.
 *
 * <p>Closes the death-lifecycle gap: a {@code playerAttached} content attachment
 * is registered {@code copyOnDeath} (so a KEPT bag carries across respawn), but
 * Fabric's copy is not gamerule-aware — the library owns the {@code keepInventory}
 * check and the per-stack drop here. Each stack resolves a {@link DropRule}:
 * {@code KEEP} stays (copyOnDeath carries it), {@code DROP} spawns a vanilla
 * player drop, {@code DESTROY} vanishes.
 *
 * <p><b>Clear-after-drop is load-bearing.</b> Dropped/destroyed slots are
 * written back EMPTY before respawn's {@code copyOnDeath} runs — this prevents a
 * dupe (copyOnDeath would otherwise carry the same items that were just dropped)
 * and, in Phase 2, lets a grave mod's capture-and-clear naturally suppress this
 * drop fallback. Ordering is clean: drop-and-clear runs now (at death),
 * copyOnDeath copies later (at respawn) → a DROP death copies an empty bag, a
 * KEEP death copies the full bag.
 */
public final class PlayerDeathDropHandler {

    private PlayerDeathDropHandler() {}

    /**
     * For every death-droppable player content attachment the player carries,
     * resolve each stack's {@link DropRule} and act: drop (vanilla player drop)
     * + clear, destroy + clear, or keep. Server-side only.
     *
     * @param player the dying player (server-side)
     * @param level  the player's server level — source of the {@code keepInventory}
     *               gamerule (the same instance vanilla's {@code dropEquipment} reads)
     */
    public static void dropAllOnDeath(Player player, ServerLevel level) {
        if (player == null || level == null) return;
        // dropEquipment(ServerLevel) is already server-side; guard defensively.
        if (level.isClientSide()) return;

        boolean keepInventory = level.getGameRules().get(GameRules.KEEP_INVENTORY);

        for (AttachmentType<ItemContainerContents> type :
                StorageAttachments.playerDeathDropAttachments()) {
            if (!player.hasAttached(type)) continue;
            ItemContainerContents contents = player.getAttached(type);
            if (contents == null) continue;

            int slotCount = StorageAttachments.slotCountOf(type);
            if (slotCount <= 0) continue;

            DropRule configured = StorageAttachments.deathDropRuleOf(type);
            if (configured == null) configured = DropRule.DEFAULT;

            NonNullList<ItemStack> items = StorageAttachments.contentsToList(contents, slotCount);
            NonNullList<ItemStack> remaining = NonNullList.withSize(slotCount, ItemStack.EMPTY);
            boolean changed = false;

            for (int i = 0; i < slotCount; i++) {
                ItemStack stack = items.get(i);
                if (stack.isEmpty()) continue;
                DropRule effective = configured.resolve(stack, keepInventory);
                switch (effective) {
                    case DROP -> {
                        // The vanilla player-death drop leaf: eye-level spawn,
                        // 40-tick pickup delay, no thrower (any player can grab) —
                        // the same call vanilla's Inventory.dropAll uses. The slot
                        // is left EMPTY in `remaining` (cleared).
                        ItemEntity dropped = player.drop(stack, true, false);
                        // Grave-parity (§0052): when a grave-container mod is
                        // installed, the player recovers their vanilla inventory
                        // from a NON-expiring grave — so the slots that mod did not
                        // capture (ours) must not expire either, or they'd vanish
                        // after 5 min while the grave persists. Flag them
                        // never-despawn. With no grave mod present, keep vanilla
                        // despawn so a grafted slot matches a normal death drop.
                        if (dropped != null && GraveModPresence.anyGraveModPresent()) {
                            dropped.setUnlimitedLifetime();
                        }
                        changed = true;
                    }
                    case DESTROY ->
                        // Vanish: leave the slot EMPTY in `remaining` (cleared), no drop.
                        changed = true;
                    default ->
                        // KEEP (and the unreachable DEFAULT): the stack stays;
                        // copyOnDeath carries it across respawn.
                        remaining.set(i, stack);
                }
            }

            // Write back the kept-only list so respawn's copyOnDeath carries
            // exactly the KEPT stacks — never the dropped/destroyed ones (no dupe).
            if (changed) {
                player.setAttached(type, StorageAttachments.listToContents(remaining));
            }
        }
    }
}
