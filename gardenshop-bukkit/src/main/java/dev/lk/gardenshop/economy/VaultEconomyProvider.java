package dev.lk.gardenshop.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Payouts through Vault — the bridge nearly every economy plugin registers with.
 *
 * <h2>Three sharp edges in Vault's API, handled here</h2>
 * <ol>
 *   <li><b>Not thread-safe.</b> EssentialsX and others expect the main thread, so
 *       {@link #deposit} refuses to run anywhere else rather than corrupting a
 *       balance file.</li>
 *   <li><b>{@code double} only.</b> The plugin does its arithmetic in
 *       {@link BigDecimal} and converts here, rejecting anything that cannot survive
 *       the trip instead of silently crediting {@code Infinity}.</li>
 *   <li><b>Success is not the absence of an exception.</b> Only
 *       {@link EconomyResponse#transactionSuccess()} is trustworthy; some providers
 *       return {@code NOT_IMPLEMENTED} without complaint.</li>
 * </ol>
 */
public final class VaultEconomyProvider implements EconomyProvider {

    /**
     * Beyond 2^53 a double can no longer represent whole numbers exactly, so a
     * payout above this would credit a different amount than the one quoted.
     */
    private static final BigDecimal PRECISION_LIMIT = BigDecimal.valueOf(9_007_199_254_740_992L);

    private final Economy economy;
    private final Logger logger;

    private VaultEconomyProvider(Economy economy, Logger logger) {
        this.economy = economy;
        this.logger = logger;
    }

    /** @return empty when Vault is absent or no economy plugin has registered with it */
    public static Optional<VaultEconomyProvider> hook(Server server, Logger logger) {
        if (server.getPluginManager().getPlugin("Vault") == null) {
            return Optional.empty();
        }
        RegisteredServiceProvider<Economy> registration =
                server.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            logger.warning("Vault is installed but no economy plugin has registered with it. "
                    + "Install one (EssentialsX, CMI, CoinsEngine, ...) for selling to pay out.");
            return Optional.empty();
        }
        Economy economy = registration.getProvider();
        if (economy == null || !economy.isEnabled()) {
            logger.warning("Vault's registered economy provider is not enabled yet.");
            return Optional.empty();
        }
        return Optional.of(new VaultEconomyProvider(economy, logger));
    }

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public String description() {
        return "Vault -> " + economy.getName();
    }

    @Override
    public boolean isAvailable() {
        return economy.isEnabled();
    }

    @Override
    public DepositResult deposit(OfflinePlayer player, BigDecimal amount) {
        if (!Bukkit.isPrimaryThread()) {
            // Failing loudly is better than a corrupted balance: most Vault
            // providers offer no thread-safety guarantee whatsoever.
            logger.severe("Refused an off-thread Vault deposit for " + player.getName()
                    + " — this is a bug, payouts must happen on the main thread.");
            return DepositResult.failure("payout attempted off the main thread");
        }
        if (amount.signum() <= 0) {
            return DepositResult.failure("amount must be positive");
        }
        if (amount.compareTo(PRECISION_LIMIT) > 0) {
            return DepositResult.failure("amount " + amount.toPlainString()
                    + " exceeds what Vault can represent exactly — lower max-payout-per-sale");
        }

        double value = amount.doubleValue();
        if (!Double.isFinite(value)) {
            return DepositResult.failure("amount is not a finite number");
        }

        try {
            if (!economy.hasAccount(player)) {
                economy.createPlayerAccount(player);
            }
            EconomyResponse response = economy.depositPlayer(player, value);
            if (response == null) {
                return DepositResult.failure(economy.getName() + " returned no response");
            }
            if (!response.transactionSuccess()) {
                String reason = response.errorMessage == null || response.errorMessage.isBlank()
                        ? response.type.name()
                        : response.errorMessage;
                return DepositResult.failure(economy.getName() + " rejected the deposit: " + reason);
            }
            return DepositResult.success(amount);
        } catch (Exception e) {
            // A third-party economy throwing is not something we can fix, but it must
            // not escape into the sell flow half-done.
            logger.warning("Deposit through " + economy.getName() + " threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return DepositResult.failure(economy.getName() + " threw " + e.getClass().getSimpleName());
        }
    }

    @Override
    public String format(BigDecimal amount) {
        try {
            return economy.format(amount.doubleValue());
        } catch (Exception e) {
            // Some providers' format() misbehaves for edge values; a plain number
            // beats an exception in the middle of a success message.
            return amount.toPlainString();
        }
    }

    @Override
    public String currencyName() {
        try {
            String plural = economy.currencyNamePlural();
            return plural == null || plural.isBlank() ? "coins" : plural;
        } catch (Exception e) {
            return "coins";
        }
    }
}
