package com.trevorschoeny.menukit.state;

import com.trevorschoeny.menukit.core.PersistentContainerKey;
import com.trevorschoeny.menukit.core.SlotStateChannel;

import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Internal registry for M1 channels + container resolvers. Not public API —
 * consumers reach it indirectly via
 * {@link com.trevorschoeny.menukit.core.MKSlotState}.
 */
public final class SlotStateRegistry {

    private SlotStateRegistry() {}

    // ── Channels ────────────────────────────────────────────────────────

    private static final Map<Identifier, SlotStateChannel<?>> CHANNELS = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> @Nullable SlotStateChannel<T> getChannelTyped(Identifier id) {
        return (SlotStateChannel<T>) CHANNELS.get(id);
    }

    public static @Nullable SlotStateChannel<?> getChannel(Identifier id) {
        return CHANNELS.get(id);
    }

    public static void registerChannel(SlotStateChannel<?> channel) {
        CHANNELS.put(channel.id(), channel);
    }

    public static Iterable<SlotStateChannel<?>> allChannels() {
        return CHANNELS.values();
    }

    // ── Container resolvers ─────────────────────────────────────────────
    //
    // Modded BE / Entity types register custom resolvers. v1 ships no
    // auto-resolution for modded types — explicit opt-in only.

    private static final Map<Class<? extends BlockEntity>,
            Function<? extends BlockEntity, PersistentContainerKey>> BE_RESOLVERS = new LinkedHashMap<>();

    private static final Map<Class<? extends Entity>,
            Function<? extends Entity, PersistentContainerKey>> ENTITY_RESOLVERS = new LinkedHashMap<>();

    public static <T extends BlockEntity> void registerBlockEntityResolver(
            Class<T> clazz, Function<T, PersistentContainerKey> resolver) {
        BE_RESOLVERS.put(clazz, resolver);
    }

    public static <T extends Entity> void registerEntityResolver(
            Class<T> clazz, Function<T, PersistentContainerKey> resolver) {
        ENTITY_RESOLVERS.put(clazz, resolver);
    }

    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> Optional<PersistentContainerKey> resolveBlockEntity(T be) {
        for (Map.Entry<Class<? extends BlockEntity>,
                Function<? extends BlockEntity, PersistentContainerKey>> entry : BE_RESOLVERS.entrySet()) {
            if (entry.getKey().isInstance(be)) {
                Function<T, PersistentContainerKey> fn =
                        (Function<T, PersistentContainerKey>) entry.getValue();
                return Optional.ofNullable(fn.apply(be));
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public static <T extends Entity> Optional<PersistentContainerKey> resolveEntity(T entity) {
        for (Map.Entry<Class<? extends Entity>,
                Function<? extends Entity, PersistentContainerKey>> entry : ENTITY_RESOLVERS.entrySet()) {
            if (entry.getKey().isInstance(entity)) {
                Function<T, PersistentContainerKey> fn =
                        (Function<T, PersistentContainerKey>) entry.getValue();
                return Optional.ofNullable(fn.apply(entity));
            }
        }
        return Optional.empty();
    }

    /**
     * Attempts to resolve a persistent key from a {@link Container}. Returns
     * {@link Optional#empty} for unsupported types (e.g., ephemeral crafting
     * containers, modded types without registered resolvers).
     *
     * <p>Resolution order: player inventory → ender chest → block entity →
     * entity → modded resolvers. First match wins.
     */
    public static Optional<PersistentContainerKey> resolve(Container container) {
        return ContainerKeyResolver.resolve(container);
    }
}
