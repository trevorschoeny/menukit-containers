package com.trevorschoeny.menukit.inject;

import com.trevorschoeny.menukit.core.SlotGroupCategory;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Library-owned registry of per-menu-class {@link SlotGroupResolver}s.
 * Parallel to {@link MenuChrome} in shape — exact-class-only resolution,
 * first-registration-wins for the primary resolver, library-shipped
 * providers for vanilla classes, modded consumers register for their own
 * classes via {@link #register}.
 *
 * <p>Consumers grafting into a vanilla menu (M4 slot-injection pattern) and
 * wanting their grafted slot group to participate in SlotGroupContext
 * dispatch declare their category via {@link #extend} — additive,
 * collision-rejecting, preserves the library's first-wins guarantee for
 * {@code register}.
 *
 * <p>See {@code Design Docs/Phase 12.5/M8_FOUR_CONTEXT_MODEL.md} §5.3 for
 * the resolver design and §6 for the full vanilla coverage catalog.
 * See {@code Design Docs/Phase 12.5/V5_7_EXTEND_RESOLVER_FIX.md} for the
 * {@code extend} design note + collision policy.
 *
 * <h3>Resolution — exact-class only</h3>
 *
 * {@link #of(AbstractContainerMenu)} looks up by {@code menu.getClass()} —
 * no inheritance walk. Rationale matches {@code MenuChrome}: vanilla
 * subclass relationships don't reliably predict slot-layout relationships
 * (e.g., {@code CraftingMenu} extends {@code AbstractCraftingMenu} which
 * extends {@code RecipeBookMenu}, but each concrete menu has its own slot
 * ordering). Modded consumers register for their own concrete menu classes.
 *
 * <h3>Primary vs. extensions</h3>
 *
 * Each class has at most one primary resolver (from {@link #register}) and
 * zero or more extension resolvers (from {@link #extend}). {@link #of}
 * runs the primary first, then each extension in registration order,
 * merging outputs. Extensions can only ADD new categories — an extension
 * emitting a category the primary (or an earlier extension) already
 * emitted is dropped with a warn log. This preserves library-defined
 * category meaning across the mod ecosystem.
 */
public final class SlotGroupCategories {

    private static final Logger LOGGER = LoggerFactory.getLogger("menukit");

    private SlotGroupCategories() {}

    // Exact-class-only maps — no inheritance walk. Empty return from
    // {@link #of} means "no slot groups resolved" (no resolver registered
    // or all resolvers returned empty).
    //
    // Plain HashMap, not ConcurrentHashMap — registration happens during
    // Fabric's single-threaded init phase. Matches existing library
    // registries (MenuChrome, SlotGroupCategories' original shape). Don't
    // drift to ConcurrentHashMap here.

    private static final Map<Class<? extends AbstractContainerMenu>, SlotGroupResolver> RESOLVERS
            = new HashMap<>();

    private static final Map<Class<? extends AbstractContainerMenu>, List<SlotGroupResolver>> EXTENSIONS
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
     * Registers an additional resolver for a menu class, contributing
     * extra categories without replacing the primary. Multiple extensions
     * per class are allowed, applied in registration order.
     *
     * <p><b>Use case.</b> A consumer grafts a slot into a vanilla menu
     * (e.g., {@code FurnaceMenu}) via M4 slot-injection and wants their
     * grafted slot group to participate in SlotGroupContext dispatch
     * under a consumer-owned category. The library's primary resolver
     * for the vanilla menu is already registered and first-wins blocks
     * a replacement; {@code extend} is the additive path.
     *
     * <p><b>Collision policy — additive only, no redefinition.</b> If
     * this extension emits a {@link SlotGroupCategory} that the primary
     * resolver or an earlier extension already emitted for the same
     * class, the duplicate entry is dropped at resolve time with a warn
     * log. Earlier entry wins. This preserves library-defined category
     * meaning (e.g., {@code FURNACE_INPUT} means one slot list, consistent
     * across mods) and matches the first-wins-with-warn pattern of
     * {@link #register}.
     *
     * <p>The class does not need a primary registered first — extensions
     * can stand alone.
     *
     * @param menuClass the concrete menu class
     * @param resolver  the extension resolver
     * @param <T>       the menu type
     */
    public static <T extends AbstractContainerMenu> void extend(
            Class<T> menuClass, SlotGroupResolver resolver) {
        List<SlotGroupResolver> list = EXTENSIONS.computeIfAbsent(
                menuClass, k -> new ArrayList<>());
        list.add(resolver);
        LOGGER.info("[SlotGroupCategories] extended resolver for {} (extension #{})",
                menuClass.getSimpleName(), list.size());
    }

    /**
     * Resolves the given menu instance's slot groups. Exact-class match on
     * {@code menu.getClass()}; returns an empty map for menus without a
     * registered resolver or extension.
     *
     * <p>Runs the primary resolver first (if any), then each extension in
     * registration order. Extension-emitted categories that collide with
     * the accumulated output are dropped with a warn log per
     * {@link #extend}'s collision policy.
     *
     * <p>Called per-screen-open from
     * {@link ScreenPanelRegistry#onScreenInit}. The result is effectively
     * cached for the screen's lifetime.
     */
    public static Map<SlotGroupCategory, List<Slot>> of(AbstractContainerMenu menu) {
        if (menu == null) return Map.of();
        Class<?> menuClass = menu.getClass();
        SlotGroupResolver primary = RESOLVERS.get(menuClass);
        List<SlotGroupResolver> extensions = EXTENSIONS.getOrDefault(menuClass, List.of());

        if (primary == null && extensions.isEmpty()) return Map.of();

        Map<SlotGroupCategory, List<Slot>> out = new HashMap<>();
        if (primary != null) {
            out.putAll(primary.resolve(menu));
        }
        for (SlotGroupResolver ext : extensions) {
            Map<SlotGroupCategory, List<Slot>> contribution = ext.resolve(menu);
            for (Map.Entry<SlotGroupCategory, List<Slot>> entry : contribution.entrySet()) {
                if (out.containsKey(entry.getKey())) {
                    LOGGER.warn("[SlotGroupCategories] extension for {} tried to redefine " +
                            "category {} — dropping (earlier entry wins)",
                            menuClass.getName(), entry.getKey());
                    continue;
                }
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(out);
    }
}
