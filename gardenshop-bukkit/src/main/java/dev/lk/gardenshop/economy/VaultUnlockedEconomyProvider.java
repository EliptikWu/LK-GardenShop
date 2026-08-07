package dev.lk.gardenshop.economy;

import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Payouts through VaultUnlocked, the modern Vault fork.
 *
 * <p>Preferred over classic Vault when present, for one reason that matters here:
 * {@link BigDecimal} all the way through. Classic Vault's API is {@code double}-only, so
 * {@link VaultEconomyProvider} has to refuse any payout above 2^53 to avoid crediting a
 * different number than the one it quoted. This provider has no such ceiling.
 *
 * <p>It also supports named currencies, so a garden can pay out in "Sheckles" while the
 * rest of the server trades in the default currency. Set {@code economy.currency} to use it.
 *
 * <p>Still main-thread only: VaultUnlocked declares no thread-safety guarantee for the
 * economy plugins behind it either.
 */
public final class VaultUnlockedEconomyProvider implements EconomyProvider {

    /** Vault2 attributes every transaction to the plugin that made it. */
    private static final String PLUGIN_NAME = "LKGardenShop";

    private final Economy economy;
    private final Logger logger;

    /** Empty means "the backend's default currency". */
    private final String currency;

    private VaultUnlockedEconomyProvider(Economy economy, Logger logger, String currency) {
        this.economy = economy;
        this.logger = logger;
        this.currency = currency;
    }

    /**
     * @param currency a specific currency to pay in, or blank for the default
     * @return empty when VaultUnlocked is absent or nothing has registered a Vault2 economy
     */
    public static Optional<VaultUnlockedEconomyProvider> hook(Server server, Logger logger, String currency) {
        // The class check matters independently of the plugin check: VaultUnlocked can be
        // installed as a drop-in replacement under the name "Vault".
        if (!isApiPresent()) {
            return Optional.empty();
        }

        RegisteredServiceProvider<Economy> registration =
                server.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return Optional.empty();
        }
        Economy economy = registration.getProvider();
        if (economy == null || !economy.isEnabled()) {
            return Optional.empty();
        }

        String resolved = currency == null ? "" : currency.trim();
        if (!resolved.isEmpty()) {
            logger.info("Paying out in the '" + resolved + "' currency via VaultUnlocked.");
        }
        return Optional.of(new VaultUnlockedEconomyProvider(economy, logger, resolved));
    }

    /** Whether the Vault2 API is even on the classpath, checked without risking a linkage error. */
    public static boolean isApiPresent() {
        try {
            Class.forName("net.milkbowl.vault2.economy.Economy");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String id() {
        return "vault-unlocked";
    }

    @Override
    public String description() {
        String backend = "VaultUnlocked -> " + safeName();
        return currency.isEmpty() ? backend : backend + " (" + currency + ")";
    }

    @Override
    public boolean isAvailable() {
        try {
            return economy.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public DepositResult deposit(OfflinePlayer player, BigDecimal amount) {
        if (!Bukkit.isPrimaryThread()) {
            logger.severe("Refused an off-thread deposit for " + player.getName()
                    + " — this is a bug, payouts must happen on the main thread.");
            return DepositResult.failure("payout attempted off the main thread");
        }
        if (amount.signum() <= 0) {
            return DepositResult.failure("amount must be positive");
        }

        try {
            if (!economy.hasAccount(player.getUniqueId())) {
                String name = player.getName() == null ? player.getUniqueId().toString() : player.getName();
                economy.createAccount(player.getUniqueId(), name, true);
            }

            EconomyResponse response = currency.isEmpty()
                    ? economy.deposit(PLUGIN_NAME, player.getUniqueId(), amount)
                    : economy.deposit(PLUGIN_NAME, player.getUniqueId(),
                            defaultWorldName(player), currency, amount);

            if (response == null) {
                return DepositResult.failure(safeName() + " returned no response");
            }
            if (!response.transactionSuccess()) {
                String reason = response.errorMessage == null || response.errorMessage.isBlank()
                        ? String.valueOf(response.type)
                        : response.errorMessage;
                return DepositResult.failure(safeName() + " rejected the deposit: " + reason);
            }
            return DepositResult.success(amount);
        } catch (Exception e) {
            logger.warning("Deposit through " + safeName() + " threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return DepositResult.failure(safeName() + " threw " + e.getClass().getSimpleName());
        }
    }

    @Override
    public String format(BigDecimal amount) {
        try {
            // The plugin-name overloads, not the deprecated bare ones: Vault2 lets an
            // economy format differently per calling plugin.
            return currency.isEmpty()
                    ? economy.format(PLUGIN_NAME, amount)
                    : economy.format(PLUGIN_NAME, amount, currency);
        } catch (Exception e) {
            return amount.toPlainString();
        }
    }

    @Override
    public String currencyName() {
        try {
            String plural = currency.isEmpty()
                    ? economy.defaultCurrencyNamePlural(PLUGIN_NAME)
                    : currency;
            return plural == null || plural.isBlank() ? "coins" : plural;
        } catch (Exception e) {
            return "coins";
        }
    }

    /**
     * The per-world currency overload needs a world name. Payouts are not world-scoped
     * here, so the player's current world is the honest answer.
     */
    private String defaultWorldName(OfflinePlayer player) {
        if (player.getPlayer() != null) {
            return player.getPlayer().getWorld().getName();
        }
        return Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().getFirst().getName();
    }

    private String safeName() {
        try {
            String name = economy.getName();
            return name == null || name.isBlank() ? "unknown" : name;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
