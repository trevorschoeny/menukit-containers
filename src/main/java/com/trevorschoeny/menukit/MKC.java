package com.trevorschoeny.menukit;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.ApiStatus;

/**
 * Mod entry point for the {@code menukit-containers} artifact —
 * "MenuKit: Containers."
 *
 * <p>Per §0042, MenuKit ships as two artifacts:
 * <ul>
 *   <li><b>MenuKit</b> ({@code menukit}, retains the bare ID; client-only):
 *       HUD panels, widgets, layouts, modal panels, region anchoring on
 *       vanilla menus, click-through prohibition, recipe-book awareness —
 *       the UI library.</li>
 *   <li><b>MenuKit: Containers</b> ({@code menukit-containers}, universal):
 *       custom container menus ({@code MKCScreenHandler}), per-slot
 *       state (M1), slot-group regions, custom payloads, server-coupled
 *       mixins, contract verification harness — the slot extension.</li>
 * </ul>
 *
 * <p>The story: <i>MenuKit is the UI library; MenuKit: Containers adds
 * slots to MenuKit.</i> Consumer mods that build custom container menus
 * (define a {@code MKCScreenHandler} subclass) depend on this artifact
 * and inherit MenuKit's UI surface transitively. Consumer mods that only
 * need HUD/widgets/layouts depend on the MenuKit artifact alone and ship
 * as {@code "environment": "client"}.
 *
 * <p>This class owns common-side init for slot-state machinery — M1
 * attachment registration, networking-payload registration, and the
 * verification harness's server-side init. The companion
 * {@link MKCClient} owns the client-side counterpart.
 *
 * <p>The MenuKit: Containers canonical surface for consumers:
 * <ul>
 *   <li><b>Screens:</b> {@code com.trevorschoeny.menukit.screen.MKCScreenHandler}
 *       + {@code MKCHandledScreen}</li>
 *   <li><b>Slot composition:</b> {@code com.trevorschoeny.menukit.core.SlotGroup},
 *       {@code SlotGroupLike}</li>
 *   <li><b>Observed screens:</b> {@code HandlerRecognizerRegistry},
 *       {@code VirtualSlotGroup}</li>
 *   <li><b>State persistence:</b> {@code com.trevorschoeny.menukit.core.MKSlotState},
 *       {@code com.trevorschoeny.menukit.core.StorageAttachment}</li>
 *   <li><b>Verification:</b>
 *       {@code com.trevorschoeny.menukit.verification.ContractVerification}</li>
 * </ul>
 */
@ApiStatus.Internal
public class MKC implements ModInitializer {

    /** Logger for the MenuKit: Containers artifact — distinct from MenuKit's
     *  own logger so init traces are distinguishable in the log output. */
    public static final Logger LOGGER = LoggerFactory.getLogger("menukit-containers");

    @Override
    public void onInitialize() {
        init();
    }

    /** Common-side initialization. Registers M1 attachment types + shared
     *  networking payloads + the verification harness's server-side hooks.
     *  Must run before any consumer-mod code that references
     *  {@code MKCScreenHandler}, {@code MKSlotState}, or
     *  {@code StorageAttachment}. */
    public static void init() {
        LOGGER.info("[MenuKit-Containers] Initialized");
        // THE ONE WINDOW — install the server tier into MK's engine (DIP: MK owns
        // the ports, MKC conforms). Runs on both sides (universal init): server
        // holds authoritative bindings; client mirrors for prediction.
        com.trevorschoeny.menukit.window.ServerTier.install(
                com.trevorschoeny.menukit.core.BehaviorBindingTable.INSTANCE,
                com.trevorschoeny.menukit.core.BehaviorBindingTable.INSTANCE);
        // §0050-backed menu-independent vanilla-slot identity (so a chest slot
        // gates the same via menu or hopper, both sides).
        com.trevorschoeny.menukit.window.ServerTier.installIdentity(
                com.trevorschoeny.menukit.core.MKCVanillaSlotIdentity.INSTANCE);
        // Server-authoritative reactive firing (ON_INSERT/ON_TAKE). The firing
        // seams that call WindowReactions.fireInsert/fireTake (server tier) are
        // the named owed gap; this makes the dispatch real so wiring a seam later
        // is a single call.
        com.trevorschoeny.menukit.window.ServerTier.installDispatch(
                com.trevorschoeny.menukit.core.ReactiveDispatchImpl.INSTANCE);
        // M1 per-slot state — attachments + shared networking types register
        // here (attachment registration must run on both sides; networking
        // payload-type registration is also symmetric).
        com.trevorschoeny.menukit.state.SlotStateAttachments.register();
        // M1 block-portable bridge component (§0048) — must register on both
        // sides for data-component registry consistency.
        com.trevorschoeny.menukit.state.SlotStateComponents.register();
        com.trevorschoeny.menukit.state.SlotStateHooks.registerCommon();
        com.trevorschoeny.menukit.state.SlotStateHooks.registerServer();
        // Verification harness — registers /mkverify command suite + test
        // MenuType so phase verification can be re-run at any time.
        com.trevorschoeny.menukit.verification.ContractVerification.initServer();

        // MKCMenu turnkey open primitive — the ONE generic open payload + its
        // server receiver, registered once here. The receiver resolves the menu
        // by the id the client sent, hops to the main thread, and opens it. An
        // unknown id is a fail-loud log, never an NPE. This serves every MKCMenu
        // a consumer defines, keyed by the menu's registered id.
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(
                com.trevorschoeny.menukit.network.MKCOpenMenuC2SPayload.TYPE,
                com.trevorschoeny.menukit.network.MKCOpenMenuC2SPayload.STREAM_CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.trevorschoeny.menukit.network.MKCOpenMenuC2SPayload.TYPE,
                (payload, context) -> {
                    net.minecraft.server.level.ServerPlayer player = context.player();
                    com.trevorschoeny.menukit.screen.MKCMenu handle =
                            com.trevorschoeny.menukit.screen.MKCMenu.byId(payload.menuId());
                    if (handle == null) {
                        LOGGER.warn("[MenuKit-Containers] MKCOpenMenu: no MKCMenu registered "
                                + "under id '{}' — ignoring open request from {}",
                                payload.menuId(), player.getName().getString());
                        return;
                    }
                    // Central main-thread hop — menu open must run on the server thread.
                    net.minecraft.server.MinecraftServer srv = player.level().getServer();
                    if (srv != null) srv.execute(() -> handle.open(player));
                });

        // §0052 Phase 2 — grave-mod compat. OPTIONAL: register a capture adapter
        // only when the grave mod is present; MKC hard-depends on none. The
        // adapter class is referenced ONLY inside the guard, so its grave-mod
        // class references never load when the mod is absent — the Phase-1 floor
        // (drop beside the death spot) covers that case. Universal Graves is the
        // shippable 1.21.11 adapter; Pneumono's Gravestones is Loom-version-blocked
        // (floor for now); YIGD has no 1.21.11 build (floor) — see
        // MOD_INTEGRATION_TRACKING.md.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("universal-graves")) {
            com.trevorschoeny.menukit.compat.UniversalGravesAdapter.register();
            LOGGER.info("[MenuKit-Containers] Universal Graves detected — player-slot grave capture enabled");
        }
    }

    /** Client-side initialization. Invoked from
     *  {@link MKCClient#onInitializeClient()}. Registers M1's
     *  client-side networking handlers. */
    public static void initClient() {
        LOGGER.info("[MenuKit-Containers] Client initialized");
        com.trevorschoeny.menukit.state.SlotStateHooks.registerClient();
    }
}
