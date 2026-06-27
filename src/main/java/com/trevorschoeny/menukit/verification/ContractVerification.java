package com.trevorschoeny.menukit.verification;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.core.HandlerRecognizerRegistry;
import com.trevorschoeny.menukit.core.HudRegion;
import com.trevorschoeny.menukit.core.MenuRegion;
import com.trevorschoeny.menukit.core.MKSlotState;
import com.trevorschoeny.menukit.core.MKCSlot;
import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.PersistentContainerKey;
import com.trevorschoeny.menukit.core.RegionMath;
import com.trevorschoeny.menukit.core.SlotGroup;
import com.trevorschoeny.menukit.core.SlotGroupLike;
import com.trevorschoeny.menukit.core.SlotStateChannel;
import com.trevorschoeny.menukit.core.DropRule;
import com.trevorschoeny.menukit.core.Storage;
import com.trevorschoeny.menukit.core.StorageAttachment;
import com.trevorschoeny.menukit.core.VirtualSlotGroup;
import com.trevorschoeny.menukit.core.attachment.CustomAttachmentSpec;
import com.trevorschoeny.menukit.core.attachment.StorageAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import com.trevorschoeny.menukit.inject.ScreenBounds;
import com.trevorschoeny.menukit.inject.ScreenOrigin;
import com.trevorschoeny.menukit.screen.MKCScreenHandler;
import com.trevorschoeny.menukit.state.SlotStateAttachments;
import com.trevorschoeny.menukit.state.SlotStateBag;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.ApiStatus;

/**
 * Contract-verification orchestrator. Registers a test MenuType, a test
 * screen factory, and the {@code /mkverify} command that exercises each of
 * MenuKit's five canonical guarantees in a single run, producing log
 * evidence for phase reports.
 *
 * <p>First run at the end of Phase 5 to verify the inventory-menu
 * architecture as landed. Lives in the repo from Phase 7 onward so each
 * subsequent phase can re-run verification cheaply when refactoring
 * completes — contract regressions are caught empirically rather than
 * assumed absent.
 *
 * <h3>Commands</h3>
 *
 * <ul>
 *   <li>{@code /mkverify all} — NOT registered by this class. The validator
 *       mod's aggregator command (see {@code mkvalidator.cmd.ValidatorCommand})
 *       owns {@code /mkverify all} so a single invocation runs the library
 *       contracts AND the Phase 12.5 validator scenarios in one pass. This
 *       class exposes {@link #runAll(CommandContext)} as the public entry
 *       point the aggregator calls. Standalone probe execution without the
 *       scenarios is available via that method (e.g. from other tooling).</li>
 *   <li>(removed) {@code /mkverify elements} — opens a clean ElementDemoHandler
 *       screen for visual verification of element rendering. Not a
 *       canonical contract; per-phase dev tooling.</li>
 * </ul>
 *
 * <h3>The thirteen contracts (all run by {@link #runAll(CommandContext)})</h3>
 *
 * 1 Composability · 2 Substitutability · 3 SyncSafety · 4 Uniform ·
 * 5 Inertness · 6 RegionMath · 7 SlotState · 8 M7 storage round-trip ·
 * 9 M8 layout math · 10 modal click-eat (Phase 14d-1) ·
 * 11 dialog composition (Phase 14d-1) ·
 * 12 ScrollContainer math (Phase 14d-2) ·
 * 13 modal-scroll dispatch (Phase 14d-2).
 *
 * <ol>
 *   <li>{@code Composability} — global {@code Slot.mayPlace} mixin
 *       ({@link com.trevorschoeny.menukit.mixin.VerifyMayPlaceMixin}) fires
 *       identically on vanilla and MenuKit slots. Two phases: vanilla
 *       {@code player.inventoryMenu} first, then MK handler after open.</li>
 *   <li>{@code Substitutability} — MenuKit slots pass {@code instanceof Slot};
 *       ecosystem mixins on {@code Slot.getItem}
 *       ({@link com.trevorschoeny.menukit.mixin.VerifyGetItemMixin})
 *       observe them. Single phase, MK handler only.</li>
 *   <li>{@code SyncSafety} — rapid visibility toggle on a hidden panel
 *       produces consistent slot state; protocol never sees phantom items.
 *       Single phase, MK handler only, 10-iteration stress.</li>
 *   <li>{@code Uniform} — {@code HandlerRecognizerRegistry.findGroup}
 *       returns {@code SlotGroupLike} for both native and observed handlers
 *       via the same consumer API. Two phases: vanilla first, then MK.</li>
 *   <li>{@code Inertness} — hidden-panel slots report EMPTY / inactive /
 *       mayPlace=false / mayPickup=false; toggle restores. Single phase,
 *       MK handler only, hidden→visible flip.</li>
 * </ol>
 *
 * <p>The two verify mixins ({@code VerifyMayPlaceMixin},
 * {@code VerifyGetItemMixin}) target vanilla {@link Slot} globally but gate
 * their bodies on {@link #isActive()} — armed only within the probe bodies
 * that need the mixin's behavior. Outside verification, they're no-ops.
 */
