package com.trevorschoeny.menukit.core;

/**
 * Marker interface for {@link Storage} implementations that represent the
 * <b>player inventory side</b> of a menu, as opposed to the container side.
 *
 * <p>Used by
 * {@link com.trevorschoeny.menukit.screen.MenuKitScreenHandler}'s shift-click
 * routing ("Layer 2: source-aware baseline") to route items in the
 * player-prefers-container / container-prefers-player direction.
 *
 * <p>M7's {@link StorageAttachment#playerAttached} factory returns Storage
 * instances that implement this marker. Consumers declaring their own
 * player-backed Storage (via {@code StorageAttachment.custom(...)} or
 * direct Storage implementation) should also implement this marker if the
 * Storage represents the player's inventory for shift-click purposes.
 *
 * <p>Pre-Phase-14b this was a concrete class with its own item-list +
 * save/load methods; M7's shipping made the impl obsolete. Surviving as
 * a marker because the shift-click routing genuinely needs to distinguish
 * player-side vs container-side Storage instances, and Storage's narrow
 * interface doesn't expose that semantic.
 */
public interface PlayerStorage extends Storage {
}
