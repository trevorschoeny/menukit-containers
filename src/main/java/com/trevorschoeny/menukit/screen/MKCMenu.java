package com.trevorschoeny.menukit.screen;

import com.trevorschoeny.menukit.network.MKCOpenMenuC2SPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.MenuType;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * The turnkey custom-menu primitive — one {@code define(...).register()} chain
 * replaces the ~4 plumbing pieces a consumer used to hand-write for every custom
 * {@link MKCScreenHandler} menu (its own {@code MenuType} registration, its own
 * open C2S payload + receiver, its own {@code MenuScreens.register}, its own open
 * call). Modeled on {@code MKCContainerPanel.define(...).register()}.
 *
 * <h3>Recipe</h3>
 *
 * <pre>{@code
 * // Consumer COMMON initializer (runs both sides):
 * public static final MKCMenu CUSTOM = MKCMenu
 *     .define(Identifier.fromNamespaceAndPath(MOD_ID, "custom_menu"), MyMenu::buildHandler)
 *     .title(Component.literal("My Custom Menu"))   // optional; default = "menu.<ns>.<path>"
 *     .screen(MyScreen::new)                         // optional; default MKCHandledScreen::new
 *     .arm(MyMenu::armBehaviors)                     // optional; arm slot behavior by Address, same chain
 *     .register();
 *
 * // Open it:
 * CUSTOM.requestOpen();        // client → sends the one generic open payload
 * CUSTOM.open(serverPlayer);   // server-side direct (no networking)
 * }</pre>
 *
 * <h3>The client/server split</h3>
 *
 * {@link #register} is side-neutral (common init): it builds + registers the
 * {@link MenuType}, stores the title + screen factory + id on the returned handle,
 * and adds the handle to {@link #DEFINITIONS} (so the client can drain it) + the
 * id-keyed {@link #BY_ID} map (so the generic server receiver can resolve it). The
 * screen factory is a {@link MKCMenuScreenFactory} — a never-server-invoked
 * functional interface, materialised into a vanilla screen only on the client from
 * {@link #registerScreens()} (same discipline as {@code MKCContainerPanel.chrome}).
 *
 * <h3>Namespace your panel ids</h3>
 *
 * This menu's {@link #getId()} {@code Identifier} is globally unique, but it does NOT
 * scope the {@link com.trevorschoeny.menukit.window.Address}es of the slots the menu's
 * handler creates — those are keyed globally by {@code (panelId, groupId, localIndex)}
 * (see {@code CreatedSlotAdapter.addressOf}). So the panel ids the handler declares
 * (via {@link MKCScreenHandler#builder}) must be namespaced per-mod — e.g.
 * {@code "mymod:menu:main"} — exactly as container-parity panel ids already are, or two
 * mods that both name a panel {@code "main"} will collide and arm each other's slots.
 * That id is the same one used for layout references and for addressing in
 * {@link Builder#arm}.
 */
public final class MKCMenu {

    // ── Registered definitions ──────────────────────────────────────────
    // DEFINITIONS: drained client-side to register screens (mirror
    // MKCContainerPanel.DEFINITIONS). BY_ID: resolved server-side by the
    // generic open receiver. Both populated by register(), side-neutral.
    private static final List<MKCMenu> DEFINITIONS = new CopyOnWriteArrayList<>();
    private static final Map<Identifier, MKCMenu> BY_ID = new ConcurrentHashMap<>();

    // ── Handle state (frozen after register()) ──────────────────────────
    private final Identifier id;
    private final BiFunction<Integer, Inventory, MKCScreenHandler> handlerFactory;
    private final Component title;
    private final MKCMenuScreenFactory screenFactory;
    private final MenuType<MKCScreenHandler> type;

    private MKCMenu(Identifier id,
                    BiFunction<Integer, Inventory, MKCScreenHandler> handlerFactory,
                    Component title,
                    MKCMenuScreenFactory screenFactory,
                    MenuType<MKCScreenHandler> type) {
        this.id = id;
        this.handlerFactory = handlerFactory;
        this.title = title;
        this.screenFactory = screenFactory;
        this.type = type;
    }

    // ── Definition entry point ──────────────────────────────────────────

    /**
     * Begins a custom-menu definition.
     *
     * @param id             the registry id for the menu's {@link MenuType} (also
     *                       the key the generic open payload carries)
     * @param handlerFactory builds the {@link MKCScreenHandler} from
     *                       {@code (syncId, playerInventory)} — runs identically on
     *                       both sides (server via the menu provider, client via the
     *                       MenuType factory), so storages must be same-size for sync
     */
    public static Builder define(Identifier id,
                                 BiFunction<Integer, Inventory, MKCScreenHandler> handlerFactory) {
        return new Builder(id, handlerFactory);
    }

    /** Fluent configuration; terminates in {@link #register()}. */
    public static final class Builder {
        private final Identifier id;
        private final BiFunction<Integer, Inventory, MKCScreenHandler> handlerFactory;
        private Component title;                                        // null => default translation key
        private MKCMenuScreenFactory screenFactory = MKCHandledScreen::new;
        private @org.jspecify.annotations.Nullable Runnable arm = null; // behavior-arming; run by register()

        Builder(Identifier id, BiFunction<Integer, Inventory, MKCScreenHandler> handlerFactory) {
            this.id = id;
            this.handlerFactory = handlerFactory;
        }

        /** The menu's display title. Optional — defaults to {@code "menu.<namespace>.<path>"}. */
        public Builder title(Component title) {
            this.title = title;
            return this;
        }

        /**
         * The client screen factory. Optional — defaults to {@code MKCHandledScreen::new}.
         * Pass a consumer subclass ({@code MyScreen::new}) to wire per-screen hooks
         * (custom keys / listeners / drag modes) in its {@code init()}. Never invoked
         * on a dedicated server (see {@link MKCMenuScreenFactory}).
         */
        public Builder screen(MKCMenuScreenFactory screenFactory) {
            this.screenFactory = screenFactory;
            return this;
        }

        /**
         * A behavior-arming hook, invoked by {@link #register()} <b>after</b> the
         * {@link MenuType} + id are live, so a custom menu's slot behavior is armed in
         * the same define chain as the menu it belongs to and cannot be forgotten in a
         * separate pass.
         *
         * <p>Arm slot behavior by {@link com.trevorschoeny.menukit.window.Address} here
         * — the menu's created slots are addressable as soon as it is registered
         * ({@code Window.slot(CreatedSlotAdapter.addressOf(panelId, groupId, i)).set(...)}).
         * Side-neutral: it runs at register() time on whichever side called it (the
         * engine declarations are pure data), exactly like arming behavior by hand in
         * common init.
         *
         * <pre>{@code
         * MKCMenu.define(id, MyMenu::buildHandler)
         *     .screen(MyScreen::new)
         *     .arm(() -> {
         *         for (int i = 0; i < 2; i++) {
         *             Window.slot(CreatedSlotAdapter.addressOf("side", "filtered", i))
         *                   .set(MKCBehaviorKeys.GATING, diamondsOnly);
         *         }
         *     })
         *     .register();
         * }</pre>
         */
        public Builder arm(Runnable arm) {
            this.arm = arm;
            return this;
        }

        /**
         * Finalises the registration. Side-neutral (common init): builds + registers
         * the {@link MenuType}, resolves the default title if none was given, stores
         * the handle in {@link #DEFINITIONS} + {@link #BY_ID}, and returns it. Call
         * once at mod init.
         */
        public MKCMenu register() {
            // The MenuType factory: (syncId, inv) -> handler. Used by the client to
            // reconstruct the menu from the server's open packet.
            MenuType<MKCScreenHandler> type = new MenuType<>(
                    (syncId, inv) -> handlerFactory.apply(syncId, inv),
                    FeatureFlagSet.of());
            Registry.register(BuiltInRegistries.MENU, id, type);

            Component resolvedTitle = (title != null)
                    ? title
                    : Component.translatable("menu." + id.getNamespace() + "." + id.getPath());

            MKCMenu handle = new MKCMenu(id, handlerFactory, resolvedTitle, screenFactory, type);
            DEFINITIONS.add(handle);
            BY_ID.put(id, handle);

            // Behavior-arming runs last, now that the MenuType + id are live and the
            // menu's created slots are addressable (see Builder.arm). Keeping it on
            // the define chain means it can't be forgotten in a separate pass.
            if (arm != null) {
                arm.run();
            }
            return handle;
        }
    }

    // ── Handle accessors ────────────────────────────────────────────────

    /** This menu's registered {@link MenuType}. */
    public MenuType<MKCScreenHandler> getType() { return type; }

    /** This menu's registry id (also the open-payload key). */
    public Identifier getId() { return id; }

    /** The screen factory (client-only; see {@link MKCMenuScreenFactory}). */
    MKCMenuScreenFactory screenFactory() { return screenFactory; }

    // ── Open ────────────────────────────────────────────────────────────

    /**
     * Opens this menu for the given server player — server-side direct, no
     * networking. Vanilla syncs the open to the client, which rebuilds the menu
     * through this menu's {@link MenuType} and shows the registered screen.
     */
    public void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inv, p) -> handlerFactory.apply(syncId, inv),
                title));
    }

    /**
     * Requests the server open this menu for the local player — client-side.
     * Sends the one library-owned generic open payload carrying this menu's id;
     * the server receiver (registered in {@code MKC.init()}) resolves the handle
     * and calls {@link #open(ServerPlayer)} on the main thread.
     */
    public void requestOpen() {
        ClientPlayNetworking.send(new MKCOpenMenuC2SPayload(id));
    }

    // ── Generic server receiver (called from MKC.init()) ────────────────

    /**
     * Resolves a menu by the id carried in an open payload, or {@code null} if no
     * menu was registered under that id. The generic server receiver in
     * {@code MKC.init()} uses this — an unknown id is a fail-loud log, never an NPE.
     */
    @ApiStatus.Internal
    public static MKCMenu byId(Identifier id) {
        return BY_ID.get(id);
    }

    // ── Client screen registration (called from MKCClient) ──────────────

    /**
     * Registers every defined menu's screen with {@link MenuScreens}. Client-only —
     * invoked once from {@code MKCClient.onInitializeClient}, after all consumer
     * common-init {@code register()} calls have populated {@link #DEFINITIONS}
     * (Fabric runs all main entrypoints before any client entrypoint). The screen
     * factory is materialised into a vanilla {@code ScreenConstructor} only here.
     */
    @ApiStatus.Internal
    public static void registerScreens() {
        for (MKCMenu handle : DEFINITIONS) {
            MenuScreens.register(handle.getType(), handle.screenFactory()::create);
        }
    }
}
