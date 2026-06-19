# MenuKit: Containers

Slot extension for MenuKit. Custom container menus, per-slot state, slot-group regions, contract verification — built on the MenuKit UI library.

## What it is

MenuKit: Containers (MKC) adds slot machinery to **[MenuKit](https://github.com/trevorschoeny/menukit)**. If you're building a Fabric mod with custom container UIs — storage blocks with new slot layouts, real slots grafted onto vanilla menus, inventory mods that need per-slot state, anything that goes through a `ScreenHandler` — MKC provides:

- **`MenuKitScreenHandler`** — a container-menu handler designed to integrate cleanly with MenuKit's panel system.
- **`MenuKitHandledScreen`** — the screen-side companion that combines MK panels with vanilla slots in one frame.
- **Per-slot state (M1).** Server-authoritative state attached to individual slots; auto-synced to the client.
- **Slot-group regions.** Group slots into named regions and anchor MK panels to those regions, just like menu regions.
- **Custom payloads.** Typed networking primitives for slot state, ready to wire into your custom protocol.
- **Contract verification.** Built-in validators for slot/UI invariants.

MKC depends on MenuKit. Depending on MKC pulls in MenuKit transitively — you don't need both dependencies in your gradle build.

## Install

Add the Modrinth Maven repository and the MKC dependency to your `build.gradle`:

```gradle
repositories {
    maven { url 'https://api.modrinth.com/maven' }
}

dependencies {
    modImplementation 'maven.modrinth:menukit-containers:1.1.0'
    // MenuKit is pulled in transitively via api — no need to declare it separately
}
```

And declare both dependencies in your `src/main/resources/fabric.mod.json`:

```json
"depends": {
    "menukit": "*",
    "menukit-containers": "*"
}
```

MKC is universal (`environment: *`) — runs on both client and server. MenuKit stays client-only.

## Quickstart

```java
// 1. Define a screen handler by extending MenuKitScreenHandler
public class MyMenuHandler extends MenuKitScreenHandler {
    public MyMenuHandler(int syncId, Inventory playerInventory, ContainerLevelAccess access) {
        super(MY_MENU_TYPE, syncId, playerInventory);
        // Register slots, set up storage attachments, etc.
        // See MenuKitScreenHandler javadoc for the standard hooks.
    }
}

// 2. Companion screen on the client
public class MyMenuScreen extends MenuKitHandledScreen<MyMenuHandler> {
    public MyMenuScreen(MyMenuHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }
    // Override panel layout if needed; default plays nicely with MK regions.
}

// 3. Open from the server side
player.openMenu(new SimpleMenuProvider((syncId, inv, p) ->
    new MyMenuHandler(syncId, inv, accessor), Component.literal("My Menu")));
```

## Feature highlights

- **Custom container menus** — `MenuKitScreenHandler` + `MenuKitHandledScreen` for clean integration with MK's panel system.
- **Per-slot state (M1)** — `MKSlotState` attaches arbitrary state to individual slots; server-authoritative, auto-synced to the client (`SlotStateBag`, `SlotStateClientCache`).
- **Slot-grafting onto vanilla menus** — add real, persistent, server-synced slots to existing vanilla screens (the player inventory, placed containers) without owning the screen. Grafted slots handle click-through correctly and can reposition at runtime; you write the menu mixin, MKC ships the kit.
- **Block-portable slot state** — per-slot state can survive a shulker being broken, carried, and replaced, via a generic library-owned bridge. You register a channel; the travel is automatic.
- **Shared or private slot state** — register a channel as per-player-private (default) or shared (one cross-player-visible value per slot, synced live to every viewer).
- **Placed-container reach** — double-chest resolution, menu-free server-side reads (for automation like hoppers), and a client-capability query. The library resolves containers, exposes state, and reports capability; your mod owns the enforcement policy.
- **Slot-group regions** — group slots into named regions; anchor MK panels to those groups.
- **Storage primitives** — `Storage`, `KeyedStorage`, `PlayerStorage`, `EphemeralStorage`, `StorageAttachment` cover most container patterns.
- **Custom payloads** — `SlotStateUpdateC2SPayload`, `SlotStateSnapshotS2CPayload`, `SlotStateUpdateS2CPayload` for the slot-state protocol; pattern is reusable for your own typed payloads.
- **Contract verification** — `ContractVerification` validates slot/UI invariants at runtime.

## License

MIT. See `LICENSE`.

## Issues

File issues at [github.com/trevorschoeny/menukit-containers/issues](https://github.com/trevorschoeny/menukit-containers/issues).
