package com.trevorschoeny.menukit.inject;

import com.trevorschoeny.menukit.core.SlotGroupCategory;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.Map;

/**
 * Per-menu-class strategy that maps an {@link AbstractContainerMenu}
 * instance to its {@link SlotGroupCategory}-keyed slot groupings. Vanilla
 * menu resolvers ship in
 * {@link com.trevorschoeny.menukit.MenuKitClient}; modded consumers
 * register resolvers for their own menu classes via
 * {@link SlotGroupCategories#register}.
 *
 * <p>The returned map entries' {@link Slot} lists are what
 * {@link ScreenPanelRegistry} uses to compute slot-group bounding boxes
 * per frame. Each slot should appear in exactly one category's list —
 * categorization is 1:N (one category → many slots), not N:N.
 *
 * <p>Resolution runs once per screen open; the result is implicitly
 * cached for the screen's lifetime. Dynamic menus whose slot set changes
 * mid-session aren't supported in v1 (see M8 §5.4 caching constraint).
 */
@FunctionalInterface
public interface SlotGroupResolver {

    /**
     * Maps the given menu instance's slots to category-keyed sub-lists.
     * Returns an empty map for categories the menu doesn't contain
     * (rather than populating with empty lists).
     */
    Map<SlotGroupCategory, List<Slot>> resolve(AbstractContainerMenu menu);
}
