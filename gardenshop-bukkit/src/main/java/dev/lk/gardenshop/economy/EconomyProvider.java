package dev.lk.gardenshop.economy;

import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;

/**
 * Somewhere to put the money.
 *
 * <p>The plugin never talks to an economy plugin directly. Vault covers EssentialsX,
 * CMI, CoinsEngine, ExcellentEconomy and most of the rest, but not everything —
 * PlayerPoints and several token plugins register no Vault service at all — and
 * Vault's own API is stuck on {@code double} with no multi-currency support. This
 * interface is the seam that lets any of those be added later without the sell flow
 * noticing.
 *
 * <p>Implementations must assume they are called on the main server thread: most
 * Vault providers, EssentialsX included, are not thread-safe.
 */
public interface EconomyProvider {

    /** Short id for {@code /gs info}, e.g. {@code vault}. */
    String id();

    /** A human-readable description of what is actually backing this, for diagnostics. */
    String description();

    /** Whether payouts can happen right now. */
    boolean isAvailable();

    /** Credits a player. Never throws: problems come back as a failed result. */
    DepositResult deposit(OfflinePlayer player, BigDecimal amount);

    /** Formats an amount the way the backing economy would. */
    String format(BigDecimal amount);

    String currencyName();
}
