package com.trevorschoeny.menukit.state;

import com.trevorschoeny.menukit.core.PersistentContainerKey;

import org.jetbrains.annotations.ApiStatus;

/**
 * Result of slot-aware container resolution (§0050): the persistent owner a
 * slot's state attaches to, plus the slot index <em>local to that owner</em>.
 *
 * <p>For a single-owner container the local index equals the input (global)
 * index — the identity case. For a composite container (vanilla double chest /
 * {@code CompoundContainer}) the global index is split to the owning half's
 * {@link PersistentContainerKey.BlockEntityKey} and the index within that half
 * (global − first-half size). Storage and the menu-free read use the local
 * index; sync (snapshot entries, the shared broadcast) stays on global /
 * menu-relative indices, because the client never sees the half-split (it backs
 * the menu with a flat container).
 */
@ApiStatus.Internal
public record ResolvedSlot(PersistentContainerKey key, int localSlotIndex) {}
