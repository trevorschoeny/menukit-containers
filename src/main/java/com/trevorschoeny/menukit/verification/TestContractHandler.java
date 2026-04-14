package com.trevorschoeny.menukit.verification;

import com.trevorschoeny.menukit.core.EphemeralStorage;
import com.trevorschoeny.menukit.core.InteractionPolicy;
import com.trevorschoeny.menukit.core.QuickMoveParticipation;
import com.trevorschoeny.menukit.core.VirtualStorage;
import com.trevorschoeny.menukit.screen.MenuKitScreenHandler;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * MenuKit-native test handler for contract verification. Three panels, each
 * chosen to exercise a specific canonical guarantee:
 *
 * <ul>
 *   <li><b>main</b> — 6 free slots (EphemeralStorage), always visible.
 *       Target for composability (global mixin fires on these slots) and
 *       substitutability ({@code instanceof Slot} + {@code getItem} mixin
 *       composition).</li>
 *   <li><b>hidden</b> — 4 diamond-filtered slots (EphemeralStorage), starts
 *       hidden, toggleable. Target for sync-safety (rapid toggle stress
 *       test) and inertness (hidden-panel slots report as EMPTY/false).</li>
 *   <li><b>player</b> — 36-slot wrap of the player's inventory, always
 *       visible. Provides a routing target and keeps the test screen
 *       visually grounded.</li>
 * </ul>
 *
 * <p>This class and its siblings in {@code verification/} are the contract-
 * verification harness. Run by {@code /mkverify &lt;contract&gt;} subcommands
 * to produce empirical evidence that MenuKit's five canonical guarantees
 * hold. Kept in the repo so each phase can re-run verification cheaply.
 */
public final class TestContractHandler {

    private TestContractHandler() {}

    /** Factory — wired into the MenuType at registration time. */
    public static MenuKitScreenHandler create(int syncId,
                                              Inventory playerInventory,
                                              MenuType<MenuKitScreenHandler> menuType) {
        // Main panel storage — 6 free slots. Seed a couple of non-empty
        // stacks so composability/substitutability tests have items to
        // observe without requiring the player to pre-place anything.
        EphemeralStorage mainStorage = EphemeralStorage.of(6);
        mainStorage.setStack(0, new net.minecraft.world.item.ItemStack(Items.IRON_INGOT, 4));
        mainStorage.setStack(1, new net.minecraft.world.item.ItemStack(Items.REDSTONE, 8));

        // Hidden panel storage — 4 slots, diamond-filtered. Pre-fill with
        // diamonds so inertness/sync-safety tests have something to observe.
        EphemeralStorage hiddenStorage = EphemeralStorage.of(4);
        hiddenStorage.setStack(0, new net.minecraft.world.item.ItemStack(Items.DIAMOND, 16));
        hiddenStorage.setStack(1, new net.minecraft.world.item.ItemStack(Items.DIAMOND, 8));
        hiddenStorage.setStack(2, new net.minecraft.world.item.ItemStack(Items.DIAMOND, 2));

        // Player inventory wrap — local indices 0..26 map to vanilla main
        // (9..35); local 27..35 map to vanilla hotbar (0..8). Matches
        // vanilla's shift-click ordering.
        VirtualStorage playerInv = new VirtualStorage(
                36,
                i -> {
                    int invIndex = i < 27 ? i + 9 : i - 27;
                    return playerInventory.getItem(invIndex);
                },
                (i, stack) -> {
                    int invIndex = i < 27 ? i + 9 : i - 27;
                    playerInventory.setItem(invIndex, stack);
                },
                playerInventory::setChanged);

        return MenuKitScreenHandler.builder(menuType)
                .panel("main", p -> p
                        .group("container", mainStorage, InteractionPolicy.free()))
                .panel("hidden", p -> p
                        .rightOf("main")
                        .toggleKey(GLFW.GLFW_KEY_T)
                        .group("filtered", hiddenStorage,
                                InteractionPolicy.input(
                                        stack -> stack.is(Items.DIAMOND)))
                        .hidden())
                .panel("player", p -> p
                        .group("inventory", playerInv, InteractionPolicy.free(),
                                QuickMoveParticipation.BOTH, 0, 9, 2, 4))
                .build(syncId);
    }
}
