package com.trevorschoeny.menukit.mixin;

import com.trevorschoeny.menukit.core.attachment.BlockScopedDropHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes block-entity removal to drop M7 {@code blockScoped} attachment
 * contents as item entities before the BlockEntity is destroyed.
 *
 * <p>Fulfills M7's vanilla-container drop-on-break contract — consumers
 * who use {@code StorageAttachment.blockScoped(...)} get
 * vanilla-parity drop behavior automatically. Matches THESIS Principle 2
 * vanilla-substitutability: M7's block-scoped contract aligns with
 * vanilla container block semantics including drop-on-break.
 *
 * <p>Targets {@link BlockEntity#preRemoveSideEffects(BlockPos, BlockState)}
 * at HEAD. This is 1.21.11's canonical pre-removal side-effects hook —
 * vanilla's own container-content drop logic runs here (via
 * {@code BaseContainerBlockEntity.preRemoveSideEffects} which calls
 * {@code Containers.dropContents}). Running our drop at HEAD means M7
 * contents drop alongside the vanilla BE inventory, in the same frame,
 * with the same physics.
 *
 * <p>HEAD injection is observational — this mixin reads the attachment
 * before destruction but does not cancel or alter vanilla's removal
 * flow. Multiple mods can inject at the same point without conflict.
 * Server-side only drops via
 * {@link BlockScopedDropHandler#dropAllAt(Level, BlockPos, BlockEntity)}
 * which gates on {@code level.isClientSide()}.
 *
 * <p>Consumers who want silent-loss behavior for their attachments
 * bypass this mixin by declaring via {@code StorageAttachment.custom(spec)}
 * rather than {@code blockScoped(...)} — only blockScoped attachments
 * enroll in the registry this mixin dispatches against.
 */
@Mixin(BlockEntity.class)
public abstract class M7BlockDropMixin {

    @Inject(method = "preRemoveSideEffects", at = @At("HEAD"))
    private void menukit$dropBlockScopedContents(BlockPos pos, BlockState state,
                                                   CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) return;
        BlockScopedDropHandler.dropAllAt(level, pos, self);
    }
}
