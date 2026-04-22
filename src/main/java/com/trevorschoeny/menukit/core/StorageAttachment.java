package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.core.attachment.CustomAttachmentSpec;
import com.trevorschoeny.menukit.core.attachment.StorageAttachments;

import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.Supplier;

/**
 * M7 — Storage Attachment Taxonomy. Public entry point for slot-group
 * content persistence, by owner type.
 *
 * <p>Produces a {@link Storage} bound to a specific owner instance. The
 * returned Storage reads/writes whatever persistence surface the factory
 * declared — Fabric attachment (Player, BlockEntity), data component
 * (ItemStack), in-memory list (Ephemeral), or consumer-supplied hooks
 * (CustomAttachmentSpec).
 *
 * <p>See {@code Design Docs/Mechanisms/M7_STORAGE_ATTACHMENT.md} for design
 * rationale including the per-entry Principle 11 check that scoped v1 to
 * four factories (ephemeral, playerAttached, blockScoped, itemContainer) +
 * custom extension. Block-portable and entity-attached are deferred to
 * first-concrete-consumer trigger.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // At mod init — declare the attachment.
 * public static final StorageAttachment<BlockEntity, NonNullList<ItemStack>> FURNACE_EXTRA =
 *     StorageAttachment.blockScoped("my-mod", "furnace_extra", 1);
 *
 * // At menu construction — bind to a specific owner instance.
 * Storage storage = FURNACE_EXTRA.bind(furnaceBE);
 * panel.slotGroup("extra").slot(0, 0).build(storage);
 * }</pre>
 *
 * @param <O> owner type (Void for ephemeral, Player / BlockEntity / etc.
 *            for persistent variants)
 * @param <C> content type — {@link NonNullList} of {@link ItemStack} for
 *            most v1 factories; {@link ItemContainerContents} for
 *            {@link #itemContainer(int)}; consumer-defined for custom specs
 */
public abstract class StorageAttachment<O, C> {

    /** Number of slots this attachment's content holds. */
    public abstract int slotCount();

    /**
     * Binds this attachment to a specific owner instance. Returns a
     * {@link Storage} view that reads/writes the attachment's content for
     * that owner.
     *
     * <p>For owners with stable references during the menu lifetime
     * (Player, BlockEntity, Entity), pass the reference directly.
     */
    public abstract Storage bind(O owner);

