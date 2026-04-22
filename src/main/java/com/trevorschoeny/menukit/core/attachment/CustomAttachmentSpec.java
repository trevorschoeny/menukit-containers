package com.trevorschoeny.menukit.core.attachment;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Consumer-implemented interface for M7's extension point. Serves two
 * purposes:
 *
 * <ol>
 *   <li><b>Modded owner types.</b> Consumers whose owner type isn't covered
 *       by v1's four shipped factories (Ephemeral, Player-attached,
 *       Block-scoped, Item-attached) provide their own save / load / dirty
 *       hooks via this spec and use {@code StorageAttachment.custom(spec)}
 *       to wrap it.</li>
 *   <li><b>Decorator-path escape hatch.</b> Consumers decorating vanilla
 *       menus via {@code ScreenPanelAdapter} — which doesn't route through
 *       {@code MenuKitScreenHandler} and therefore doesn't get the default
 *       handler-owned save/load lifecycle — use a custom spec and trigger
 *       save/load from their decorator mixin directly.</li>
 * </ol>
 *
 * <p>The library never learns what {@code O} is. The spec handles all
 * owner-specific persistence; the library only dispatches lifecycle calls.
 *
 * <p>Content type {@code C} is the consumer's choice. For the common case
 * ({@code NonNullList<ItemStack>}), the consumer implements a straightforward
 * spec; for structured content (records, wrappers), the consumer provides
 * conversions inside the spec methods.
 *
 * @param <O> owner type (whatever the consumer's persistence anchors on)
 * @param <C> content type (what the consumer stores per slot group)
 *
 * @see com.trevorschoeny.menukit.core.StorageAttachment
 */
public interface CustomAttachmentSpec<O, C> {

    /**
     * Read the stored content for this owner instance. The returned content
     * becomes the authoritative state for subsequent {@code Storage} reads.
     * If nothing has been persisted yet, return a default built from
     * {@link #defaultFactory()}.
     */
    C read(O owner);

    /**
     * Write the content for this owner instance. Called after consumer
     * mutations (via Storage.setStack) to flush to persistent storage.
     *
     * <p>For Fabric-attachment-backed specs, implementations typically call
     * {@code owner.setAttached(attachmentType, content)} here.
     */
    void write(O owner, C content);

    /**
     * Mark the owner as needing persistence. For block entities, call
     * {@code be.setChanged()}. For entities / players, this is often a no-op
     * (Fabric auto-persists attachment changes on save).
     */
    void markDirty(O owner);

    /**
     * Produces a fresh default content when no stored value exists yet.
     * For an {@code NonNullList<ItemStack>}-based spec with {@code slotCount} slots:
     * <pre>{@code
     *   () -> NonNullList.withSize(slotCount, ItemStack.EMPTY)
     * }</pre>
     */
    C defaultFactory();

    /**
     * Converts stored content to a mutable {@link NonNullList} view for the
     * Storage surface. If {@code C} is already {@code NonNullList<ItemStack>},
     * return the content directly (identity). For structured content, convert.
     */
    NonNullList<ItemStack> toItemList(C content);

    /**
     * Converts a mutated item list back to the structured content, preserving
     * any non-ItemStack fields. Called by the Storage surface after each
     * {@code setStack} so the mutation persists through {@link #write}.
     * If {@code C} is already {@code NonNullList<ItemStack>}, return the list
     * directly (identity).
     */
    C fromItemList(NonNullList<ItemStack> items);

    /**
     * How many slots this content holds. Used by the Storage surface to
     * report size.
     */
    int slotCount();

    /**
     * Identifier for this spec — useful for debug logging and for
     * distinguishing specs that share an owner type.
     */
    Identifier id();
}
