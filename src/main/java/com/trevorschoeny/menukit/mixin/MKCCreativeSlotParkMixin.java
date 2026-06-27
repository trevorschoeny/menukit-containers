package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.MKCSlot;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Parks registered slots' creative {@code SlotWrapper}s off-screen so vanilla draws
 * and hover-tests nothing for them — the library half of creative slot parity.
 *
 * <h3>Why</h3>
 *
 * The creative inventory tab wraps every {@code player.inventoryMenu} slot —
 * slots included — in a {@code SlotWrapper} positioned by index, and the slot
 * indices have no creative layout slot, so vanilla would draw them overlapping the
 * hotbar. Rather than each consumer hand-placing its slots in creative (the old
 * {@code CreativeEquipmentSlotMixin} burden), the library parks <em>all</em> slot
 * wrappers off-screen here; the screen dispatcher's render + hover helpers then
 * draw and hit-test slots at their live {@code renderX/renderY} exactly as on the
 * survival inventory. One uniform model across both modes — and the wrapper still
 * lives in the creative menu, so a click on the slot still routes through it to
 * the real backing slot.
 *
 * <p>{@code Slot.x/y} are final, so position must be set at wrapper construction —
 * hence {@link ModifyArgs} on the {@code new SlotWrapper(target, index, x, y)} call
 * (args 2 = x, 3 = y). Vanilla itself parks its 2×2 craft slots at extreme coords
 * the same way, so this is a blessed pattern.
 *
 * <p>Generic: detects a slot by {@code target instanceof MKCSlot}; no
 * per-consumer knowledge. Client-only (creative screen is a client type).
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class MKCCreativeSlotParkMixin {

    /** Far off-screen — same parking coordinate the survival slot uses. */
    private static final int SLOT_OFFSCREEN = -10000;

    @ModifyArgs(
            method = "selectTab",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen$SlotWrapper;"
                            + "<init>(Lnet/minecraft/world/inventory/Slot;III)V"))
    private void mk$parkSlotWrappers(Args args) {
        // arg 0 is the wrapped target slot; slots wrap a raw MKCSlot.
        if (args.<Object>get(0) instanceof MKCSlot) {
            args.set(2, SLOT_OFFSCREEN); // x
            args.set(3, SLOT_OFFSCREEN); // y
        }
    }
}
