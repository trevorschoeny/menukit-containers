package com.trevorschoeny.menukit.inject;

import com.trevorschoeny.menukit.core.Panel;
import com.trevorschoeny.menukit.core.RegionMath;
import com.trevorschoeny.menukit.core.SlotGroupCategory;
import com.trevorschoeny.menukit.core.SlotGroupRegion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Internal registry for SlotGroupContext panel registrations.
 * Post-§0042 split companion to MenuKit's {@link RegionRegistry} (which
 * holds MenuContext + HudContext registrations).
 *
 * <p>Holds process-lifetime per-(category, region) panel lists. Panels
 * register once at mod init (during {@link SlotGroupPanelAdapter#on})
 * and remain registered until process exit.
 *
 * <p><b>Internal only.</b> Consumers don't call this directly — the
 * {@link SlotGroupPanelAdapter#on} method registers on their behalf.
 */
public final class SlotGroupRegionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("menukit-containers");

    private SlotGroupRegionRegistry() {}

    // Per-(category, region) panel lists for SlotGroupContext. Composite key
    // because two adapters targeting (PLAYER_INVENTORY, TOP_ALIGN_RIGHT) and
    // (FURNACE_INPUT, TOP_ALIGN_RIGHT) stack independently — they share a
    // region name but anchor to different slot groups.
    private record SlotGroupKey(SlotGroupCategory category, SlotGroupRegion region) {}
    private static final Map<SlotGroupKey, List<Panel>> SLOT_GROUP = new HashMap<>();
    private static final Map<Panel, Integer> SLOT_GROUP_PADDING = new HashMap<>();
    private static final Map<Panel, Set<SlotGroupKey>> WARNED_SLOT_GROUP =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Registers a SlotGroupContext panel into a (category, region) pair with
     * a content padding. Called from
     * {@link SlotGroupPanelAdapter#on} for each declared target category.
     * A single adapter targeting N categories produces N registrations —
     * each (category, region) key stacks independently.
     */
    public static void registerSlotGroup(Panel panel, SlotGroupCategory category,
                                          SlotGroupRegion region, int padding) {
        SlotGroupKey key = new SlotGroupKey(category, region);
        SLOT_GROUP.computeIfAbsent(key, k -> new ArrayList<>()).add(panel);
        SLOT_GROUP_PADDING.put(panel, padding);
    }

    /**
     * Axial prefix for a SlotGroupContext panel anchored in a given
     * (category, region) pair. Walks the per-key panel list, skipping
     * hidden panels, and sums extent + {@link RegionMath#STACK_GAP} for
     * each visible preceding entry.
     *
     * @throws IllegalStateException if {@code self} is not registered
     *         under {@code (category, region)}
     */
    public static int axialPrefix(Panel self, SlotGroupCategory category,
                                   SlotGroupRegion region) {
        SlotGroupKey key = new SlotGroupKey(category, region);
        List<Panel> panels = SLOT_GROUP.getOrDefault(key, List.of());
        int prefix = 0;
        boolean horizontal = region.isHorizontalFlow();
        for (Panel p : panels) {
            if (p == self) return prefix;
            if (!p.isVisible()) continue;
            int pad = SLOT_GROUP_PADDING.getOrDefault(p, 0);
            int extent = (horizontal ? p.getWidth() : p.getHeight()) + 2 * pad;
            prefix += extent + RegionMath.STACK_GAP;
        }
        throw new IllegalStateException(
                "Panel '" + self.getId() + "' is not registered in "
                        + category + "/" + region);
    }

    /**
     * Logs a one-shot warning the first time a SlotGroupContext panel
     * overflows a given (category, region) pair. Called from
     * {@link SlotGroupPanelAdapter} when
     * {@link com.trevorschoeny.menukit.core.SlotGroupRegionMath#resolveSlotGroup}
     * returns empty.
     */
    public static void warnSlotGroupOverflowOnce(Panel panel,
                                                  SlotGroupCategory category,
                                                  SlotGroupRegion region,
                                                  int pw, int ph, int prefix,
                                                  SlotGroupBounds bounds) {
        SlotGroupKey key = new SlotGroupKey(category, region);
        Set<SlotGroupKey> warned = WARNED_SLOT_GROUP
                .computeIfAbsent(panel, p -> Collections.synchronizedSet(new HashSet<>()));
        if (!warned.add(key)) return;
        int axisExtent = region.isHorizontalFlow() ? pw : ph;
        int axisCapacity = region.isHorizontalFlow() ? bounds.imageWidth() : bounds.imageHeight();
        LOGGER.warn(
                "[SlotGroupRegionRegistry] Panel '{}' overflows {}/{} — axial extent " +
                "{}px (including padding) + prefix {}px exceeds slot-group capacity {}px. " +
                "Silent OUT_OF_REGION until this panel + (category, region) pair is resized.",
                panel.getId(), category, region, axisExtent, prefix, axisCapacity);
    }
}
