package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.MendingCandidates;
import com.trevorschoeny.menukit.core.MenuKitSlot;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The mending primitive's <b>intercept</b> — widens vanilla's XP-orb repair pool
 * so a damaged Mending item in an opted-in grafted slot ({@code SlotGroup.mendsFromXp})
 * or a consumer-contributed source ({@link MendingCandidates}) repairs from XP
 * exactly like worn armor does. Library-owned grafted-slot vanilla mechanic, the
 * same line as death-drop (§0052) and binding (§0053).
 *
 * <h3>One unified pool, once</h3>
 * Vanilla's {@code repairPlayerItems} picks a single damaged Mending item via
 * {@code EnchantmentHelper.getRandomItemWith(REPAIR_WITH_XP, …)}, repairs it,
 * consumes proportional XP, and recurses with the leftover. We {@link Redirect}
 * that one pick so the pool is unified in a single place:
 * <pre>  vanilla equipped (preserved) ∪ opted-in grafted slots ∪ consumer candidates</pre>
 * then repair runs with vanilla's own math. Unifying at the pick site means one
 * orb repairs one item across all sources — no double-dipping, and no second
 * {@code ExperienceOrb} mixin needed consumer-side.
 *
 * <h3>Vanilla is untouched when nobody opts in</h3>
 * If the entity isn't a server player, or no grafted slot opts in and no consumer
 * registers a candidate, the redirect returns vanilla's result verbatim — identical
 * behavior. Vanilla equipped items keep their exact selection + break semantics
 * (via the original {@code getRandomItemWith} call).
 *
 * <h3>Fair selection (§0053)</h3>
 * Uniform random pick across the unified pool — matching vanilla's per-orb
 * randomness. Each equipped mendable item is expanded into its own pool entry
 * (rather than vanilla's single pre-collapsed {@code getRandomItemWith} pick), so a
 * uniform draw weights every mendable item equally and the opted-in grafted /
 * consumer candidates are no longer over-represented versus equipped-as-a-group.
 *
 * <h3>Sync (the load-bearing subtlety)</h3>
 * Vanilla repairs the chosen stack <em>in place</em> AFTER this redirect returns,
 * and {@code repairPlayerItems} recurses — so we can't persist at pick time. For a
 * non-vanilla candidate we queue a commit (grafted: {@code slot.set} → storage
 * {@code markDirty}; consumer: its {@code onRepaired}) on the orb instance and
 * drain it at {@code playerTouch} TAIL, once the whole repair has finished. An
 * in-place durability change alone may not round-trip an attachment-backed slot to
 * disk; the commit marks it dirty (and vanilla's {@code broadcastChanges} handles
 * the client sync from the in-place change).
 */
@Mixin(ExperienceOrb.class)
public class ExperienceOrbMendMixin {

    // Commits for the non-vanilla candidates actually picked + repaired this orb.
    // Lives on the orb instance (stable across playerTouch → repairPlayerItems
    // recursion); lazy-init at playerTouch HEAD so it cannot leak across orbs.
    @Unique
    private List<Runnable> menukit$mendCommits;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void menukit$mendBegin(Player player, CallbackInfo ci) {
        this.menukit$mendCommits = new ArrayList<>();
    }

    @Inject(method = "playerTouch", at = @At("TAIL"))
    private void menukit$mendDrain(Player player, CallbackInfo ci) {
        if (this.menukit$mendCommits != null) {
            for (Runnable commit : this.menukit$mendCommits) commit.run();
            this.menukit$mendCommits = null;
        }
    }

    @Redirect(
            method = "repairPlayerItems",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;"
                            + "getRandomItemWith(Lnet/minecraft/core/component/DataComponentType;"
                            + "Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Predicate;)"
                            + "Ljava/util/Optional;"))
    private Optional<EnchantedItemInUse> menukit$widenMendPool(
            DataComponentType<?> effect, LivingEntity entity, Predicate<ItemStack> isDamaged) {
        Optional<EnchantedItemInUse> vanilla =
                EnchantmentHelper.getRandomItemWith(effect, entity, isDamaged);
        if (!(entity instanceof ServerPlayer player)) return vanilla;

        // Gather the extra (non-vanilla) candidates. Parallel lists: pool[i] is the
        // repair target, commits[i] is its mark-dirty/sync (null for the vanilla pick).
        List<EnchantedItemInUse> pool = new ArrayList<>();
        List<Runnable> commits = new ArrayList<>();
        int extras = 0;

        // Opted-in grafted slots on the player's own inventory menu.
        for (Slot slot : player.inventoryMenu.slots) {
            if (slot instanceof MenuKitSlot mk && mk.getGroup().mendsFromXp()) {
                ItemStack stack = mk.getItem();
                if (menukit$mendable(stack)) {
                    pool.add(new EnchantedItemInUse(stack, null, player, item -> {}));
                    commits.add(() -> mk.set(stack));   // write back + markDirty
                    extras++;
                }
            }
        }
        // Consumer-contributed candidates (MKC applies the predicate; consumer owns "which").
        for (MendingCandidates.Candidate candidate : MendingCandidates.gather(player)) {
            ItemStack stack = candidate.stack();
            if (menukit$mendable(stack)) {
                pool.add(new EnchantedItemInUse(stack, null, player, item -> {}));
                commits.add(candidate::onRepaired);
                extras++;
            }
        }

        // Nothing opted in → vanilla behavior, untouched.
        if (extras == 0) return vanilla;

        // §0053 fair weighting: add EVERY equipped mendable item as its own pool
        // entry — not vanilla's single pre-collapsed pick — so the opted-in grafted /
        // consumer candidates aren't over-represented versus equipped-as-a-group.
        // (Vanilla's getRandomItemWith collapses all equipped matches to one entry;
        // we expand them so a uniform pick weights every mendable item equally.)
        // Equipped items repair in place on the live equipment stack, which syncs via
        // vanilla's equipment broadcast — commit = null, exactly like vanilla's pick.
        for (EquipmentSlot eq : EquipmentSlot.values()) {
            ItemStack equipped = entity.getItemBySlot(eq);
            if (menukit$mendable(equipped)) {
                pool.add(new EnchantedItemInUse(equipped, null, player, item -> {}));
                commits.add(null);
            }
        }
        if (pool.isEmpty()) return Optional.empty();

        int index = player.getRandom().nextInt(pool.size());
        Runnable commit = commits.get(index);
        if (commit != null && this.menukit$mendCommits != null) {
            this.menukit$mendCommits.add(commit);
        }
        return Optional.of(pool.get(index));
    }

    /** Damaged AND carries the vanilla {@code REPAIR_WITH_XP} (Mending) effect. */
    @Unique
    private static boolean menukit$mendable(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamaged()
                && EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP);
    }
}
