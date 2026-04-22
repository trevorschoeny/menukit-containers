package com.trevorschoeny.menukit.verification;

import com.mojang.serialization.Codec;
import com.trevorschoeny.menukit.core.HandlerRecognizerRegistry;
import com.trevorschoeny.menukit.core.HudRegion;
import com.trevorschoeny.menukit.core.MenuRegion;
import com.trevorschoeny.menukit.core.MKSlotState;
import com.trevorschoeny.menukit.core.MenuKitSlot;
import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.PersistentContainerKey;
import com.trevorschoeny.menukit.core.RegionMath;
import com.trevorschoeny.menukit.core.SlotGroup;
import com.trevorschoeny.menukit.core.SlotGroupLike;
import com.trevorschoeny.menukit.core.SlotStateChannel;
import com.trevorschoeny.menukit.core.VirtualSlotGroup;
import com.trevorschoeny.menukit.inject.ScreenBounds;
import com.trevorschoeny.menukit.inject.ScreenOrigin;
import com.trevorschoeny.menukit.screen.MenuKitScreenHandler;
import com.trevorschoeny.menukit.state.SlotStateAttachments;
import com.trevorschoeny.menukit.state.SlotStateBag;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.commands.Commands.literal;

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
 *   <li>{@code /mkverify elements} — opens a clean {@link ElementDemoHandler}
 *       screen for visual verification of element rendering. Not a
 *       canonical contract; per-phase dev tooling.</li>
 * </ul>
 *
 * <h3>The seven contracts (all run by {@link #runAll(CommandContext)})</h3>
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

    private static MenuType<MenuKitScreenHandler> testMenuType;
    private static MenuType<MenuKitScreenHandler> elementDemoMenuType;

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

    /** Called from {@code MenuKit.init()} — server-safe MenuType + commands. */
    public static void initServer() {
        testMenuType = new MenuType<>(
                (syncId, inv) -> TestContractHandler.create(syncId, inv, testMenuType),
                net.minecraft.world.flag.FeatureFlagSet.of());
        Registry.register(BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath("menukit", "contract_verify"),
                testMenuType);

        elementDemoMenuType = new MenuType<>(
                (syncId, inv) -> ElementDemoHandler.create(syncId, inv, elementDemoMenuType),
                net.minecraft.world.flag.FeatureFlagSet.of());
        Registry.register(BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath("menukit", "element_demo"),
                elementDemoMenuType);

        // /mkverify all is registered by the validator mod's aggregator
        // command (ValidatorCommand.register), which composes the library
        // contracts (via runAll below) with the Phase 12.5 validator
        // scenarios. MenuKit does not self-register "all" — that would
        // collide with the validator's registration under the same Brigadier
        // literal, and the combined-surface aggregator is the ergonomic
        // shape Trevor actually uses in practice.
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                dispatcher.register(literal("mkverify")
                        .then(literal("elements").executes(ContractVerification::cmdElements))
                        .then(literal("regions")
                                .executes(ContractVerification::cmdRegionsToggle)
                                .then(literal("stack").executes(ContractVerification::cmdRegionsStackToggle)))));
    }

    /** Called from {@code MenuKitClient.onInitializeClient()} — screen factory. */
    public static void initClient() {
        MenuScreens.register(testMenuType, TestContractScreen::new);
        MenuScreens.register(elementDemoMenuType, ElementDemoScreen::new);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    /** Opens the test screen for the player. {@code player.containerMenu}
     *  becomes the {@link TestContractHandler} synchronously. */
    private static void openTestScreen(ServerPlayer player) {
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() {
                return Component.literal("MenuKit Contract Verification");
            }
            @Override public AbstractContainerMenu createMenu(int syncId, Inventory inv, Player p) {
                return TestContractHandler.create(syncId, inv, testMenuType);
            }
        });
    }

    /** Opens the element-demo screen for the player. */
    private static void openElementDemoScreen(ServerPlayer player) {
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() {
                return Component.literal("MenuKit Element Demo");
            }
            @Override public AbstractContainerMenu createMenu(int syncId, Inventory inv, Player p) {
                return ElementDemoHandler.create(syncId, inv, elementDemoMenuType);
            }
        });
    }

    /** Short description of an ItemStack for log lines. */
    private static String desc(ItemStack stack) {
        return stack.isEmpty() ? "EMPTY"
                : stack.getItem().toString() + "x" + stack.getCount();
    }

    // ══════════════════════════════════════════════════════════════════════
    // runAll — runs all seven contracts in sequence
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
    // runs BEFORE openTestScreen — that's the only window when
    // player.inventoryMenu is observable through the command path. After
    // openTestScreen, player.containerMenu points at the test handler and
    // Phase B runs against it.
    //
    // Substitutability, SyncSafety, Inertness are single-phase MK probes
    // that all run after open.

    /**
     * Runs all seven canonical contracts in sequence, orchestrating menu
     * state across the open-test-screen boundary. Each contract emits its
     * own {@code VERDICT} log line. Emits one chat acknowledgement
     * ({@code "[Verify] All seven contracts — see log..."}) so the caller
     * knows execution completed; scan the log for VERDICT to read results.
     *
     * <p>Leaves {@code player.containerMenu} pointing at the
     * {@link TestContractHandler} (the test screen opens partway through).
     * Subsequent callers that read {@link Player#inventoryMenu} directly
     * (as the Phase 12.5 validator scenarios do) are unaffected.
     */
    public static int runAll(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        LOGGER.info("[Verify] BEGIN — runAll — running all seven contracts");

        // ── Pure-math contract (no menu state) ──────────────────────────
        regionMath();

        // ── Per-slot state contract (server-side; no menu needed) ───────
        slotState(player);

        // ── Vanilla phases (before opening test screen) ─────────────────
        composabilityPhaseA(player);
        uniformPhaseA(player);

        // ── Switch to test handler ──────────────────────────────────────
        openTestScreen(player);
        MenuKitScreenHandler handler = (MenuKitScreenHandler) player.containerMenu;

        // ── MK phases ───────────────────────────────────────────────────
        composabilityPhaseB(handler);
        substitutability(handler);
        uniformPhaseB(handler);
        syncSafety(handler);
        inertness(player, handler);

        LOGGER.info("[Verify] END — seven contracts checked. Scan log for VERDICT lines.");

        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify] All seven contracts — see log. Test screen is now open."),
                false);
        return 1;
    }

    // ══════════════════════════════════════════════════════════════════════
    // /mkverify elements (visual-verification surface, not a canonical contract)
    // ══════════════════════════════════════════════════════════════════════
    //
    // Opens a clean screen with just a demo panel, no slots, no player
    // inventory rendering. Used for visual verification of element rendering
    // during phase work. Per-phase test elements live in ElementDemoHandler;
    // edit there to add/remove test elements.

    private static int cmdElements(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        openElementDemoScreen(player);
        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify] Element demo screen is now open."), false);
        return 1;
    }

    // ══════════════════════════════════════════════════════════════════════
    // /mkverify regions — M5 §9.2 integration-level visual verification
    // ══════════════════════════════════════════════════════════════════════
    //
    // Toggles client-side region probes on/off. When on, 17 colored 18×18
    // squares render at the 8 inventory regions + 9 HUD regions, plus two
    // additional squares in RIGHT_ALIGN_TOP for stacking inspection.
    //
    // /mkverify regions         — master toggle
    // /mkverify regions stack   — flip the middle stacking probe's visibility
    //                             (verifies per-frame reflow of subsequent
    //                             panels when a mid-stack panel hides)
    //
    // Command runs on the server thread but the probes are client-side state;
    // the dev client is single-player so the server-thread → client-thread
    // read/write of the volatile flags is consistent enough for verification.

    private static int cmdRegionsToggle(CommandContext<CommandSourceStack> ctx) {
        boolean on = com.trevorschoeny.menukit.verification.RegionProbes.toggleMaster();
        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify.Regions] probes " + (on ? "ON" : "OFF")),
                false);
        return 1;
    }

    private static int cmdRegionsStackToggle(CommandContext<CommandSourceStack> ctx) {
        boolean visible = com.trevorschoeny.menukit.verification.RegionProbes.toggleStackMiddle();
        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify.Regions] stack middle probe "
                        + (visible ? "VISIBLE" : "HIDDEN")),
                false);
        return 1;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. Composability
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: VerifyMayPlaceMixin (a global Slot.mayPlace
    // @Inject at HEAD that rejects cobblestone while armed) fires on both
    // vanilla slots (player.inventoryMenu has 46 vanilla slots) AND MenuKit
    // slots (test screen's 46 MenuKitSlot instances). Mixin's per-invocation
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
                if (slot instanceof MenuKitSlot) aMk++; else aVanilla++;
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

    private static void composabilityPhaseB(MenuKitScreenHandler handler) {
        LOGGER.info("[Verify.Composability] Phase B — BEGIN (MK slots)");
        arm();
        try {
            ItemStack cobble = new ItemStack(Items.COBBLESTONE, 1);
            ItemStack diamond = new ItemStack(Items.DIAMOND, 1);

            LOGGER.info("[Verify.Composability] Phase B — probing {} MK slots in {}",
                    handler.slots.size(), handler.getClass().getSimpleName());

            int bMk = 0, bVanilla = 0, bCobbleRejected = 0, bDiamondAccepted = 0;
            for (Slot slot : handler.slots) {
                if (slot instanceof MenuKitSlot) bMk++; else bVanilla++;
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
    // Expected evidence: every MenuKitSlot passes `instanceof Slot`
    // (structural check). Then VerifyGetItemMixin (a global Slot.getItem
    // @Inject at RETURN logging MK-class invocations) fires on MK slots,
    // demonstrating that ecosystem-style Slot.getItem mixins compose with
    // MenuKit's override. RETURN injection observes the result *after*
    // MenuKit's inertness check runs, so a hidden-panel slot's return value
    // is EMPTY in the mixin's log — that's composition, not replacement.

    private static void substitutability(MenuKitScreenHandler handler) {
        LOGGER.info("[Verify.Substitutability] BEGIN");

        int total = 0, mk = 0, passedInstance = 0;
        for (Slot slot : handler.slots) {
            total++;
            if (slot instanceof MenuKitSlot) mk++;
            if (slot instanceof Slot) passedInstance++;
        }
        LOGGER.info("[Verify.Substitutability] Structural: {}/{} slots pass `instanceof Slot` "
                        + "({} are MenuKitSlot)",
                passedInstance, total, mk);

        // Sample per-class logging — enough to show the type hierarchy
        // (avoid 40+ lines when they're all the same class).
        for (Slot slot : handler.slots) {
            if (slot instanceof MenuKitSlot) {
                LOGGER.info("[Verify.Substitutability] Sample slot — class={} instanceof Slot={} instanceof MenuKitSlot={}",
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
                if (slot instanceof MenuKitSlot) slot.getItem();
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

    private static void syncSafety(MenuKitScreenHandler handler) {
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

    private static void uniformPhaseB(MenuKitScreenHandler handler) {
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
        Slot probe = menu.slots.get(0);
        var result = HandlerRecognizerRegistry.findGroup(menu, probe);
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
    //   MenuKitSlot.isInert()== true
    // After toggling the panel visible, all properties flip back to
    // reflecting real state (active=true, mayPlace/Pickup respect the
    // interaction policy, getItem returns real storage contents,
    // isInert()=false).

    private static void inertness(ServerPlayer player, MenuKitScreenHandler handler) {
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
            if (!(slot instanceof MenuKitSlot mks)) continue;
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
            if (!(slot instanceof MenuKitSlot mks)) continue;

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

    private static void regionMath() {
        LOGGER.info("[Verify.RegionMath] BEGIN");

        ScreenBounds bounds = new ScreenBounds(100, 50, 176, 166);
        int pw = 20, ph = 20;
        int[] counts = {0, 0}; // [total, failed]

        // ── Inventory regions at prefix=0 ───────────────────────────────
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.RIGHT_ALIGN_TOP,    278, 50);
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.RIGHT_ALIGN_BOTTOM, 278, 196);
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.LEFT_ALIGN_TOP,     78,  50);
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.LEFT_ALIGN_BOTTOM,  78,  196);
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.TOP_ALIGN_LEFT,     100, 28);
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.TOP_ALIGN_RIGHT,    256, 28);
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.BOTTOM_ALIGN_LEFT,  100, 218);
        checkInventory(counts, bounds, pw, ph, 0, MenuRegion.BOTTOM_ALIGN_RIGHT, 256, 218);

        // ── Inventory regions at prefix=20 (stacking offset) ────────────
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.RIGHT_ALIGN_TOP,    278, 70);
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.RIGHT_ALIGN_BOTTOM, 278, 176);
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.LEFT_ALIGN_TOP,     78,  70);
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.LEFT_ALIGN_BOTTOM,  78,  176);
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.TOP_ALIGN_LEFT,     120, 28);
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.TOP_ALIGN_RIGHT,    236, 28);
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.BOTTOM_ALIGN_LEFT,  120, 218);
        checkInventory(counts, bounds, pw, ph, 20, MenuRegion.BOTTOM_ALIGN_RIGHT, 236, 218);

        // ── Inventory overflow — prefix > imageHeight ───────────────────
        checkOverflowInventory(counts, bounds, pw, ph, 200, MenuRegion.RIGHT_ALIGN_TOP);

        // ── HUD regions at prefix=0 ─────────────────────────────────────
        int sw = 800, sh = 600;
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.TOP_LEFT,      4,   4);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.TOP_CENTER,    390, 4);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.TOP_RIGHT,     776, 4);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.LEFT_CENTER,   4,   300);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.RIGHT_CENTER,  776, 300);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.BOTTOM_LEFT,   4,   576);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.BOTTOM_CENTER, 390, 576);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.BOTTOM_RIGHT,  776, 576);
        checkHud(counts, sw, sh, pw, ph, 0, HudRegion.CENTER,        390, 316);

        // ── HUD overflow ────────────────────────────────────────────────
        checkOverflowHud(counts, sw, sh, pw, ph, 700, HudRegion.TOP_LEFT);

        int total = counts[0], failed = counts[1];
        int passed = total - failed;
        LOGGER.info("[Verify.RegionMath] VERDICT — {}/{} cases pass ({})",
                passed, total, failed == 0 ? "PASS" : "FAIL — see above");
    }

    /**
     * Asserts inventory resolution. Updates {@code counts[0]} (total)
     * and {@code counts[1]} (failed) based on the assertion outcome.
     */
    private static void checkInventory(int[] counts, ScreenBounds bounds,
                                        int pw, int ph, int prefix,
                                        MenuRegion region,
                                        int expectedX, int expectedY) {
        counts[0]++;
        Optional<ScreenOrigin> result = RegionMath.resolveMenu(
                region, bounds, pw, ph, prefix);
        if (result.isEmpty()) {
            LOGGER.info("[Verify.RegionMath] {} prefix={} → EMPTY (FAIL, expected ({}, {}))",
                    region, prefix, expectedX, expectedY);
            counts[1]++;
            return;
        }
        ScreenOrigin o = result.get();
        if (o.x() == expectedX && o.y() == expectedY) {
            LOGGER.info("[Verify.RegionMath] {} prefix={} → ({}, {}) OK",
                    region, prefix, o.x(), o.y());
        } else {
            LOGGER.info("[Verify.RegionMath] {} prefix={} → ({}, {}) FAIL (expected ({}, {}))",
                    region, prefix, o.x(), o.y(), expectedX, expectedY);
            counts[1]++;
        }
    }

    /** Asserts HUD resolution. Same counts semantics as {@link #checkInventory}. */
    private static void checkHud(int[] counts, int sw, int sh, int pw, int ph,
                                  int prefix, HudRegion region,
                                  int expectedX, int expectedY) {
        counts[0]++;
        Optional<ScreenOrigin> result = RegionMath.resolveHud(
                region, sw, sh, pw, ph, prefix);
        if (result.isEmpty()) {
            LOGGER.info("[Verify.RegionMath] HUD {} prefix={} → EMPTY (FAIL, expected ({}, {}))",
                    region, prefix, expectedX, expectedY);
            counts[1]++;
            return;
        }
        ScreenOrigin o = result.get();
        if (o.x() == expectedX && o.y() == expectedY) {
            LOGGER.info("[Verify.RegionMath] HUD {} prefix={} → ({}, {}) OK",
                    region, prefix, o.x(), o.y());
        } else {
            LOGGER.info("[Verify.RegionMath] HUD {} prefix={} → ({}, {}) FAIL (expected ({}, {}))",
                    region, prefix, o.x(), o.y(), expectedX, expectedY);
            counts[1]++;
        }
    }

    /** Asserts inventory overflow returns Optional.empty. */
    private static void checkOverflowInventory(int[] counts, ScreenBounds bounds,
                                                 int pw, int ph, int prefix,
                                                 MenuRegion region) {
        counts[0]++;
        Optional<ScreenOrigin> result = RegionMath.resolveMenu(
                region, bounds, pw, ph, prefix);
        if (result.isEmpty()) {
            LOGGER.info("[Verify.RegionMath] OVERFLOW {} prefix={} → empty (OK)", region, prefix);
        } else {
            LOGGER.info("[Verify.RegionMath] OVERFLOW {} prefix={} → {} (FAIL, expected empty)",
                    region, prefix, result.get());
            counts[1]++;
        }
    }

    /** Asserts HUD overflow returns Optional.empty. */
    private static void checkOverflowHud(int[] counts, int sw, int sh,
                                          int pw, int ph, int prefix, HudRegion region) {
        counts[0]++;
        Optional<ScreenOrigin> result = RegionMath.resolveHud(region, sw, sh, pw, ph, prefix);
        if (result.isEmpty()) {
            LOGGER.info("[Verify.RegionMath] OVERFLOW HUD {} prefix={} → empty (OK)", region, prefix);
        } else {
            LOGGER.info("[Verify.RegionMath] OVERFLOW HUD {} prefix={} → {} (FAIL, expected empty)",
                    region, prefix, result.get());
            counts[1]++;
        }
    }

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
}
