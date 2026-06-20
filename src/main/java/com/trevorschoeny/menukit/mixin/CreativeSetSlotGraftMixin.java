package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.MenuKitSlot;

import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * §0051 Fix 1 — the creative-set-slot bridge: makes §0045 grafted slots
 * round-trip in creative, closing the silent-drop gap that the consumer cannot
 * fix (Inventory Max Equipment Slots).
 *
 * <h3>Why this layer (§0051 / §0019)</h3>
 *
 * Survival slot edits ride {@code ServerboundContainerClickPacket → menu.clicked},
 * which reaches a grafted {@link MenuKitSlot} like any other slot. Creative edits
 * ride a <em>different</em> server path —
 * {@code ServerboundSetCreativeModeSlotPacket → handleSetCreativeModeSlot} —
 * which is <b>index-bounded to vanilla slots</b>: it writes
 * {@code player.inventoryMenu.getSlot(n)} only for {@code n} in {@code [1, 45]},
 * drops {@code n < 0}, and <b>silently discards everything else</b>. A grafted
 * slot lives <em>past</em> index 45 (§0045 appends it after the offhand), so its
 * creative placement falls into the discard path and the item vanishes
 * server-side. This is the upstream injection point §0051 mandates — the packet
 * handler, above the per-mode divergence — not a per-screen mixin creative would
 * defeat.
 *
 * <h3>What it does</h3>
 *
 * At HEAD, for a creative player, if the incoming slot index resolves to a
 * grafted {@link MenuKitSlot} on the inventory menu, it replicates vanilla's
 * <em>own</em> in-bounds write path for that index — {@code setByPlayer} (which
 * routes through the slot's {@code StorageContainerAdapter} to the backing
 * {@code Storage}, and bypasses {@code mayPlace} exactly as vanilla creative
 * does), then {@code setRemoteSlot} + {@code broadcastChanges} to keep the
 * client in sync — and cancels, so vanilla's discard never runs. Every
 * non-grafted index (vanilla slot, drop sentinel, out of range) is left entirely
 * to vanilla.
 *
 * <h3>Generic, no per-consumer schema (§0048 shape)</h3>
 *
 * Grafted slots are detected by type ({@code instanceof MenuKitSlot}), not a
 * registry — the library ships no graft registry (§0045), and every graft uses
 * {@link MenuKitSlot}, so this is generic over all registered grafts with no
 * per-feature knowledge. The library is the only layer that knows a slot is
 * grafted, so this bridge is library-owned by necessity (§0019-clean: it routes
 * the consumer's own slot, it does not decide consumer behavior).
 */
@ApiStatus.Internal
@Mixin(ServerGamePacketListenerImpl.class)
public class CreativeSetSlotGraftMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void menukit$routeGraftedCreativeSet(
            ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        // Run only on the server thread. Vanilla's very first action (just after
        // this HEAD inject) is PacketUtils.ensureRunningOnSameThread, which
        // reschedules the initial network-thread call onto the main thread and
        // aborts it. Our inject runs BEFORE that, so on the off-thread call we must
        // bail WITHOUT cancelling — letting vanilla reschedule — and act only on
        // the main-thread re-invocation. This keeps the storage write and
        // broadcastChanges on the server thread, never the network thread.
        MinecraftServer server = this.player.level().getServer();
        if (server == null || !server.isSameThread()) return;

        // Only the creative path edits via this packet — mirror vanilla's gate so
        // a non-creative player (spoofed packet) is left to vanilla's own checks.
        if (!this.player.hasInfiniteMaterials()) return;

        int slotNum = packet.slotNum();
        AbstractContainerMenu menu = this.player.inventoryMenu;

        // Defer to vanilla for everything that isn't a grafted slot: the drop
        // sentinel (n < 0), out-of-range indices, and real vanilla slots all
        // stay vanilla's job. Only a MenuKitSlot past vanilla's range is ours —
        // it is exactly the index vanilla would silently discard.
        if (slotNum < 0 || slotNum >= menu.slots.size()) return;
        Slot slot = menu.getSlot(slotNum);
        if (!(slot instanceof MenuKitSlot)) return;

        // The grafted index is ours; vanilla would vanish it. Replicate vanilla's
        // in-bounds write for valid stacks, then cancel either way (we own this
        // index — vanilla must not also run its discard).
        ItemStack stack = packet.itemStack();
        boolean validStack = stack.isEmpty() || stack.getCount() <= stack.getMaxStackSize();
        if (stack.isItemEnabled(this.player.level().enabledFeatures()) && validStack) {
            slot.setByPlayer(stack);                 // → StorageContainerAdapter → backing Storage
            menu.setRemoteSlot(slotNum, stack);      // keep remote tracking in step (no redundant re-send)
            menu.broadcastChanges();
        }
        ci.cancel();
    }
}
