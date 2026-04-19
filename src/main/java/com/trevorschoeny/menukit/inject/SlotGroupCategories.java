package com.trevorschoeny.menukit.inject;

import com.trevorschoeny.menukit.core.SlotGroupCategory;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Library-owned registry of per-menu-class {@link SlotGroupResolver}s.
 * Parallel to {@link MenuChrome} in shape — exact-class-only resolution,
 * first-registration-wins, library-shipped providers for vanilla classes,
 * modded consumers extend via {@link #register}.
 *
 * <p>See {@code Design Docs/Phase 12.5/M8_FOUR_CONTEXT_MODEL.md} §5.3 for
 * the resolver design and §6 for the full vanilla coverage catalog.
 *
 * <h3>Resolution — exact-class only</h3>
 *
 * {@link #of(AbstractContainerMenu)} looks up by {@code menu.getClass()} —
 * no inheritance walk. Rationale matches {@code MenuChrome}: vanilla
 * subclass relationships don't reliably predict slot-layout relationships
 * (e.g., {@code CraftingMenu} extends {@code AbstractCraftingMenu} which
 * extends {@code RecipeBookMenu}, but each concrete menu has its own slot
 * ordering). Modded consumers register for their own concrete menu classes.
 */
public final class SlotGroupCategories {

    private static final Logger LOGGER = LoggerFactory.getLogger("menukit");

    private SlotGroupCategories() {}

    // Exact-class-only map — no inheritance walk. Empty list value for
    // {@link #of} means "no slot groups resolved" (either no resolver
    // registered or the resolver returned empty).

    private static final Map<Class<? extends AbstractContainerMenu>, SlotGroupResolver> RESOLVERS
            = new HashMap<>();

    /**
     * Registers a resolver for a concrete menu class. First registration
     * wins; subsequent calls with the same class are no-ops with a warning
     * log (mirrors {@link MenuChrome#register}).
     *
     * <p>Called at mod init — library-shipped resolvers register during
     * {@code MenuKitClient.onInitializeClient}; modded consumers register
     * from their own {@code ModInitializer}.
     *
     * @param menuClass the concrete menu class (typically the same class
     *                  that's registered as a MenuType)
     * @param resolver  the resolver
     * @param <T>       the menu type
     */
    public static <T extends AbstractContainerMenu> void register(
            Class<T> menuClass, SlotGroupResolver resolver) {
        SlotGroupResolver existing = RESOLVERS.get(menuClass);
        if (existing != null) {
            LOGGER.warn("[SlotGroupCategories] resolver for {} already registered — ignoring " +
                    "second registration", menuClass.getName());
            return;
        }
        RESOLVERS.put(menuClass, resolver);
        LOGGER.info("[SlotGroupCategories] registered resolver for {}",
                menuClass.getSimpleName());
    }

    /**
     * Resolves the given menu instance's slot groups. Exact-class match on
     * {@code menu.getClass()}; returns an empty map for menus without a
     * registered resolver.
     *
     * <p>Called per-screen-open from
     * {@link ScreenPanelRegistry#onScreenInit}. The result is effectively
     * cached for the screen's lifetime.
     */
    public static Map<SlotGroupCategory, List<Slot>> of(AbstractContainerMenu menu) {
        if (menu == null) return Map.of();
        SlotGroupResolver resolver = RESOLVERS.get(menu.getClass());
        if (resolver == null) return Map.of();
        return resolver.resolve(menu);
    }
}
