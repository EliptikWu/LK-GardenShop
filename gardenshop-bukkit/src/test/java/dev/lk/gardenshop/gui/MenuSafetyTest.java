package dev.lk.gardenshop.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing a GUI must never do is let a player move an item.
 *
 * <p>Every menu in this plugin is display-only, so the guarantee is simple to state and worth
 * pinning down: no click, of any type, in any half of the view, may go through while a menu is
 * open. These tests exist because the failure mode is silent — a shift-click that is not
 * cancelled looks like nothing happened until an item goes missing.
 */
class MenuSafetyTest {

    private ServerMock server;
    private MenuService menus;
    private MenuListener listener;

    /** A menu with one button, recording whether its handler ran. */
    private static final class ProbeMenu extends Menu {

        private int clicks;
        private boolean explode;

        ProbeMenu(MenuService menus, PlayerMock viewer) {
            super(menus, viewer);
        }

        @Override
        protected Component title() {
            return Component.text("Probe");
        }

        @Override
        protected int size() {
            return 27;
        }

        @Override
        protected void render() {
            set(13, Icon.of(Material.GOLD_INGOT, Component.text("Button")));
        }

        @Override
        public void onClick(InventoryClickEvent event) {
            clicks++;
            if (explode) {
                throw new IllegalStateException("button is broken");
            }
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin("LKGardenShopTest");
        menus = new MenuService(plugin);
        listener = new MenuListener(menus, Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ProbeMenu openProbe(PlayerMock player) {
        ProbeMenu menu = new ProbeMenu(menus, player);
        menu.open();
        return menu;
    }

    private InventoryClickEvent click(PlayerMock player, int rawSlot, ClickType type,
                                     InventoryAction action) {
        InventoryView view = player.getOpenInventory();
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, type, action);
    }

    @Test
    @DisplayName("a plain click on a button is cancelled but still fires the button")
    void buttonClickIsCancelledAndDispatched() {
        PlayerMock player = server.addPlayer();
        ProbeMenu menu = openProbe(player);

        InventoryClickEvent event = click(player, 13, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);

        assertThat(event.isCancelled()).as("a menu click must never move an item").isTrue();
        assertThat(menu.clicks).isEqualTo(1);
    }

    @Test
    @DisplayName("every click type is cancelled, including the ones that move items sideways")
    void allClickTypesAreCancelled() {
        PlayerMock player = server.addPlayer();
        openProbe(player);

        // Each of these is a distinct way to get an item out of, or into, a container.
        List<ClickType> dangerous = List.of(
                ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
                ClickType.MIDDLE, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK,
                ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND);

        for (ClickType type : dangerous) {
            InventoryClickEvent event = click(player, 13, type, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            listener.onClick(event);
            assertThat(event.isCancelled()).as("click type %s was allowed through", type).isTrue();
        }
    }

    @Test
    @DisplayName("a shift-click in the player's own half is cancelled: it would reach into the menu")
    void shiftClickIntoTheMenuIsCancelled() {
        PlayerMock player = server.addPlayer();
        ProbeMenu menu = openProbe(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));

        // Raw slot 27+ is the player's inventory in a 27-slot chest view. A shift-click here
        // would push the diamonds up into the menu if the event were not cancelled.
        InventoryClickEvent event = click(player, 30, ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY);
        listener.onClick(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(menu.clicks)
                .as("a click in the player's bag is not a button press and must not be dispatched")
                .isZero();
    }

    @Test
    @DisplayName("a double-click in the player's own half is cancelled: it collects the menu's icons")
    void doubleClickCollectingFromTheMenuIsCancelled() {
        PlayerMock player = server.addPlayer();
        openProbe(player);

        // COLLECT_TO_CURSOR gathers every matching stack in the whole view, the menu's own buttons
        // included, so a player could double-click their way to owning an icon.
        InventoryClickEvent event = click(player, 30, ClickType.DOUBLE_CLICK,
                InventoryAction.COLLECT_TO_CURSOR);
        listener.onClick(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("an ordinary click in the player's own bag is ALLOWED")
    void ordinaryPlayerInventoryClicksAreAllowed() {
        PlayerMock player = server.addPlayer();
        ProbeMenu menu = openProbe(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));

        // The bug this guards: cancelling every click while a menu was open froze the player's own
        // inventory. They could not move a crop into their hand with the shop in front of them,
        // which reads as the plugin having locked up.
        InventoryClickEvent event = click(player, 30, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);

        assertThat(event.isCancelled())
                .as("their items are their business")
                .isFalse();
        assertThat(menu.clicks)
                .as("still not a button press, so nothing is dispatched")
                .isZero();
    }

    @Test
    @DisplayName("a button that throws still leaves the click cancelled")
    void thrownButtonStillCancels() {
        PlayerMock player = server.addPlayer();
        ProbeMenu menu = openProbe(player);
        menu.explode = true;

        InventoryClickEvent event = click(player, 13, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);

        // Cancelling before dispatch is what guarantees this: a handler cannot leave a live
        // click behind by failing.
        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("clicks in someone else's inventory are left completely alone")
    void unrelatedInventoriesAreUntouched() {
        PlayerMock player = server.addPlayer();
        player.openInventory(server.createInventory(null, 27, Component.text("Not ours")));

        InventoryClickEvent event = click(player, 13, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(event);

        assertThat(event.isCancelled())
                .as("this plugin must not interfere with other plugins' menus")
                .isFalse();
    }

    @Test
    @DisplayName("opening tracks the menu and closing forgets it")
    void registryTracksLifecycle() {
        PlayerMock player = server.addPlayer();

        assertThat(menus.openCount()).isZero();
        openProbe(player);
        assertThat(menus.openCount()).isEqualTo(1);

        menus.untrack(player);
        assertThat(menus.openCount()).isZero();
    }

    @Test
    @DisplayName("closeAll empties the registry, so a disabled plugin leaves no unguarded menu")
    void closeAllClearsEverything() {
        openProbe(server.addPlayer());
        openProbe(server.addPlayer());
        assertThat(menus.openCount()).isEqualTo(2);

        menus.closeAll();

        assertThat(menus.openCount()).isZero();
    }

    @Test
    @DisplayName("a menu holds only icons, never a real sellable item")
    void menusHoldOnlyIcons() {
        PlayerMock player = server.addPlayer();
        ProbeMenu menu = openProbe(player);

        // Nothing in a menu carries this plugin's harvest tags, which is what makes
        // cancelling every click a complete defence rather than a partial one.
        for (ItemStack item : menu.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            assertThat(item.hasItemMeta()).isTrue();
            assertThat(item.getItemMeta().getPersistentDataContainer().getKeys())
                    .as("an icon must carry no harvest data")
                    .noneMatch(key -> key.getNamespace().contains("gardenshop"));
        }
    }
}