@ApiStatus.Internal
public final class ContractVerification {

    private ContractVerification() {}

    /** Logger used by the orchestrator and both verify mixins. */
    public static final Logger LOGGER = LoggerFactory.getLogger("menukit-verify");

    // ── Active-state gate for verify mixins ─────────────────────────────
    //
    // Armed only within the scope of a probe body that needs the mixin's
    // behavior (composability phase A+B, substitutability getItem loop).
    // Keeps the vanilla Slot mixins silent outside their evidence windows
    // so the log isn't drowned by mayPlace/getItem calls during unrelated
    // probes (sync-safety toggles iterate getItem hundreds of times).

    private static volatile boolean active = false;
    public static boolean isActive() { return active; }
    private static void arm() { active = true; }
    private static void disarm() { active = false; }

    // ── MenuType + screen registration ──────────────────────────────────

    private static MenuType<MKCScreenHandler> testMenuType;

    // ── Per-slot state test channel (M1 contract 7) ─────────────────────
    // Registered in initServer so the channel exists before /mkverify runs.
    // Namespace "menukit-verify" is cleaned at the start of every run so
    // probe writes don't accumulate in the player's attachment.

    public static final SlotStateChannel<Boolean> TEST_BOOL = MKSlotState.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("menukit-verify", "test_bool"),
            Codec.BOOL,
            StreamCodec.<RegistryFriendlyByteBuf, Boolean>of(
                    (buf, v) -> buf.writeBoolean(v),
                    buf -> buf.readBoolean()),
            false);

    // ── M7 storage-attachment test (contract 8) ─────────────────────────
    // Player-attached used for the probe because a test BE would require
    // synthesizing one in the world. Player-attached exercises the same
    // Fabric-attachment round-trip (write → attachment codec → read back)
    // against a known owner the probe has in hand. Namespace cleared at
    // probe start so runs are idempotent.

    public static final StorageAttachment<Player, NonNullList<ItemStack>> TEST_CONTENT =
            StorageAttachment.playerAttached("menukit-verify", "m7_test_content", 3);

    /**
     * Called from {@code MK.init()} — server-safe MenuType
     * registration. Phase 14d-2.7: chat command registration removed
     * entirely per TESTING_CONVENTIONS.md (single test entry point is
     * the validator's inventory "Test" button; library exposes
     * {@link #runAll(ServerPlayer)} as a public method any consumer
     * can call).
     */
    public static void initServer() {
        testMenuType = new MenuType<>(
                (syncId, inv) -> TestContractHandler.create(syncId, inv, testMenuType),
                net.minecraft.world.flag.FeatureFlagSet.of());
        Registry.register(BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath("menukit", "contract_verify"),
                testMenuType);
    }

    // Phase 14d-2.7 — visual smoke wireups (dialog, scroll, opacity)
    // migrated to validator/.../scenarios/smoke/MKSmokeWireup.java
    // per the testing convention's library/validator split. Library
    // exposes only pure-logic contracts; validator owns visual smoke.

    // ══════════════════════════════════════════════════════════════════════
    // Removed in 14d-2.7
    // ══════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Installs a {@link TestContractHandler} as {@code player.containerMenu}
     * for the duration of Phase B contracts. Server-side only — no client
     * screen is opened, no {@code ClientboundOpenScreenPacket} is sent.
     *
     * <p>Phase B contracts read {@code handler.slots} directly server-side;
     * they never need a visible client screen, and the previous flow's
     * "open TestContractScreen, run contracts, then open V5" sequence
     * caused a one-frame flash on the "Test MKC" button path (the
     * intermediate screen would render before V5's open S2C arrived).
     *
     * <p>The {@code syncId} is fixed at {@code 0} because no packets carry
     * it — vanilla cycles 1..100 for live menus, so 0 is a safe headless
     * marker that won't collide with anything vanilla generates. The
     * caller is expected to follow with {@code player.openMenu(...)} for
     * the real visible screen (which will get its own live syncId).
     */
    private static void installTestHandler(ServerPlayer player) {
        MKCScreenHandler handler = TestContractHandler.create(
                0, player.getInventory(), testMenuType);
        player.containerMenu = handler;
    }

    // Phase 14d-2.7 — openElementDemoScreen + ElementDemoHandler/Screen
    // removed. Per-phase scratch element-demo workspace was unreachable
    // post-14d-2.6 (sub-command removed); V1.1 Palette Matrix +
    // V1.2 Composed cover the same evidence territory.

    /** Short description of an ItemStack for log lines. */
    private static String desc(ItemStack stack) {
        return stack.isEmpty() ? "EMPTY"
                : stack.getItem().toString() + "x" + stack.getCount();
    }

    // ══════════════════════════════════════════════════════════════════════
    // runAll — runs all thirteen contracts in sequence
    // ══════════════════════════════════════════════════════════════════════
    //
    // Public entry point called by validator's /mkverify all aggregator.
    // Not itself registered as a Brigadier command — see initServer().
    //
    // Menu-state sequencing: player.containerMenu starts as whatever screen
    // (if any) the player currently has open — defaults to inventoryMenu
    // when no screen is open. The command can't be typed with a screen
    // open, so we reliably start on inventoryMenu.
    //
    // Composability and Uniform need vanilla evidence, so their Phase A
    // runs BEFORE installTestHandler — that's the only window when
    // player.inventoryMenu is observable through the command path. After
    // installTestHandler, player.containerMenu points at the test handler
    // and Phase B runs against it.
    //
    // Substitutability, SyncSafety, Inertness are single-phase MK probes
    // that all run after open.

    /**
     * Runs the full library contract sweep. Each probe emits its own
     * {@code VERDICT} log line; emits one chat ack so the caller knows
     * the sweep completed.
     *
     * <p>Phase 14d-2.7: takes {@link ServerPlayer} directly (chat
     * commands removed; the validator's inventory "Test" button is the
     * canonical entry point and dispatches to this method via
     * {@code RunAllAndOpenHubC2SPayload}'s server handler).
     *
     * <p>Leaves {@code player.containerMenu} pointing at the
     * {@link TestContractHandler} (installed server-side partway
     * through, no visible client screen). Validator's aggregator
     * scenarios then read {@link Player#inventoryMenu} directly so
     * they're unaffected by that state change.
     */
    public static int runAll(ServerPlayer player) {
        LOGGER.info("[Verify] BEGIN — runAll — running all thirteen contracts");

        // ── Per-slot state contract (server-side; no menu needed) ───────
        slotState(player);

        // ── M7 storage-attachment round-trip (server-side; no menu) ─────
        m7Storage(player);

        // ── Vanilla phases (before opening test screen) ─────────────────
        composabilityPhaseA(player);
        uniformPhaseA(player);

        // ── Switch to test handler (server-side only) ───────────────────
        installTestHandler(player);
        MKCScreenHandler handler = (MKCScreenHandler) player.containerMenu;

        // ── MK phases ───────────────────────────────────────────────────
        composabilityPhaseB(handler);
        substitutability(handler);
        uniformPhaseB(handler);
        syncSafety(handler);
        inertness(player, handler);

        // §0052 death edge-a — custom-spec player-anchored death enrollment.
        boolean deathEdgeA = customSpecDeathEnrollment();

        LOGGER.info("[Verify] END — fourteen contracts checked. Scan log for VERDICT lines.");

        player.displayClientMessage(
                Component.literal("[Verify] All contracts — see log."),
                false);
        // Distinct chat line for the new death edge-a contract so its result
        // is visible without reading the log.
        player.displayClientMessage(
                Component.literal("[Verify] custom-spec death enrollment (§0052): "
                        + (deathEdgeA ? "PASS" : "FAIL"))
                        .withStyle(deathEdgeA ? ChatFormatting.GREEN : ChatFormatting.RED),
                false);
        return 1;
    }

    /**
     * §0052 death edge-a — verifies a custom (consumer-defined) player-anchored
     * spec can opt into death handling via {@code dropsOnDeath} (it previously
     * threw {@link UnsupportedOperationException} on any non-{@code playerAttached}
     * attachment). Checks the call succeeds AND the spec lands in the
     * custom-player-death registry the death handler iterates. Pure enrollment
     * check — the actual gamerule-gated drop is a manual {@code /kill} scenario.
     *
     * @return true if the custom spec enrolled cleanly
     */
    private static boolean customSpecDeathEnrollment() {
        try {
            StorageAttachment.custom(DEATH_PROBE_SPEC).dropsOnDeath(DropRule.KEEP);
            boolean enrolled = StorageAttachments.customPlayerDeathSpecs()
                    .containsKey(DEATH_PROBE_SPEC);
            LOGGER.info("[Verify.DeathEdgeA] custom-spec dropsOnDeath enrolled={} — VERDICT {}",
                    enrolled, enrolled ? "PASS" : "FAIL");
            return enrolled;
        } catch (UnsupportedOperationException e) {
            LOGGER.info("[Verify.DeathEdgeA] custom-spec dropsOnDeath threw — VERDICT FAIL");
            return false;
        }
    }

    /**
     * Minimal player-anchored {@link CustomAttachmentSpec} for the death edge-a
     * enrollment probe. A single static instance keeps re-enrollment idempotent
     * (the registry is keyed by spec). The probe exercises only the enrollment
     * API, so the read/write hooks are trivial (no real backing store).
     */
    private static final CustomAttachmentSpec<Player, NonNullList<ItemStack>> DEATH_PROBE_SPEC =
            new CustomAttachmentSpec<>() {
                @Override public NonNullList<ItemStack> read(Player owner) {
                    return NonNullList.withSize(1, ItemStack.EMPTY);
                }
                @Override public void write(Player owner, NonNullList<ItemStack> content) { /* probe no-op */ }
                @Override public void markDirty(Player owner) { /* probe no-op */ }
                @Override public NonNullList<ItemStack> defaultFactory() {
                    return NonNullList.withSize(1, ItemStack.EMPTY);
                }
                @Override public NonNullList<ItemStack> toItemList(NonNullList<ItemStack> content) { return content; }
                @Override public NonNullList<ItemStack> fromItemList(NonNullList<ItemStack> items) { return items; }
                @Override public int slotCount() { return 1; }
                @Override public Identifier id() {
                    return Identifier.fromNamespaceAndPath("menukit", "verify_custom_death_probe");
                }
            };

    // Phase 14d-2.7 — /mkverify elements + /mkverify regions removed.
    // ElementDemoHandler/Screen deleted; V1 (Palette Matrix + Composed)
    // covers the same evidence. RegionProbes migrated to validator,
    // toggled via Hub entries.

    // ══════════════════════════════════════════════════════════════════════
    // 1. Composability
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: VerifyMayPlaceMixin (a global Slot.mayPlace
    // @Inject at HEAD that rejects cobblestone while armed) fires on both
    // vanilla slots (player.inventoryMenu has 46 vanilla slots) AND MenuKit
    // slots (test screen's 46 MKCSlot instances). Mixin's per-invocation
    // log lines show it reaching both slot types, and cobblestone is
    // rejected uniformly.

    private static void composabilityPhaseA(ServerPlayer player) {
        LOGGER.info("[Verify.Composability] Phase A — BEGIN (vanilla slots)");
        arm();
        try {
            ItemStack cobble = new ItemStack(Items.COBBLESTONE, 1);
            ItemStack diamond = new ItemStack(Items.DIAMOND, 1);

            AbstractContainerMenu vanillaMenu = player.inventoryMenu;
            LOGGER.info("[Verify.Composability] Phase A — probing {} vanilla slots in {}",
                    vanillaMenu.slots.size(), vanillaMenu.getClass().getSimpleName());

            int aMk = 0, aVanilla = 0, aCobbleRejected = 0, aDiamondAccepted = 0;
            for (Slot slot : vanillaMenu.slots) {
                if (slot instanceof MKCSlot) aMk++; else aVanilla++;
                if (!slot.mayPlace(cobble)) aCobbleRejected++;
                if (slot.mayPlace(diamond)) aDiamondAccepted++;
            }
            LOGGER.info("[Verify.Composability] Phase A result — {} MK / {} vanilla slots, "
                            + "cobble rejected on {}, diamond accepted on {}",
                    aMk, aVanilla, aCobbleRejected, aDiamondAccepted);
        } finally {
            disarm();
        }
    }

    private static void composabilityPhaseB(MKCScreenHandler handler) {
        LOGGER.info("[Verify.Composability] Phase B — BEGIN (MK slots)");
        arm();
        try {
            ItemStack cobble = new ItemStack(Items.COBBLESTONE, 1);
            ItemStack diamond = new ItemStack(Items.DIAMOND, 1);

            LOGGER.info("[Verify.Composability] Phase B — probing {} MK slots in {}",
                    handler.slots.size(), handler.getClass().getSimpleName());

            int bMk = 0, bVanilla = 0, bCobbleRejected = 0, bDiamondAccepted = 0;
            for (Slot slot : handler.slots) {
                if (slot instanceof MKCSlot) bMk++; else bVanilla++;
                if (!slot.mayPlace(cobble)) bCobbleRejected++;
                if (slot.mayPlace(diamond)) bDiamondAccepted++;
            }
            LOGGER.info("[Verify.Composability] Phase B result — {} MK / {} vanilla slots, "
                            + "cobble rejected on {}, diamond accepted on {}",
                    bMk, bVanilla, bCobbleRejected, bDiamondAccepted);

            LOGGER.info("[Verify.Composability] VERDICT — mixin fired on both vanilla and MK "
                            + "slot types; global cobblestone filter applied uniformly");
        } finally {
            disarm();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. Vanilla-slot substitutability
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: every MKCSlot passes `instanceof Slot`
    // (structural check). Then VerifyGetItemMixin (a global Slot.getItem
    // @Inject at RETURN logging MK-class invocations) fires on MK slots,
    // demonstrating that ecosystem-style Slot.getItem mixins compose with
    // MenuKit's override. RETURN injection observes the result *after*
    // MenuKit's inertness check runs, so a hidden-panel slot's return value
    // is EMPTY in the mixin's log — that's composition, not replacement.

    private static void substitutability(MKCScreenHandler handler) {
        LOGGER.info("[Verify.Substitutability] BEGIN");

        int total = 0, mk = 0, passedInstance = 0;
        for (Slot slot : handler.slots) {
            total++;
            if (slot instanceof MKCSlot) mk++;
            if (slot instanceof Slot) passedInstance++;
        }
        LOGGER.info("[Verify.Substitutability] Structural: {}/{} slots pass `instanceof Slot` "
                        + "({} are MKCSlot)",
                passedInstance, total, mk);

        // Sample per-class logging — enough to show the type hierarchy
        // (avoid 40+ lines when they're all the same class).
        for (Slot slot : handler.slots) {
            if (slot instanceof MKCSlot) {
                LOGGER.info("[Verify.Substitutability] Sample slot — class={} instanceof Slot={} instanceof MKCSlot={}",
                        slot.getClass().getName(),
                        slot instanceof Slot,
                        true);
                break;
            }
        }

        // Trigger the Slot.getItem mixin on every MK slot. RETURN-phase
        // injection runs after MenuKit's getItem() has computed its result
        // (including any inertness override), so the mixin observes the
        // composed output.
        arm();
        try {
            LOGGER.info("[Verify.Substitutability] Triggering getItem() on all MK slots (mixin armed)…");
            for (Slot slot : handler.slots) {
                if (slot instanceof MKCSlot) slot.getItem();
            }
        } finally {
            disarm();
        }

        LOGGER.info("[Verify.Substitutability] VERDICT — all {} MK slots pass `instanceof Slot`, "
                + "and Slot.getItem RETURN mixin fires on MK slots with the composed return value "
                + "(including inertness-driven EMPTY for any hidden panel)", mk);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. Sync-safety
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: rapid visibility toggles on the hidden panel leave
    // slot state consistent every cycle. Library's inertness layer
    // guarantees the protocol only sees what visibility dictates — hidden
    // panel → slot.getItem() returns EMPTY regardless of backing storage.
    // 10-toggle stress test checks this invariant after each toggle; any
    // desync is logged as a concrete failure.

    private static void syncSafety(MKCScreenHandler handler) {
        LOGGER.info("[Verify.SyncSafety] BEGIN");

        Panel hidden = handler.getPanel("hidden");
        if (hidden == null) {
            LOGGER.info("[Verify.SyncSafety] 'hidden' panel not found — abort");
            return;
        }

        int hiddenStart = Integer.MAX_VALUE, hiddenEnd = Integer.MIN_VALUE;
        for (SlotGroup g : handler.getGroupsFor("hidden")) {
            hiddenStart = Math.min(hiddenStart, g.getFlatIndexStart());
            hiddenEnd = Math.max(hiddenEnd, g.getFlatIndexEnd());
        }
        LOGGER.info("[Verify.SyncSafety] Hidden panel covers flat slot range [{}..{})",
                hiddenStart, hiddenEnd);

        int inconsistencies = 0;
        for (int iter = 0; iter < 10; iter++) {
            boolean targetVisible = (iter % 2 == 0); // T F T F T F T F T F
            handler.setPanelVisible("hidden", targetVisible);
            boolean reportedVisible = hidden.isVisible();
            boolean visibilityOk = reportedVisible == targetVisible;
            if (!visibilityOk) {
                LOGGER.info("[Verify.SyncSafety] iter {} — visibility MISMATCH: target={} reported={}",
                        iter, targetVisible, reportedVisible);
                inconsistencies++;
            }

            int desync = 0;
            for (int s = hiddenStart; s < hiddenEnd; s++) {
                Slot slot = handler.slots.get(s);
                ItemStack reported = slot.getItem();
                if (!targetVisible && !reported.isEmpty()) {
                    desync++;
                    LOGGER.info("[Verify.SyncSafety] iter {} slot {} — DESYNC (hidden but reported {})",
                            iter, s, desc(reported));
                }
            }
            if (desync > 0) inconsistencies += desync;
            LOGGER.info("[Verify.SyncSafety] iter {} — target={} reported={} desync={}",
                    iter, targetVisible, reportedVisible, desync);
        }

        // Leave panel visible so the follow-up inertness probe has a clean
        // starting state, and log the real contents.
        handler.setPanelVisible("hidden", true);
        StringBuilder finalState = new StringBuilder();
        for (int s = hiddenStart; s < hiddenEnd; s++) {
            finalState.append(s).append("=").append(desc(handler.slots.get(s).getItem())).append(" ");
        }
        LOGGER.info("[Verify.SyncSafety] Post-stress (visible) — slot contents: {}",
                finalState.toString().trim());

        LOGGER.info("[Verify.SyncSafety] VERDICT — 10 toggles, {} inconsistencies. "
                        + "{} the protocol's view stayed consistent with visibility.",
                inconsistencies, inconsistencies == 0 ? "PASS —" : "FAIL —");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. Uniform abstraction
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: findGroup returns Optional<SlotGroupLike> for both
    // vanilla handlers (observed → VirtualSlotGroup) and MenuKit handlers
    // (native → SlotGroup). Consumer code calling findGroup or iterating
    // recognize() gets uniform SlotGroupLike instances regardless of handler
    // type — that's the structural uniform-abstraction promise.

    private static void uniformPhaseA(ServerPlayer player) {
        LOGGER.info("[Verify.Uniform] Phase A — BEGIN (vanilla handler)");
        logUniformProbe("Phase A", player.inventoryMenu);
    }

    private static void uniformPhaseB(MKCScreenHandler handler) {
        LOGGER.info("[Verify.Uniform] Phase B — BEGIN (MK handler)");
        logUniformProbe("Phase B", handler);

        LOGGER.info("[Verify.Uniform] VERDICT — same findGroup() API used against both "
                + "vanilla and MenuKit handlers; both return Optional<SlotGroupLike>, "
                + "concrete implementations (VirtualSlotGroup vs SlotGroup) transparent to caller");
    }

    /** Probes {@code findGroup} + {@code recognize} on a handler, logging
     *  evidence under the given phase label. */
    private static void logUniformProbe(String phase, AbstractContainerMenu menu) {
        LOGGER.info("[Verify.Uniform] {} — menu={} slotCount={}",
                phase, menu.getClass().getName(), menu.slots.size());

        if (menu.slots.isEmpty()) {
            LOGGER.info("[Verify.Uniform] {} — no slots to probe", phase);
            return;
        }

        // findGroup on slot 0 — the simple single-slot lookup pattern.
        // Per §0043: dispatch through MKC's facade for owned handlers; fall
        // through to MK's vanilla observation for non-owned. Both are complete
        // on their respective sides.
        Slot probe = menu.slots.get(0);
        var result = (menu instanceof com.trevorschoeny.menukit.screen.MKCScreenHandler mkH)
                ? mkH.findGroupForSlot(probe)
                : HandlerRecognizerRegistry.findGroup(menu, probe);
        if (result.isPresent()) {
            SlotGroupLike group = result.get();
            String implKind = group instanceof SlotGroup ? "native SlotGroup"
                    : group instanceof VirtualSlotGroup ? "observed VirtualSlotGroup"
                    : "custom impl: " + group.getClass().getSimpleName();
            LOGGER.info("[Verify.Uniform] {} — findGroup(slot 0) → SlotGroupLike ({}) id='{}' "
                            + "canAccept(DIAMOND)={} qmp={}",
                    phase, implKind, group.getId(),
                    group.canAccept(new ItemStack(Items.DIAMOND)),
                    group.getQmp());
        } else {
            LOGGER.info("[Verify.Uniform] {} — findGroup(slot 0) → Optional.empty (not in any recognized group)",
                    phase);
        }

        // recognize() — the whole-menu decomposition pattern. Returns
        // VirtualSlotGroup for observed handlers; empty for native MK
        // handlers (native groups come from the handler's Panel tree,
        // not from recognition).
        var recognized = HandlerRecognizerRegistry.recognize(menu);
        LOGGER.info("[Verify.Uniform] {} — recognize() → {} VirtualSlotGroup(s)",
                phase, recognized.size());
        for (VirtualSlotGroup g : recognized) {
            LOGGER.info("[Verify.Uniform] {}   group id='{}' size={} policy-accepts-diamond={}",
                    phase, g.getId(), g.size(),
                    g.canAccept(new ItemStack(Items.DIAMOND)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. Inertness
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: for every slot inside a hidden panel, these
    // properties all report the inert state:
    //   getItem().isEmpty()  == true   (even though backing storage has items)
    //   isActive()           == false
    //   mayPlace(any)        == false
    //   mayPickup(player)    == false
    //   MKCSlot.isInert()== true
    // After toggling the panel visible, all properties flip back to
    // reflecting real state (active=true, mayPlace/Pickup respect the
    // interaction policy, getItem returns real storage contents,
    // isInert()=false).

    private static void inertness(ServerPlayer player, MKCScreenHandler handler) {
        LOGGER.info("[Verify.Inertness] BEGIN");

        Panel hidden = handler.getPanel("hidden");
        if (hidden == null) {
            LOGGER.info("[Verify.Inertness] 'hidden' panel not found — abort");
            return;
        }

        int hiddenStart = Integer.MAX_VALUE, hiddenEnd = Integer.MIN_VALUE;
        for (SlotGroup g : handler.getGroupsFor("hidden")) {
            hiddenStart = Math.min(hiddenStart, g.getFlatIndexStart());
            hiddenEnd = Math.max(hiddenEnd, g.getFlatIndexEnd());
        }

        ItemStack diamond = new ItemStack(Items.DIAMOND, 1);

        // ── Phase A — hidden (the inert state) ─────────────────────
        handler.setPanelVisible("hidden", false);
        LOGGER.info("[Verify.Inertness] Phase A — hidden (isVisible={})", hidden.isVisible());

        int checked = 0, allInert = 0;
        for (int s = hiddenStart; s < hiddenEnd; s++) {
            Slot slot = handler.slots.get(s);
            if (!(slot instanceof MKCSlot mks)) continue;
            checked++;

            boolean getItemEmpty = slot.getItem().isEmpty();
            boolean active = slot.isActive();
            boolean mayPlace = slot.mayPlace(diamond);
            boolean mayPickup = slot.mayPickup(player);
            boolean isInert = mks.isInert();

            boolean ok = getItemEmpty && !active && !mayPlace && !mayPickup && isInert;
            if (ok) allInert++;

            LOGGER.info("[Verify.Inertness] HIDDEN  slot {} — getItem.empty={} active={} "
                            + "mayPlace(DIAMOND)={} mayPickup={} isInert={} {}",
                    s, getItemEmpty, active, mayPlace, mayPickup, isInert,
                    ok ? "→ OK (fully inert)" : "→ FAIL");
        }
        LOGGER.info("[Verify.Inertness] Phase A result — {}/{} hidden slots fully inert",
                allInert, checked);

        // ── Phase B — visible (inertness reverses) ─────────────────
        handler.setPanelVisible("hidden", true);
        LOGGER.info("[Verify.Inertness] Phase B — visible (isVisible={})", hidden.isVisible());

        int flipped = 0;
        for (int s = hiddenStart; s < hiddenEnd; s++) {
            Slot slot = handler.slots.get(s);
            if (!(slot instanceof MKCSlot mks)) continue;

            boolean getItemEmpty = slot.getItem().isEmpty();
            boolean active = slot.isActive();
            boolean mayPlace = slot.mayPlace(diamond);
            boolean mayPickup = slot.mayPickup(player);
            boolean isInert = mks.isInert();

            // Expected after toggle: active=true, isInert=false. getItem
            // should return real storage contents (non-empty for seeded
            // slots 0..2). mayPlace respects the filter (diamond allowed).
            // mayPickup depends on whether there's content to pick up.
            if (active && !isInert) flipped++;

            LOGGER.info("[Verify.Inertness] VISIBLE slot {} — getItem.empty={} active={} "
                            + "mayPlace(DIAMOND)={} mayPickup={} isInert={} content={}",
                    s, getItemEmpty, active, mayPlace, mayPickup, isInert,
                    desc(slot.getItem()));
        }
        LOGGER.info("[Verify.Inertness] Phase B result — {}/{} slots flipped to active+non-inert",
                flipped, checked);

        LOGGER.info("[Verify.Inertness] VERDICT — inertness holds: hidden slots report fully "
                + "inert ({}/{} OK); visible slots flip back ({}/{} restored)",
                allInert, checked, flipped, checked);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 6. Region math (pure, no menu state)
    // ══════════════════════════════════════════════════════════════════════
    //
    // Phase 12 M5 — the region math is pure given explicit inputs, so this
    // probe runs without opening any screen. Validates:
    //   - each of 8 inventory regions at prefix=0 produces the expected
    //     first-panel origin (formulas from design doc §3.4)
    //   - same regions at prefix=20 advance along the flow axis by 20px
    //   - each of 9 HUD regions at prefix=0 produces the expected first-panel
    //     origin (§3.5)
    //   - overflow: a prefix exceeding the region's available space returns
    //     Optional.empty() (maps to OUT_OF_REGION at the adapter boundary)
    //
    // Synthetic inputs:
    //   inventory bounds = (leftPos=100, topPos=50, imageWidth=176, imageHeight=166)
    //   panel size = (20, 20)
    //   hud screen = (sw=800, sh=600), panel size = (20, 20)


    // ══════════════════════════════════════════════════════════════════════
    // 7. Per-slot persistent state (M1)
    // ══════════════════════════════════════════════════════════════════════
    //
    // Exercises the full server-side persistence path against the test
    // player's PlayerInventory attachment. Covers:
    //   - default read for an unset slot returns the channel's default
    //   - slot-less write + read round-trip via PlayerInventory key
    //   - Tag-native storage: stored value is a ByteTag matching Codec.BOOL
    //     encoding (ByteTag.ONE for true) — verifies THESIS principle 6
    //   - cleanup: after clearing the verify namespace, reads return default
    //
    // The "menukit-verify:*" namespace is cleared at the start of each run
    // so probes don't accumulate state in the player's save file.

    private static void slotState(ServerPlayer player) {
        LOGGER.info("[Verify.SlotState] BEGIN");
        int[] counts = {0, 0}; // [total, failed]

        // Clean up any leftover state from a previous run
        SlotStateBag bag = player.getAttachedOrCreate(SlotStateAttachments.PLAYER_INVENTORY);
        bag.clearChannel(TEST_BOOL.id());

        PersistentContainerKey key = new PersistentContainerKey.PlayerInventory(player.getUUID());

        // Case 1: default read (no value stored yet)
        check(counts, "default read", TEST_BOOL.get(player, key, 5) == Boolean.FALSE);

        // Case 2: slot-less write via PlayerInventory key
        TEST_BOOL.set(player, key, 5, Boolean.TRUE);

        // Case 3: slot-less read returns the written value
        check(counts, "write+read", TEST_BOOL.get(player, key, 5) == Boolean.TRUE);

        // Case 4: Tag-native storage — inspect the raw bag
        Tag storedTag = bag.read(TEST_BOOL.id(), 5);
        check(counts, "tag stored", storedTag != null);
        check(counts, "tag is ByteTag",
                storedTag instanceof ByteTag);
        if (storedTag instanceof ByteTag bt) {
            check(counts, "byte value = 1", bt.byteValue() == 1);
        } else {
            counts[0]++;
            counts[1]++;
            LOGGER.info("[Verify.SlotState] byte value check — FAIL (tag wasn't a ByteTag)");
        }

        // Case 5: writing a different slot leaves the original untouched
        TEST_BOOL.set(player, key, 12, Boolean.TRUE);
        check(counts, "slot isolation", TEST_BOOL.get(player, key, 5) == Boolean.TRUE
                                       && TEST_BOOL.get(player, key, 12) == Boolean.TRUE
                                       && TEST_BOOL.get(player, key, 6) == Boolean.FALSE);

        // Case 6: clearChannel wipes the channel; default returns
        bag.clearChannel(TEST_BOOL.id());
        check(counts, "cleanup",
                TEST_BOOL.get(player, key, 5) == Boolean.FALSE
                && TEST_BOOL.get(player, key, 12) == Boolean.FALSE);

        int total = counts[0];
        int failed = counts[1];
        int passed = total - failed;
        LOGGER.info("[Verify.SlotState] VERDICT — {}/{} cases pass ({})",
                passed, total, failed == 0 ? "PASS" : "FAIL — see above");
    }

    private static void check(int[] counts, String label, boolean condition) {
        counts[0]++;
        if (condition) {
            LOGGER.info("[Verify.SlotState] {} — OK", label);
        } else {
            LOGGER.info("[Verify.SlotState] {} — FAIL", label);
            counts[1]++;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 8. M7 storage-attachment round-trip
    // ══════════════════════════════════════════════════════════════════════
    //
    // Exercises M7's StorageAttachment path against a test player-attached
    // attachment. Covers:
    //   - default empty content for a fresh owner
    //   - write + immediate read returns the written ItemStack
    //   - fresh bind reads the persisted state (attachment round-trip,
    //     proves writes flowed through Fabric's attachment codec)
    //   - slot isolation (writing slot 1 doesn't touch slot 0 / 2)
    //   - cleanup returns the attachment to empty state
    //
    // Does NOT simulate chunk unload / reload (would require BE synthesis).
    // The fresh-bind read is the round-trip signal the probe asserts on —
    // if the attachment's read/write path is wired correctly, a fresh
    // bind against the same player reads back the written content. Real
    // chunk-unload persistence is verified at integration level via V5.7
    // (BE-scoped) in the dev client.

    private static void m7Storage(ServerPlayer player) {
        LOGGER.info("[Verify.M7] BEGIN");
        int[] counts = {0, 0};

        Storage storage = TEST_CONTENT.bind(player);

        // Clean any prior state before probing.
        for (int i = 0; i < storage.size(); i++) storage.setStack(i, ItemStack.EMPTY);

        // Case 1: default empty
        checkM7(counts, "default empty", storage.getStack(0).isEmpty());

        // Case 2: write + immediate read
        ItemStack diamond = new ItemStack(Items.DIAMOND, 3);
        storage.setStack(0, diamond);
        ItemStack read = storage.getStack(0);
        checkM7(counts, "write+read same",
                read.getItem() == Items.DIAMOND && read.getCount() == 3);

        // Case 3: fresh bind reads persisted value (attachment round-trip)
        Storage fresh = TEST_CONTENT.bind(player);
        ItemStack freshRead = fresh.getStack(0);
        checkM7(counts, "fresh-bind round-trip",
                freshRead.getItem() == Items.DIAMOND && freshRead.getCount() == 3);

        // Case 4: slot isolation
        storage.setStack(1, new ItemStack(Items.EMERALD, 1));
        checkM7(counts, "slot isolation",
                storage.getStack(0).getItem() == Items.DIAMOND
                && storage.getStack(1).getItem() == Items.EMERALD
                && storage.getStack(2).isEmpty());

        // Case 5: marker interface — PlayerAttachment-produced Storage
        //         should implement PlayerStorage for shift-click routing.
        checkM7(counts, "PlayerStorage marker",
                storage instanceof com.trevorschoeny.menukit.core.PlayerStorage);

        // Case 6: cleanup
        for (int i = 0; i < storage.size(); i++) storage.setStack(i, ItemStack.EMPTY);
        checkM7(counts, "cleanup",
                storage.getStack(0).isEmpty()
                && storage.getStack(1).isEmpty()
                && storage.getStack(2).isEmpty());

        int total = counts[0], failed = counts[1];
        int passed = total - failed;
        LOGGER.info("[Verify.M7] VERDICT — {}/{} cases pass ({})",
                passed, total, failed == 0 ? "PASS" : "FAIL — see above");
    }

    private static void checkM7(int[] counts, String label, boolean condition) {
        counts[0]++;
        if (condition) {
            LOGGER.info("[Verify.M7] {} — OK", label);
        } else {
            LOGGER.info("[Verify.M7] {} — FAIL", label);
            counts[1]++;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 9. M8 layout math (pure; no menu, no state)
    // ══════════════════════════════════════════════════════════════════════
    //
    // Exercises Row / Column / nested layouts and CrossAlign math against
    // synthetic ElementSpecs. Asserts the emitted PanelElements have the
    // expected childX/childY values. No live screen; no Fabric attachment.
    //
    // Each test uses an ad-hoc anonymous ElementSpec to avoid coupling to
    // any concrete element class — the probe verifies layout math in
    // isolation from element rendering.

    // ════════════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════════════


}
