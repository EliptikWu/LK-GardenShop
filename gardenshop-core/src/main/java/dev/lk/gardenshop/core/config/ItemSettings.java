package dev.lk.gardenshop.core.config;

import java.util.Objects;

/**
 * How the plugin treats harvest items, from {@code config.yml → items}.
 *
 * @param stampOnGenerate   roll and store the weight the moment Mythic generates
 *                          the item, rather than lazily on first appraisal
 * @param rewriteLore       replace the pack's {@code Weight:} lore line with a
 *                          2-decimal rendering of the stored weight
 * @param legacyLoreFallback derive a weight from the lore for items that predate
 *                          the plugin. Turning this off makes those items unsellable
 * @param weightLoreFormat  format for the rewritten line; must contain one
 *                          {@code %s} where the formatted weight goes
 * @param weightLoreMarker  substring identifying the lore line to replace, matched
 *                          case-insensitively after colour codes are stripped
 * @param requireCropPack   refuse to sell and refuse to open the shop while MythicMobs does not
 *                          have the crops this file prices. Off, selling simply finds nothing and
 *                          players are told there is nothing to sell — which is true and useless
 */
public record ItemSettings(
        boolean stampOnGenerate,
        boolean rewriteLore,
        boolean legacyLoreFallback,
        String weightLoreFormat,
        String weightLoreMarker,
        boolean requireCropPack
) {

    /** Without the pack gate, which defaults to on. */
    public ItemSettings(boolean stampOnGenerate, boolean rewriteLore, boolean legacyLoreFallback,
                        String weightLoreFormat, String weightLoreMarker) {
        this(stampOnGenerate, rewriteLore, legacyLoreFallback, weightLoreFormat, weightLoreMarker, true);
    }

    public ItemSettings {
        Objects.requireNonNull(weightLoreFormat, "weightLoreFormat");
        Objects.requireNonNull(weightLoreMarker, "weightLoreMarker");

        if (!weightLoreFormat.contains("%s")) {
            throw new IllegalArgumentException(
                    "weight-lore-format must contain '%s' as the weight placeholder, got: " + weightLoreFormat);
        }
        if (weightLoreMarker.isBlank()) {
            throw new IllegalArgumentException("weight-lore-marker must not be blank");
        }
    }

    public static ItemSettings defaults() {
        return new ItemSettings(true, true, true, "&f&lWeight: &r%skg", "weight:");
    }
}
