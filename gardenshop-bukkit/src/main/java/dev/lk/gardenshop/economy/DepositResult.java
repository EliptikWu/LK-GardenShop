package dev.lk.gardenshop.economy;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Outcome of a payout attempt.
 *
 * <p>A failure here is not cosmetic: the sell flow refunds the items it already took
 * when it sees one, so this must never report success optimistically.
 *
 * @param amount how much was actually credited
 * @param error  why it failed; empty on success
 */
public record DepositResult(boolean success, BigDecimal amount, String error) {

    public DepositResult {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(error, "error");
    }

    public static DepositResult success(BigDecimal amount) {
        return new DepositResult(true, amount, "");
    }

    public static DepositResult failure(String error) {
        return new DepositResult(false, BigDecimal.ZERO, error);
    }
}
