package com.trevorschoeny.menukit.core.attachment;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.core.DropRule;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric attachment-type registry for M7. Each consumer-declared
 * {@code StorageAttachment} registers exactly one attachment per owner
 * type with a unique identifier — the library maintains this registry so
 * registrations are idempotent and inspectable.
 *
 * <p>Attachments store {@link ItemContainerContents} (vanilla's codec-backed
 * item-list type) and the Storage surface converts to/from mutable
 * {@link NonNullList} on each read/write. Using ItemContainerContents lets
 * M7 reuse vanilla's codec directly instead of composing a NonNullList codec.
 *
 * <p>Not public API — consumers go through {@code StorageAttachment}'s
 * factories, which call into this registry internally.
 */
public final class StorageAttachments {

    private StorageAttachments() {}

    // Cache attachment types by identifier. Fabric's registry throws on
    // duplicate registration; this cache lets the library return the same
    // type for repeated StorageAttachment declarations with the same id
    // (which is an error elsewhere in a typical mod, but not here — consumers
    // may reload static fields during hot-reload in dev).
    private static final ConcurrentHashMap<Identifier, AttachmentType<ItemContainerContents>> CACHE =
            new ConcurrentHashMap<>();

    // Tracks the per-attachment slot count — needed at drop-on-break time
    // so the library can iterate the attachment's content array correctly.
    private static final ConcurrentHashMap<AttachmentType<ItemContainerContents>, Integer>
            ATTACHMENT_SIZES = new ConcurrentHashMap<>();

    // Block-scoped attachments register here in addition to CACHE. The
    // drop-on-break mixin iterates this list, not CACHE, to skip
    // player-attached / item-attached variants whose lifecycle doesn't
    // involve block destruction. Populated by
    // {@link #registerBlockScopedAttachment(Identifier, int)}.
    private static final java.util.List<AttachmentType<ItemContainerContents>>
            BLOCK_SCOPED_ATTACHMENTS =
                    new java.util.concurrent.CopyOnWriteArrayList<>();

    // Player content attachments enrolled for death handling. Mirrors
    // BLOCK_SCOPED_ATTACHMENTS, but maps each type to its DropRule — mutable, so
    // the consumer can override the policy via StorageAttachment.dropsOnDeath()
    // after registration. (copyOnDeath is already set at registration time, so an
    // override is a plain map update, not a re-registration.) PlayerDeathDropHandler
    // iterates this; Phase 2 grave adapters can reuse the same "death-droppable
    // player slots" surface. Populated by registerPlayerContentAttachment.
    private static final ConcurrentHashMap<AttachmentType<ItemContainerContents>, DropRule>
            PLAYER_DEATH_DROP = new ConcurrentHashMap<>();

    // §0052 completion — custom (consumer-defined) player-anchored content
    // specs enrolled for death handling. A custom spec has no library-owned
    // AttachmentType to set copyOnDeath on (the consumer owns the storage), so
    // it can't sit in PLAYER_DEATH_DROP. The library owns only the
    // gamerule-gated DROP/DESTROY at the death site, reading/writing through the
    // spec; KEEP-across-respawn is the consumer storage's own job (it sets
    // copyOnDeath on its Fabric attachment, or uses a persistent store — the
    // library cannot set copyOnDeath on storage it doesn't own). Keyed by spec
    // so dropsOnDeath() can override the rule post-registration.
    private static final ConcurrentHashMap<CustomAttachmentSpec<?, ?>, DropRule>
            CUSTOM_PLAYER_DEATH_DROP = new ConcurrentHashMap<>();

    /**
     * Register (or retrieve) an attachment storing {@link ItemContainerContents}.
     * Persistence is automatic via Fabric's {@code .persistent(Codec)}.
     *
     * <p>Use this variant for non-block-scoped attachments (player-attached,
     * item-attached, ephemeral, custom). For block-scoped attachments, use
     * {@link #registerBlockScopedAttachment(Identifier, int)} instead —
     * same underlying registration, plus the block-scoped registry entry
     * for drop-on-break dispatch.
     *
     * @param id           the attachment's unique identifier (shows up in NBT paths)
     * @param defaultSlots number of slots in the default empty content
     */
    public static AttachmentType<ItemContainerContents> registerContainerAttachment(
            Identifier id, int defaultSlots) {
        AttachmentType<ItemContainerContents> type = CACHE.computeIfAbsent(id, k ->
                AttachmentRegistry.<ItemContainerContents>builder()
                        .persistent(ItemContainerContents.CODEC)
                        .initializer(() -> defaultEmpty(defaultSlots))
                        .buildAndRegister(k));
        ATTACHMENT_SIZES.putIfAbsent(type, defaultSlots);
        return type;
    }

    /**
     * Register (or retrieve) a PLAYER content attachment — like
     * {@link #registerContainerAttachment(Identifier, int)} but with Fabric's
     * {@code copyOnDeath()} flag (so a KEPT bag carries across respawn) plus
     * enrollment in the player death-drop registry at {@link DropRule#DEFAULT}
     * (zero-config vanilla parity). The death handler
     * ({@link PlayerDeathDropHandler}) iterates the registry and owns the
     * {@code keepInventory} check; the consumer overrides the per-attachment rule
     * via {@code StorageAttachment.dropsOnDeath(...)}. Called by
     * {@link com.trevorschoeny.menukit.core.StorageAttachment#playerAttached(String, String, int)}.
     */
    public static AttachmentType<ItemContainerContents> registerPlayerContentAttachment(
            Identifier id, int defaultSlots) {
        AttachmentType<ItemContainerContents> type = CACHE.computeIfAbsent(id, k ->
                AttachmentRegistry.<ItemContainerContents>builder()
                        .persistent(ItemContainerContents.CODEC)
                        .copyOnDeath()
                        .initializer(() -> defaultEmpty(defaultSlots))
                        .buildAndRegister(k));
        ATTACHMENT_SIZES.putIfAbsent(type, defaultSlots);
        PLAYER_DEATH_DROP.putIfAbsent(type, DropRule.DEFAULT);
        return type;
    }