    /**
     * Binds via a live supplier for owners whose reference can change
     * during the menu lifetime (notably {@link ItemStack}, which vanilla
     * may replace during interactions). The library calls the supplier on
     * every read/write.
     */
    public Storage bind(Supplier<O> ownerSupplier) {
        // Default: a thin adapter that re-resolves the owner on every call
        // and delegates to bind(O). Specific factories may override if they
        // can cache state across calls.
        StorageAttachment<O, C> self = this;
        return new Storage() {
            @Override public ItemStack getStack(int i) {
                return self.bind(ownerSupplier.get()).getStack(i);
            }
            @Override public void setStack(int i, ItemStack s) {
                self.bind(ownerSupplier.get()).setStack(i, s);
            }
            @Override public int size() { return self.slotCount(); }
            @Override public void markDirty() {
                self.bind(ownerSupplier.get()).markDirty();
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Factories
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Session-scoped, in-memory storage. Content dies when the menu closes
     * unless the handler's close-drop policy captures it. Equivalent to
     * wrapping an {@link EphemeralStorage} — {@code StorageAttachment}
     * presents it through the unified API.
     *
     * <p>Owner type is {@link Void}; callers pass {@code null} to {@link #bind}.
     */
    public static StorageAttachment<Void, NonNullList<ItemStack>> ephemeral(int slotCount) {
        return new EphemeralAttachment(slotCount);
    }

    /**
     * Persists slot-group content on a {@link Player} via a Fabric attachment.
     * Content travels with the player across sessions, dimensions, and
     * respawn (player data serialization).
     *
     * @param namespace attachment-id namespace (consumer's mod id)
     * @param path      attachment-id path (unique per consumer-declared attachment)
     * @param slotCount number of item slots the content holds
     */
    public static StorageAttachment<Player, NonNullList<ItemStack>> playerAttached(
            String namespace, String path, int slotCount) {
        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
        AttachmentType<ItemContainerContents> type =
                StorageAttachments.registerContainerAttachment(id, slotCount);
        return new PlayerAttachment(slotCount, type);
    }

    /**
     * Persists slot-group content on a {@link BlockEntity} via a Fabric
     * attachment. Content dies with the block — breaking destroys the
     * attachment. Intended for dies-with-block containers (chest, furnace,
     * hopper, etc.).
     *
     * @param namespace attachment-id namespace (consumer's mod id)
     * @param path      attachment-id path (unique per consumer-declared attachment)
     * @param slotCount number of item slots the content holds
     */
    public static StorageAttachment<BlockEntity, NonNullList<ItemStack>> blockScoped(
            String namespace, String path, int slotCount) {
        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
        // registerBlockScopedAttachment enrolls the attachment in the
        // block-scoped registry that the drop-on-break mixin dispatches
        // against (see BlockScopedDropHandler) — this is what fulfills
        // M7's vanilla-container drop-on-break contract.
        AttachmentType<ItemContainerContents> type =
                StorageAttachments.registerBlockScopedAttachment(id, slotCount);
        return new BlockScopedAttachment(slotCount, type);
    }

    /**
     * Persists slot-group content on an {@link ItemStack} via vanilla's
     * {@link DataComponents#CONTAINER} component ({@link ItemContainerContents}).
     * Content travels with the item through menus, pickup, drop, throw.
     *
     * <p>v1 hardcodes {@code DataComponents.CONTAINER} as the component
     * type — the canonical vanilla surface for item-backed slot groups
     * (shulker items, bundles). A fully-generic
     * {@code itemAttached(DataComponentType<C>, ...)} factory is a
     * follow-on when a concrete consumer surfaces needing a non-CONTAINER
     * component.
     *
     * <p>Because ItemStacks can be replaced by vanilla during interactions
     * (e.g., when a slot is vacated), prefer {@link #bind(Supplier)} over
     * {@link #bind(Object)} for item-backed storage.
     */
    public static StorageAttachment<ItemStack, ItemContainerContents> itemContainer(int slotCount) {
        return new ItemContainerAttachment(slotCount);
    }

    /**
     * Wraps a {@link CustomAttachmentSpec} as a {@code StorageAttachment}.
     * Used for owner types outside the four v1 factories AND as the
     * decorator-path escape hatch for consumers whose save/load doesn't
     * route through a MenuKit handler.
     *
     * @param <O> owner type
     * @param <C> content type
     */
    public static <O, C> StorageAttachment<O, C> custom(CustomAttachmentSpec<O, C> spec) {
        return new CustomAttachmentWrapper<>(spec);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal implementations (package-private subclasses live alongside)
    // ══════════════════════════════════════════════════════════════════════

    /** In-memory ephemeral. Owner is Void; bind(null) returns a fresh EphemeralStorage. */
    private static final class EphemeralAttachment
            extends StorageAttachment<Void, NonNullList<ItemStack>> {
        private final int slotCount;
        EphemeralAttachment(int slotCount) { this.slotCount = slotCount; }
        @Override public int slotCount() { return slotCount; }
        @Override public Storage bind(Void ignored) { return EphemeralStorage.of(slotCount); }
    }

    /** Player-attached via Fabric attachment. */
    private static final class PlayerAttachment
            extends StorageAttachment<Player, NonNullList<ItemStack>> {
        private final int slotCount;
        private final AttachmentType<ItemContainerContents> type;
        PlayerAttachment(int slotCount, AttachmentType<ItemContainerContents> type) {
            this.slotCount = slotCount;
            this.type = type;
        }
        @Override public int slotCount() { return slotCount; }
        @Override public Storage bind(Player player) {
            // Wrap in a PlayerStorage-implementing adapter so shift-click
            // routing (MenuKitScreenHandler) sees this as player-side.
            return new PlayerFabricStorage(slotCount,
                    () -> player.hasAttached(type)
                            ? player.getAttached(type)
                            : StorageAttachments.defaultEmpty(slotCount),
                    contents -> player.setAttached(type, contents),
                    () -> { /* Players auto-persist via Fabric; no setChanged */ });
        }
    }

    /** FabricAttachmentStorage variant that implements the PlayerStorage
     *  marker for shift-click routing. Same logic as the base class. */
    private static final class PlayerFabricStorage implements PlayerStorage {
        private final FabricAttachmentStorage delegate;
        PlayerFabricStorage(int slotCount,
                             Supplier<ItemContainerContents> reader,
                             java.util.function.Consumer<ItemContainerContents> writer,
                             Runnable dirtyMarker) {
            this.delegate = new FabricAttachmentStorage(slotCount, reader, writer, dirtyMarker);
        }
        @Override public ItemStack getStack(int i) { return delegate.getStack(i); }
        @Override public void setStack(int i, ItemStack s) { delegate.setStack(i, s); }
        @Override public int size() { return delegate.size(); }
        @Override public void markDirty() { delegate.markDirty(); }
    }

    /** BlockEntity-attached via Fabric attachment. */
    private static final class BlockScopedAttachment
            extends StorageAttachment<BlockEntity, NonNullList<ItemStack>> {
        private final int slotCount;
        private final AttachmentType<ItemContainerContents> type;
        BlockScopedAttachment(int slotCount, AttachmentType<ItemContainerContents> type) {
            this.slotCount = slotCount;
            this.type = type;
        }
        @Override public int slotCount() { return slotCount; }
        @Override public Storage bind(BlockEntity be) {
            return new FabricAttachmentStorage(slotCount,
                    () -> be.hasAttached(type)
                            ? be.getAttached(type)
                            : StorageAttachments.defaultEmpty(slotCount),
                    contents -> be.setAttached(type, contents),
                    be::setChanged);
        }
    }

    /** ItemStack-attached via DataComponents.CONTAINER. */
    private static final class ItemContainerAttachment
            extends StorageAttachment<ItemStack, ItemContainerContents> {
        private final int slotCount;
        ItemContainerAttachment(int slotCount) { this.slotCount = slotCount; }
        @Override public int slotCount() { return slotCount; }
        @Override public Storage bind(ItemStack stack) {
            return new Storage() {
                @Override public ItemStack getStack(int i) {
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                    ItemContainerContents c = stack.get(DataComponents.CONTAINER);
                    if (c == null) return ItemStack.EMPTY;
                    NonNullList<ItemStack> list =
                            StorageAttachments.contentsToList(c, slotCount);
                    return list.get(i);
                }
                @Override public void setStack(int i, ItemStack s) {
                    if (stack.isEmpty()) return;
                    ItemContainerContents c = stack.get(DataComponents.CONTAINER);
                    NonNullList<ItemStack> list =
                            StorageAttachments.contentsToList(c, slotCount);
                    list.set(i, s);
                    stack.set(DataComponents.CONTAINER,
                            StorageAttachments.listToContents(list));
                }
                @Override public int size() { return slotCount; }
                @Override public void markDirty() {
                    // DataComponents are inherently dirty on set; no separate call.
                }
            };
        }
    }

    /** Consumer-supplied spec via {@link CustomAttachmentSpec}. */
    private static final class CustomAttachmentWrapper<O, C>
            extends StorageAttachment<O, C> {
        private final CustomAttachmentSpec<O, C> spec;
        CustomAttachmentWrapper(CustomAttachmentSpec<O, C> spec) { this.spec = spec; }
        @Override public int slotCount() { return spec.slotCount(); }
        @Override public Storage bind(O owner) {
            return new Storage() {
                @Override public ItemStack getStack(int i) {
                    C content = spec.read(owner);
                    return spec.toItemList(content).get(i);
                }
                @Override public void setStack(int i, ItemStack s) {
                    C content = spec.read(owner);
                    NonNullList<ItemStack> list = spec.toItemList(content);
                    list.set(i, s);
                    spec.write(owner, spec.fromItemList(list));
                    spec.markDirty(owner);
                }
                @Override public int size() { return spec.slotCount(); }
                @Override public void markDirty() { spec.markDirty(owner); }
            };
        }
    }

    /**
     * Shared Storage implementation backed by a Fabric attachment supplier/sink.
     * Reads by pulling a fresh view on every getStack; writes by creating a
     * new ItemContainerContents and calling the sink.
     */
    private static final class FabricAttachmentStorage implements Storage {
        private final int slotCount;
        private final Supplier<ItemContainerContents> reader;
        private final java.util.function.Consumer<ItemContainerContents> writer;
        private final Runnable dirtyMarker;

        FabricAttachmentStorage(int slotCount,
                                 Supplier<ItemContainerContents> reader,
                                 java.util.function.Consumer<ItemContainerContents> writer,
                                 Runnable dirtyMarker) {
            this.slotCount = slotCount;
            this.reader = reader;
            this.writer = writer;
            this.dirtyMarker = dirtyMarker;
        }

        @Override public ItemStack getStack(int i) {
            NonNullList<ItemStack> list =
                    StorageAttachments.contentsToList(reader.get(), slotCount);
            return list.get(i);
        }

        @Override public void setStack(int i, ItemStack s) {
            NonNullList<ItemStack> list =
                    StorageAttachments.contentsToList(reader.get(), slotCount);
            list.set(i, s);
            writer.accept(StorageAttachments.listToContents(list));
            dirtyMarker.run();
        }

        @Override public int size() { return slotCount; }
        @Override public void markDirty() { dirtyMarker.run(); }
    }
}
