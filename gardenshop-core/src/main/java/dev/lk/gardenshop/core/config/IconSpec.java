package dev.lk.gardenshop.core.config;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * What a menu button looks like.
 *
 * <p>Two looks per button, on purpose. {@code material} + {@code modelData} is the pack art: a
 * neutral item carrying a CustomModelData the resource pack redirects to a custom texture. That
 * renders as a blank sheet of paper for anyone without the pack, which is why every button also
 * carries {@code fallbackMaterial} — a recognisable vanilla item used whenever the menu is drawn
 * {@link MenuStyle#PLAIN}.
 *
 * <p>Material names stay as strings so this module keeps no dependency on server classes; they are
 * resolved, and reported if invalid, at the point of use.
 *
 * @param material         item used when pack art is on
 * @param modelData        CustomModelData for the pack, or empty for none
 * @param fallbackMaterial item used when the menu is plain; blank falls back to {@code material}
 */
public record IconSpec(String material, OptionalInt modelData, String fallbackMaterial) {

    public IconSpec {
        Objects.requireNonNull(modelData, "modelData");
        material = material == null ? "" : material.trim();
        fallbackMaterial = fallbackMaterial == null ? "" : fallbackMaterial.trim();

        if (modelData.isPresent() && modelData.getAsInt() < 0) {
            throw new IllegalArgumentException("model-data must not be negative, got " + modelData.getAsInt());
        }
    }

    /** A plain vanilla icon with no pack art. */
    public static IconSpec of(String material) {
        return new IconSpec(material, OptionalInt.empty(), "");
    }

    /**
     * The material to use for a given style.
     *
     * @param plain whether the menu is being drawn without pack art
     */
    public String materialFor(boolean plain) {
        if (plain && !fallbackMaterial.isEmpty()) {
            return fallbackMaterial;
        }
        return material;
    }

    /** CustomModelData only applies when the pack art is in play. */
    public OptionalInt modelDataFor(boolean plain) {
        return plain ? OptionalInt.empty() : modelData;
    }

    public boolean isBlank() {
        return material.isEmpty() && fallbackMaterial.isEmpty();
    }
}
