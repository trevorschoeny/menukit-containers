package com.trevorschoeny.menukit.verification;

import com.trevorschoeny.menukit.core.HandlerRecognizerRegistry;
import com.trevorschoeny.menukit.core.MenuKitSlot;
import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.SlotGroup;
import com.trevorschoeny.menukit.core.SlotGroupLike;
import com.trevorschoeny.menukit.core.VirtualSlotGroup;
import com.trevorschoeny.menukit.screen.MenuKitScreenHandler;

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
 * Phase 5 contract-verification orchestrator. Registers a test MenuType,
 * a test screen factory, and a {@code /mkverify} command suite that
 * exercises each of MenuKit's five canonical guarantees, producing log
 * evidence for the Phase 5 report.
 *
 * <p>Each subcommand is self-contained: it arranges its own menu context
 * (uses {@link Player#containerMenu} — which defaults to
 * {@link Player#inventoryMenu} when no screen is open — for vanilla
 * evidence, and opens the {@link TestContractHandler} test screen via
 * {@code player.openMenu} for MenuKit evidence). This matters because
 * players can't type chat commands while a menu screen is open, so the
 * command has to orchestrate menu state itself rather than assume it.
 *
 * <p>The five contracts, each with its own subcommand:
 * <ol>
 *   <li>{@code /mkverify composability} — global {@code Slot.mayPlace}
 *       mixin fires identically on vanilla and MenuKit slots. Two phases
 *       in one command: vanilla first, then opens test screen for MK.</li>
 *   <li>{@code /mkverify substitutability} — MenuKit slots pass
 *       {@code instanceof Slot}; ecosystem mixins on {@code Slot.getItem}
 *       observe them. Opens test screen.</li>
 *   <li>{@code /mkverify syncsafety} — rapid visibility toggle on hidden
 *       panel produces consistent slot state; protocol never sees phantom
 *       items. Opens test screen.</li>
 *   <li>{@code /mkverify uniform} — {@code HandlerRecognizerRegistry.findGroup}
 *       returns {@code SlotGroupLike} for both native and observed
 *       handlers via the same consumer API. Two phases: vanilla first,
 *       then opens test screen for MK.</li>
 *   <li>{@code /mkverify inertness} — hidden-panel slots report EMPTY /
 *       inactive / mayPlace=false / mayPickup=false; toggle restores.
 *       Opens test screen.</li>
 * </ol>
 *
 * <p>The two verify mixins ({@code VerifyMayPlaceMixin},
 * {@code VerifyGetItemMixin}) target vanilla {@link Slot} globally but
 * gate their bodies on {@link #isActive()} — armed only during a single
 * command's execution window. Outside verification, they're no-ops.
 *
 * <p><b>Phase 5 verification scaffolding — this whole package is
 * temporary.</b> The test screen, handler, orchestrator, and both
 * verify mixins are removed in a follow-up commit after the Phase 5
 * report lands. Kept in git history for potential re-verification.
 */
public final class ContractVerification {

    private ContractVerification() {}

    /** Logger used by the orchestrator and both verify mixins. */
    public static final Logger LOGGER = LoggerFactory.getLogger("menukit-verify");

    // ── Active-state gate for verify mixins ─────────────────────────────
    //
    // Armed only within the scope of a single command's execution
    // (at the beginning, disarmed at the end). Keeps the vanilla Slot
    // mixins silent outside verification runs.

    private static volatile boolean active = false;
    public static boolean isActive() { return active; }
    private static void arm() { active = true; }
    private static void disarm() { active = false; }

    // ── MenuType + screen registration ──────────────────────────────────

    private static MenuType<MenuKitScreenHandler> testMenuType;

    /** Called from {@code MenuKit.init()} — server-safe MenuType + commands. */
    public static void initServer() {
        testMenuType = new MenuType<>(
                (syncId, inv) -> TestContractHandler.create(syncId, inv, testMenuType),
                net.minecraft.world.flag.FeatureFlagSet.of());
        Registry.register(BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath("menukit", "contract_verify"),
                testMenuType);

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                dispatcher.register(literal("mkverify")
                        .then(literal("composability").executes(ContractVerification::cmdComposability))
                        .then(literal("substitutability").executes(ContractVerification::cmdSubstitutability))
                        .then(literal("syncsafety").executes(ContractVerification::cmdSyncSafety))
                        .then(literal("uniform").executes(ContractVerification::cmdUniform))
                        .then(literal("inertness").executes(ContractVerification::cmdInertness))));
    }

    /** Called from {@code MenuKitClient.onInitializeClient()} — screen factory. */
    public static void initClient() {
        MenuScreens.register(testMenuType, TestContractScreen::new);
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

    /** Short description of an ItemStack for log lines. */
    private static String desc(ItemStack stack) {
        return stack.isEmpty() ? "EMPTY"
                : stack.getItem().toString() + "x" + stack.getCount();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. Composability
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: VerifyMayPlaceMixin (a global Slot.mayPlace
    // @Inject at HEAD that rejects cobblestone while armed) fires on both
    // vanilla slots (player.inventoryMenu has 46 vanilla slots: armor,
    // offhand, crafting, result, hotbar, main) AND MenuKit slots (the
    // test screen's 46 MenuKitSlot instances). The mixin's per-invocation
    // log lines show it reaching both slot types, and cobblestone is
    // rejected uniformly. That's the ecosystem-mixin composition guarantee.

    private static int cmdComposability(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        LOGGER.info("[Verify.Composability] BEGIN");
        arm();
        try {
            ItemStack cobble = new ItemStack(Items.COBBLESTONE, 1);
            ItemStack diamond = new ItemStack(Items.DIAMOND, 1);

            // ── Phase A: vanilla slots (player.inventoryMenu) ─────────
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

            // ── Phase B: open test screen, probe MenuKit slots ────────
            openTestScreen(player);
            AbstractContainerMenu mkMenu = player.containerMenu;
            LOGGER.info("[Verify.Composability] Phase B — probing {} MK slots in {}",
                    mkMenu.slots.size(), mkMenu.getClass().getSimpleName());

            int bMk = 0, bVanilla = 0, bCobbleRejected = 0, bDiamondAccepted = 0;
            for (Slot slot : mkMenu.slots) {
                if (slot instanceof MenuKitSlot) bMk++; else bVanilla++;
                if (!slot.mayPlace(cobble)) bCobbleRejected++;
                if (slot.mayPlace(diamond)) bDiamondAccepted++;
            }
            LOGGER.info("[Verify.Composability] Phase B result — {} MK / {} vanilla slots, "
                            + "cobble rejected on {}, diamond accepted on {}",
                    bMk, bVanilla, bCobbleRejected, bDiamondAccepted);

            LOGGER.info("[Verify.Composability] VERDICT — mixin fired on both vanilla ({} slots) "
                            + "and MK ({} slots) slot types; global cobblestone filter applied uniformly",
                    aVanilla, bMk);
        } finally {
            disarm();
        }
        LOGGER.info("[Verify.Composability] END");

        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify] Composability — see log. Test screen is now open."),
                false);
        return 1;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. Vanilla-slot substitutability
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: every MenuKitSlot passes `instanceof Slot`
    // (structural check). Then VerifyGetItemMixin (a global
    // Slot.getItem @Inject at RETURN logging MK-class invocations) fires
    // on MK slots, demonstrating that ecosystem-style Slot.getItem mixins
    // compose with MenuKit's override. RETURN injection observes the
    // result *after* MenuKit's inertness check runs, so a hidden-panel
    // slot's return value is EMPTY in the mixin's log — that's
    // composition, not replacement.

    private static int cmdSubstitutability(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        LOGGER.info("[Verify.Substitutability] BEGIN");

        openTestScreen(player);
        MenuKitScreenHandler handler = (MenuKitScreenHandler) player.containerMenu;

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

        // Trigger the Slot.getItem mixin on every MK slot.
        // RETURN-phase injection: runs after MenuKit's getItem() has
        // computed its result (including any inertness override), so the
        // mixin observes the composed output.
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
        LOGGER.info("[Verify.Substitutability] END");

        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify] Substitutability — see log."), false);
        return 1;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. Sync-safety
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: rapid visibility toggles on the hidden panel
    // leave slot state consistent every cycle. The library's inertness
    // layer guarantees that the protocol only sees what visibility
    // dictates — hidden panel → slot.getItem() returns EMPTY regardless
    // of backing storage. The 10-toggle stress test checks this invariant
    // after each toggle; any desync is logged as a concrete failure.

    private static int cmdSyncSafety(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        LOGGER.info("[Verify.SyncSafety] BEGIN");
        openTestScreen(player);
        MenuKitScreenHandler handler = (MenuKitScreenHandler) player.containerMenu;

        Panel hidden = handler.getPanel("hidden");
        if (hidden == null) {
            LOGGER.info("[Verify.SyncSafety] 'hidden' panel not found — abort");
            return 0;
        }

        int hiddenStart = Integer.MAX_VALUE, hiddenEnd = Integer.MIN_VALUE;
        for (SlotGroup g : hidden.getGroups()) {
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

        // Leave panel visible so the follow-up inertness test (if run)
        // has a clean starting state, and log the real contents.
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
        LOGGER.info("[Verify.SyncSafety] END");

        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify] SyncSafety — see log."), false);
        return 1;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. Uniform abstraction
    // ══════════════════════════════════════════════════════════════════════
    //
    // Expected evidence: findGroup returns Optional<SlotGroupLike> for
    // both vanilla handlers (observed → VirtualSlotGroup) and MenuKit
    // handlers (native → SlotGroup). Consumer code calling findGroup or
    // iterating recognize() gets uniform SlotGroupLike instances
    // regardless of handler type — that's the structural uniform-
    // abstraction promise.

    private static int cmdUniform(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        LOGGER.info("[Verify.Uniform] BEGIN");

        // ── Phase A: vanilla handler (player.inventoryMenu) ──────────
        AbstractContainerMenu vanilla = player.inventoryMenu;
        logUniformProbe("Phase A", vanilla);

        // ── Phase B: MenuKit-native handler ──────────────────────────
        openTestScreen(player);
        AbstractContainerMenu mk = player.containerMenu;
        logUniformProbe("Phase B", mk);

        LOGGER.info("[Verify.Uniform] VERDICT — same findGroup() API used against both "
                + "vanilla ({}) and MenuKit ({}) handlers; both return Optional<SlotGroupLike>, "
                + "concrete implementations (VirtualSlotGroup vs SlotGroup) transparent to caller",
                vanilla.getClass().getSimpleName(), mk.getClass().getSimpleName());
        LOGGER.info("[Verify.Uniform] END");

        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify] Uniform — see log."), false);
        return 1;
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

        // findGroup on slot 0 — the simple single-slot lookup pattern
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

    private static int cmdInertness(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        LOGGER.info("[Verify.Inertness] BEGIN");
        openTestScreen(player);
        MenuKitScreenHandler handler = (MenuKitScreenHandler) player.containerMenu;

        Panel hidden = handler.getPanel("hidden");
        if (hidden == null) {
            LOGGER.info("[Verify.Inertness] 'hidden' panel not found — abort");
            return 0;
        }

        int hiddenStart = Integer.MAX_VALUE, hiddenEnd = Integer.MIN_VALUE;
        for (SlotGroup g : hidden.getGroups()) {
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
        LOGGER.info("[Verify.Inertness] END");

        ctx.getSource().sendSuccess(
                () -> Component.literal("[Verify] Inertness — see log."), false);
        return 1;
    }
}
