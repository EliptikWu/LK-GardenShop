package dev.lk.gardenshop.core;

import dev.lk.gardenshop.core.config.EconomySettings;
import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.ItemSettings;
import dev.lk.gardenshop.core.config.MenuStyle;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.ResourcePackSettings;
import dev.lk.gardenshop.core.config.SellingSettings;
import dev.lk.gardenshop.core.config.ValidationReport;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.core.registry.DropRegistry;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * The entire plugin configuration at one instant, fully parsed and immutable.
 *
 * <p>This is the unit of atomicity behind {@code /gs reload}: a new snapshot is
 * built and validated in full, and only then published to a single
 * {@code AtomicReference}. Readers therefore always see one coherent
 * configuration — never a half-applied one — and a broken YAML costs nothing
 * because the previous snapshot simply stays live.
 *
 * <p>Lives in the root package rather than {@code config} so that {@code config}
 * and {@code registry} stay independent of each other.
 *
 * @param report              issues found while loading; carried along so
 *                            {@code /gs info} can surface warnings later
 * @param loadedAtEpochMilli  when this snapshot was built, for {@code /gs info}
 */
public record ConfigSnapshot(
        String language,
        PricingConfig pricing,
        ItemSettings items,
        SellingSettings selling,
        EconomySettings economy,
        GuiSettings gui,
        ResourcePackSettings pack,
        DropRegistry registry,
        WeightTable weights,
        ValidationReport report,
        long loadedAtEpochMilli
) {

    public ConfigSnapshot {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(pricing, "pricing");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(selling, "selling");
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(gui, "gui");
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(weights, "weights");
        Objects.requireNonNull(report, "report");
    }

    public Optional<DropDefinition> definition(String mythicType) {
        return registry.find(mythicType);
    }

    public Collection<Species> species() {
        return registry.species();
    }

    /**
     * The menu style actually in force.
     *
     * <p>{@code resource-pack.installed: false} overrides whatever {@code gui.yml} asks for, because
     * it is the owner declaring their players have no pack — and pack art on a player without the
     * pack is missing-glyph boxes, not decoration.
     */
    public MenuStyle menuStyle() {
        return MenuStyle.effective(pack.installed(), gui.style());
    }
}
