# MenuKit: Containers (the slot extension)

Universal Fabric library — both client and server. Produces `menukit-containers-1.0.0.jar`. Mod ID `menukit-containers`. `environment: "*"`.

## What lives here

**Ownership idioms** — slot creation, owned `ScreenHandler` subclasses, slot state, owned storage implementations:

- Custom container menus (`MenuKitScreenHandler`, `MenuKitHandledScreen`).
- Per-slot state machinery (M1: `SlotStateBag`, `SlotStateChannel`, `SlotStateRegistry`, S2C/C2S payloads).
- Slot grafting (M3) — owned `MenuKitSlot`.
- Owned `SlotGroup` collections (implementing MK-side `SlotGroupLike`).
- Storage attachments (M7) — owned implementations: `EphemeralStorage`, `StorageContainerAdapter`, `StorageAttachment`, `BlockScopedDropHandler`, `CustomAttachmentSpec`, `StorageAttachments`.
- Server-coupled mixins (`M7BlockDropMixin`, `PlayerListRespawnMixin`, `PlayerOpenMenuMixin`, `VerifyGetItemMixin`, `VerifyMayPlaceMixin`).
- Contract verification cohort (`ContractVerification`, `TestContractHandler`, `TestContractScreen`).
- MKC-side facades (e.g., `MenuKitScreenHandler.findGroupForSlot(Slot)` — combined owned + observed lookup per §0043).
- Entrypoints (`MenuKitContainers`, `MenuKitContainersClient`).

## What does NOT live here (post-§0044)

- Pure UI primitives — widgets, HUD panels, layouts, modal-on-vanilla-menus machinery, standalone screens. Those live in the sibling `menukit` codebase.
- **Observation idioms** — read-only observation of vanilla slots/menus. `HandlerRecognizerRegistry`, `VirtualSlotGroup`, slot-group region machinery, `VanillaSlotGroupResolvers`, `SlotGroupCategory`, `SlotGroupResolver`, etc. all live in `menukit` post-§0044.
- **Signature-pure shared contracts** — `Storage` (interface), `InteractionPolicy`, `QuickMoveParticipation`, `VirtualStorage`, `ReadOnlyStorage`, `SlotGroupLike`, `SlotIdentity` all live in `menukit` per the dependency-inversion pattern (MKC depends on MK; contracts belong with the depended-on side).

## §0042 / §0043 / §0044 boundary (binding)

This codebase depends on `menukit` (one-way, gradle-enforced via `api project(':menukit')`). The reverse direction is forbidden — `menukit` must compile and run with no awareness of this codebase. The `api` configuration is intentional: consumer mods that depend on `:menukit-containers` get `:menukit` transitively, so they need only one project dependency for both surfaces.

Per §0043 (Complete-on-Side Feature Ownership, accepted): features in this codebase are complete on this side. If a feature needs MK-side capability, it should call MK's complete API directly (via the `api` dependency) — not register callbacks into MK that turn an MK feature into a "with-MKC" version. MKC contributes its own complete features alongside MK's; it does not extend MK's surfaces.

Per §0044 (Slot Observation Refinement, pending): a class belongs in this codebase if and only if it touches **ownership** of slots (creation, owned state, owned collections, owned storage implementations, handler subclassing). Observation idioms and signature-pure shared contracts live in `menukit`.

## Architectural canon

Governed by the `@ Trevlar Mods/@ MenuKit/` sub-unit of the Trevlar Mods Silcrow agency (same as the `menukit` codebase). Local canon at `@ Trevlar Mods/@ MenuKit/1 | Canon/accepted/`; design docs at `@ Trevlar Mods/@ MenuKit/2 | Working Files/Design Docs/`. The two-artifact split is canonized in §0042.
