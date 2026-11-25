package io.github.wintermarkinteractive.economy.api;

import java.math.BigDecimal;

/**
 * Result of a money change made through {@link EconomyAPI}.
 *
 * @param status  what happened
 * @param amount  the amount that was requested (in currency units, e.g. 12.50)
 * @param balance the balance of the (first) account after the call; unchanged when it failed
 */
public record EconomyResult(Status status, BigDecimal amount, BigDecimal balance) {

    public enum Status {
        SUCCESS,
        NO_ACCOUNT,
        INSUFFICIENT_FUNDS,
        INVALID_AMOUNT,
        MAX_BALANCE
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }
}
