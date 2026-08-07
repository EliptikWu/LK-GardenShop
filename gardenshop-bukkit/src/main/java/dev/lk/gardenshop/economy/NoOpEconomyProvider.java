package dev.lk.gardenshop.economy;

import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;

/**
 * The provider used when there is nowhere to send money.
 *
 * <p>It exists so a missing Vault is a disabled feature rather than a failed startup:
 * the server boots, the console says why, and {@code /gs value} still works for
 * tuning prices. {@link #isAvailable()} returns {@code false}, so the sell flow stops
 * before it takes anyone's items.
 *
 * @param reason why payouts are unavailable, shown by {@code /gs info}
 */
public record NoOpEconomyProvider(String reason) implements EconomyProvider {

    @Override
    public String id() {
        return "none";
    }

    @Override
    public String description() {
        return "no payouts (" + reason + ")";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public DepositResult deposit(OfflinePlayer player, BigDecimal amount) {
        return DepositResult.failure(reason);
    }

    @Override
    public String format(BigDecimal amount) {
        return amount.toPlainString();
    }

    @Override
    public String currencyName() {
        return "coins";
    }
}
