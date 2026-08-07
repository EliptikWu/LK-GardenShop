package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.item.MythicItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Using the pack's own art for a menu icon.
 *
 * <p>The price book shows the real crop for each drop type instead of a stand-in material, and it
 * gets that item from MythicMobs. Mythic hands out a <b>cached</b> instance, so the one rule that
 * really matters here is that dressing an icon must not write on it.
 */
// CustomModelData is read and written the old way here. The plugin compiles against Paper 1.21.3,
// where these methods are current; the tests run against 1.21.11, which MockBukkit demands and which
// has deprecated them in favour of the component API. Chasing that here would test a method the
// shipped jar does not call.
@SuppressWarnings("deprecation")
class PackArtTest {

    /** One of the Model ids in growGardenItems.yml: the plain Odre drop. */
    private static final int ODRE_MODEL = 92139;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack cropItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> {
            meta.setCustomModelData(ODRE_MODEL);
            meta.displayName(Component.text("Odre Plant Drop"));
        });
        return item;
    }

    @Test
    @DisplayName("dressing an icon does not write on the item it was built from")
    void sourceItemIsNotMutated() {
        ItemStack source = cropItem();

        Icon.fromItem(source, Component.text("Renamed"), List.of(Component.text("lore")));

        // Mythic's getCachedMenuItem() returns its own cached instance. Renaming that in place would
        // corrupt every later use of it -- including Mythic's own menus -- and the damage would look
        // like a MythicMobs bug rather than ours.
        assertThat(source.getItemMeta().displayName()).isEqualTo(Component.text("Odre Plant Drop"));
        assertThat(source.getItemMeta().hasLore()).isFalse();
    }

    @Test
    @DisplayName("the icon keeps the model, which is the entire point of using the real item")
    void modelSurvives() {
        ItemStack icon = Icon.fromItem(cropItem(), Component.text("Odre"), List.of());

        assertThat(icon.getItemMeta().hasCustomModelData()).isTrue();
        assertThat(icon.getItemMeta().getCustomModelData()).isEqualTo(ODRE_MODEL);
        assertThat(icon.getItemMeta().displayName()).isEqualTo(
                Component.text("Odre").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
    }

    @Test
    @DisplayName("an icon keeps the stack's size, clamped to something a slot can render")
    void amountIsKeptButClamped() {
        // Deliberate: the sell menu's 'held' slot uses this to show a player what they are holding,
        // so the count is information. The price book is unaffected -- the item Mythic builds for a
        // drop type is a single one.
        ItemStack stack = cropItem();
        stack.setAmount(37);
        assertThat(Icon.fromItem(stack, Component.text("x"), List.of()).getAmount()).isEqualTo(37);

        stack.setAmount(200);
        assertThat(Icon.fromItem(stack, Component.text("x"), List.of()).getAmount())
                .as("an amount past the stack limit would not render")
                .isEqualTo(Material.PAPER.getMaxStackSize());
    }

    @Test
    @DisplayName("with no MythicMobs there is no art, and asking for it is harmless")
    void withoutMythicThereIsNoArt() {
        // Which is what makes the fallback path the normal one in tests, and why the price book keeps
        // a stand-in material for every row.
        MythicItems mythic = MythicItems.detect(Logger.getLogger("test"));

        assertThat(mythic.isAvailable()).isFalse();
        assertThatCode(() -> assertThat(mythic.menuItem("growGardenNCDrop")).isEmpty())
                .doesNotThrowAnyException();
        assertThat(mythic.menuItem(null)).isEmpty();
        assertThat(mythic.menuItem("")).isEmpty();
    }
}
