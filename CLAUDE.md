# MenuKit: Containers (the slot extension)

Universal Fabric library — both client and server. Produces `menukit-containers-1.0.0.jar`. Mod ID `menukit-containers`. `environment: "*"`.

## What lives here

Custom container menus (`MenuKitScreenHandler`, `MenuKitHandledScreen`), per-slot state machinery (M1: `SlotStateBag`, `SlotStateChannel`, `SlotStateRegistry`, S2C/C2S payloads), slot identity (M2: `SlotIdentity`), slot grafting (M3), slot-group regions (`SlotGroupRegion`, `SlotGroupCategory`, `SlotGroupBounds`, `SlotGroupResolver`, `VanillaSlotGroupResolvers`), storage attachments (M7: `Storage`, `EphemeralStorage`, `StorageAttachment`, `BlockScopedDropHandler`), slot-group panel rendering (`SlotGroupPanelRegistry`, `SlotGroupPanelAdapter`, `SlotGroupPanelRenderMixin`), contract verification (`ContractVerification`, `TestContractHandler`, `TestContractScreen`).

## What does NOT live here

Pure UI primitives — widgets, HUD panels, layouts, modal-on-vanilla-menus machinery, standalone screens. Those live in the sibling `menukit` codebase.

## §0042 boundary (binding)

This codebase depends on `menukit` (one-way, gradle-enforced via `api project(':menukit')`). The reverse direction is forbidden — `menukit` must compile and run with no awareness of this codebase. The `api` configuration is intentional: consumer mods that depend on `:menukit-containers` get `:menukit` transitively, so they need only one project dependency for both surfaces.

A class is **only** in this codebase if it touches slots, ScreenHandlers, slot state, slot groups, slot identity, slot storage, or any slot-adjacent concept — measured against API surface, type signatures, or implementation references. Otherwise it belongs in `menukit`.

## Architectural canon

Governed by the `@ Trevlar Mods/@ MenuKit/` sub-unit of the Trevlar Mods Silcrow agency (same as the `menukit` codebase). Local canon at `@ Trevlar Mods/@ MenuKit/1 | Canon/accepted/`; design docs at `@ Trevlar Mods/@ MenuKit/2 | Working Files/Design Docs/`. The two-artifact split is canonized in §0042.
