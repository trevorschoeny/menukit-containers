# MenuKit: Containers

MenuKit: Containers is a slot extension for MenuKit. It lets mods add slots that persist, whether in custom container menus or placed where vanilla never had them.

What it does:
- Creates slots as UI components, placed like any other: a pocket, an extra equipment slot, a satchel, each real and server-synced.
- Adds custom container menus that integrate with MenuKit's panels.
- Attaches per-slot state to any slot: server-authoritative, auto-synced, and either per-player-private or shared across all viewers.
- Makes created slots behave like vanilla ones: identical in creative and survival; correct on death (drop, keep with keepInventory, or destroy with Curse of Vanishing) and grave-mod aware; with optional Curse of Binding and XP Mending.
- Shows a registered slot on every container screen (creative, survival, and foreign menus) without per-screen setup.
- Reaches placed containers: double-chest resolution, server-side reads with no open menu, and a client-capability check.
- Stores each slot's state on its natural owner (the player, block, entity, or item), readable with `/data get`.
- Exposes every slot it creates to any MenuKit consumer, so other mods can see and address your slots, and you theirs. Slots and menus from different mods register cleanly and coexist, keeping mods compatible with each other.

MenuKit: Containers is universal (runs on client and server) and depends on MenuKit, which it pulls in automatically. Requires Fabric.

## Install

```gradle
repositories {
    maven { url 'https://api.modrinth.com/maven' }
}
dependencies {
    modImplementation 'maven.modrinth:menukit-containers:2.0.0'
    // MenuKit comes in transitively, no need to declare it separately
}
```

Declare both in `fabric.mod.json`:

```json
"depends": { "menukit": "*", "menukit-containers": "*" }
```

## Working examples

The `validator-mkc` mod is the honest reference consumer, with real, compiling usage of created slots, per-slot state, death/binding/mending, and the rest. The full surface is documented in the javadoc.

## License

MIT. See `LICENSE`.

## Issues

[github.com/trevorschoeny/menukit-containers/issues](https://github.com/trevorschoeny/menukit-containers/issues).