    /**
     * Register a block-scoped attachment. Same as
     * {@link #registerContainerAttachment(Identifier, int)} plus enrollment
     * in the block-scoped registry that the drop-on-break mixin dispatches
     * against. Called by
     * {@link com.trevorschoeny.menukit.core.StorageAttachment#blockScoped(String, String, int)}.
     */
    public static AttachmentType<ItemContainerContents> registerBlockScopedAttachment(
            Identifier id, int defaultSlots) {
        AttachmentType<ItemContainerContents> type =
                registerContainerAttachment(id, defaultSlots);
        if (!BLOCK_SCOPED_ATTACHMENTS.contains(type)) {
            BLOCK_SCOPED_ATTACHMENTS.add(type);
        }
        return type;
    }

    /** Snapshot of all registered block-scoped attachments, for drop-on-break iteration. */
    public static java.util.List<AttachmentType<ItemContainerContents>> blockScopedAttachments() {
        return java.util.List.copyOf(BLOCK_SCOPED_ATTACHMENTS);
    }

    /**
     * Override the death-drop rule for a player content attachment. Called by
     * {@code StorageAttachment.dropsOnDeath(...)} — a plain map update, valid
     * post-registration because {@code copyOnDeath} was already set when the
     * attachment registered.
     */
    public static void setDeathDropRule(AttachmentType<ItemContainerContents> type, DropRule rule) {
        PLAYER_DEATH_DROP.put(type, rule);
    }

    /** The configured death-drop rule for a type, or {@code null} if it isn't death-enrolled. */
    public static DropRule deathDropRuleOf(AttachmentType<ItemContainerContents> type) {
        return PLAYER_DEATH_DROP.get(type);
    }

    /** Snapshot of all death-droppable player content attachments, for the death handler. */
    public static java.util.Set<AttachmentType<ItemContainerContents>> playerDeathDropAttachments() {
        return java.util.Set.copyOf(PLAYER_DEATH_DROP.keySet());
    }

    /**
     * Enroll a player-anchored custom spec for death handling at {@code rule}
     * (or override the rule for an already-enrolled spec). Called by
     * {@code StorageAttachment.customPlayerAttached(spec).dropsOnDeath(rule)}. The
     * owner type is statically {@code Player} (the typed factory enforces it), so a
     * non-player spec can never reach this. The library owns the gamerule-gated
     * DROP/DESTROY; the consumer's storage owns KEEP-across-respawn survival.
     */
    public static void registerCustomPlayerDeathSpec(CustomAttachmentSpec<?, ?> spec, DropRule rule) {
        CUSTOM_PLAYER_DEATH_DROP.put(spec, rule);
    }

    /** Snapshot of the death-enrolled custom player specs + their rules, for the death handler. */
    public static java.util.Map<CustomAttachmentSpec<?, ?>, DropRule> customPlayerDeathSpecs() {
        return java.util.Map.copyOf(CUSTOM_PLAYER_DEATH_DROP);
    }

    /** Slot count for a registered attachment — used at drop-on-break to size the list. */
    public static int slotCountOf(AttachmentType<ItemContainerContents> type) {
        Integer size = ATTACHMENT_SIZES.get(type);
        return size == null ? 0 : size;
    }

    /**
     * The Identifier a content attachment was registered under, or null if it
     * isn't in the cache. Used by the §0052 Phase 2 capture surface to tag a
     * captured slot with a stable, serializable id (for grave round-trips).
     */
    public static @Nullable Identifier identifierOf(AttachmentType<ItemContainerContents> type) {
        for (var e : CACHE.entrySet()) {
            if (e.getValue() == type) return e.getKey();
        }
        return null;
    }

    /** The content attachment registered under {@code id}, or null. The capture-surface restore path. */
    public static @Nullable AttachmentType<ItemContainerContents> typeById(Identifier id) {
        return CACHE.get(id);
    }

    /** Produces an empty {@link ItemContainerContents} of the given size. */
    public static ItemContainerContents defaultEmpty(int slotCount) {
        NonNullList<ItemStack> empty = NonNullList.withSize(slotCount, ItemStack.EMPTY);
        return ItemContainerContents.fromItems(empty);
    }

    /**
     * Converts {@link ItemContainerContents} to a mutable {@link NonNullList}.
     * Every call allocates a fresh list — callers mutate their copy and write
     * back via {@link #listToContents}.
     */
    public static NonNullList<ItemStack> contentsToList(ItemContainerContents contents,
                                                         int slotCount) {
        NonNullList<ItemStack> list = NonNullList.withSize(slotCount, ItemStack.EMPTY);
        if (contents != null) contents.copyInto(list);
        return list;
    }

    /** Converts a {@link NonNullList} to an {@link ItemContainerContents}. */
    public static ItemContainerContents listToContents(NonNullList<ItemStack> list) {
        return ItemContainerContents.fromItems(list);
    }
}
