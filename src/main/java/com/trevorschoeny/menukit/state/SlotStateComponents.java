package com.trevorschoeny.menukit.state;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.ApiStatus;

/**
 * Library-owned data component(s) for M1 per-slot state — the §0048
 * block-portable bridge.
 *
 * <h3>{@link #PORTABLE_SLOT_STATE} — the generic block↔item travel vehicle</h3>
 *
 * Post-1.20.5, only <em>declared data components</em> travel block↔item; a
 * Fabric attachment (where M1 metadata lives) does not. So per-slot metadata on
 * a shulker would fall off the moment the box is broken. This component is the
 * bridge: it carries the <em>whole</em> per-BlockEntity M1 bag
 * ({@link PerPlayerSlotStateBag}) — every registered channel's per-slot, per-
 * player state — and a mixin on the shulker block entity emits it at
 * {@code collectImplicitComponents} (BE → item) and restores it at
 * {@code applyImplicitComponents} (item → BE), riding vanilla's own
 * component-travel path (the same one the container contents use).
 *
 * <p><b>Generic, never per-feature (§0048 / §0019).</b> The bag round-trips
 * through one {@link PerPlayerSlotStateBag#CODEC}, so the component carries
 * whatever channels are present with no schema of its own. A lock flag is a
 * consumer-registered M1 channel that lands in this bag like any other — the
 * library ships the <em>travel mechanism</em>; consumers own <em>what travels</em>.
 * There is deliberately no {@code menukit:slot_locks} here.
 *
 * <p><b>Persistent only.</b> It travels on disk and with the item. Network sync
 * is deferred: a client-facing block-portable viewer would sync only the
 * viewing player's slice, not the whole per-player bag — a viewer-time concern,
 * not a travel concern.
 *
 * <p>The M1 Fabric attachment stays source-of-truth (live enforcement + world
 * save, {@link SlotStateAttachments#BLOCK_ENTITY}); the component is travel-only.
 */
@ApiStatus.Internal
public final class SlotStateComponents {

    private SlotStateComponents() {}

    /** Generic block-portable M1 metadata bridge component (§0048). */
    public static final DataComponentType<PerPlayerSlotStateBag> PORTABLE_SLOT_STATE =
            Registry.register(
                    BuiltInRegistries.DATA_COMPONENT_TYPE,
                    Identifier.fromNamespaceAndPath("menukit", "portable_slot_state"),
                    DataComponentType.<PerPlayerSlotStateBag>builder()
                            .persistent(PerPlayerSlotStateBag.CODEC)
                            .build());

    /**
     * Triggers class loading so the static field above registers. Called from
     * common init (both sides) — data-component types must be registered
     * consistently on client and server.
     */
    public static void register() {
        // No-op body — registration is driven by the static field.
    }
}
