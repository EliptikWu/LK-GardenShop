package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.core.config.IconSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

/** Builds menu icons, and turns configured material names into materials safely. */
public final class Icon {

    private Icon() {
    }

    /**
     * Resolves a material name from {@code gui.yml}.
     *
     * <p>Falls back rather than throwing: a menu that opens with one wrong-looking button
     * beats a menu that refuses to open because someone typo'd a material.
     */
    public static Material material(String name, Material fallback, Logger logger) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Optional<Material> resolved = Optional.ofNullable(
                Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT)));
        if (resolved.isEmpty()) {
            logger.warning("gui.yml: '" + name + "' is not a valid material, using "
                    + fallback + " instead.");
            return fallback;
        }
        if (!resolved.get().isItem()) {
            logger.warning("gui.yml: '" + name + "' is a block, not an item, so it cannot be"
                    + " shown in a menu. Using " + fallback + " instead.");
            return fallback;
        }
        return resolved.get();
    }

    /**
     * Builds a button from its {@link IconSpec}.
     *
     * <p>The spec carries both looks — pack art with a CustomModelData, and a vanilla fallback — and
     * {@code plain} picks between them. CustomModelData is only stamped when pack art is in play:
     * setting it for a player without the pack does nothing visible but does make the item stop
     * stacking with its plain twin.
     */
    public static ItemStack of(IconSpec spec, boolean plain, Logger logger,
                               Component name, List<Component> lore) {
        // BARRIER as the last resort, not PAPER. This fires when gui.yml names a material that does
        // not exist or names none at all, which is a misconfiguration -- and a barrier looks like
        // one, next to a logged warning. Paper looked deliberate, and worse, wears a crop sprite on
        // any server running the crop pack, since that pack owns models/item/paper.json.
        Material material = material(spec.materialFor(plain), Material.BARRIER, logger);
        ItemStack icon = of(material, name, lore);

        OptionalInt modelData = spec.modelDataFor(plain);
        if (modelData.isPresent()) {
            int value = modelData.getAsInt();
            icon.editMeta(meta -> meta.setCustomModelData(value));
        }
        return icon;
    }

    public static ItemStack of(IconSpec spec, boolean plain, Logger logger, Component name) {
        return of(spec, plain, logger, name, List.of());
    }

    public static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack icon = new ItemStack(material);
        icon.editMeta(meta -> {
            // Vanilla renders item names and lore in italics, which makes every generated
            // line look provisional. Switched off explicitly.
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            if (!lore.isEmpty()) {
                List<Component> plain = new ArrayList<>(lore.size());
                for (Component line : lore) {
                    plain.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                }
                meta.lore(plain);
            }
        });
        return icon;
    }

    public static ItemStack of(Material material, Component name) {
        return of(material, name, List.of());
    }

    /**
     * A copy of a real item, dressed up as an icon.
     *
     * <p>Used to show a player their own crop in the menu. The copy keeps the item's model
     * and persistent data so it looks exactly like the thing being priced, and being a copy
     * means the original is never at risk.
     */
    public static ItemStack fromItem(ItemStack source, Component name, List<Component> lore) {
        ItemStack icon = source.clone();
        icon.setAmount(Math.max(1, Math.min(source.getAmount(), source.getMaxStackSize())));
        icon.editMeta(meta -> {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            List<Component> plain = new ArrayList<>(lore.size());
            for (Component line : lore) {
                plain.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(plain);
        });
        return icon;
    }

    /** A blank pane for empty slots, with an empty name so it shows no tooltip text. */
    public static ItemStack filler(Material material) {
        return of(material, Component.empty());
    }
}
