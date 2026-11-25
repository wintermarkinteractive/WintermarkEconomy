package io.github.wintermarkinteractive.economy.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public API of Wintermark Economy.
 * <p>
 * Get it from Bukkit's ServicesManager:
 * <pre>{@code
 * RegisteredServiceProvider<EconomyAPI> rsp =
 *         Bukkit.getServicesManager().getRegistration(EconomyAPI.class);
 * EconomyAPI eco = rsp == null ? null : rsp.getProvider();
 * }</pre>
 * All methods are thread-safe. Amounts are in currency units (e.g. 12.50) and are rounded
 * down to the configured number of decimals.
 */
public interface EconomyAPI {

    boolean hasAccount(UUID player);

    /** Creates an account with the starting balance. Returns false if it already existed. */
    boolean createAccount(UUID player, String name);

    /** Balance in currency units, or zero when the player has no account. */
    BigDecimal getBalance(UUID player);

    boolean has(UUID player, BigDecimal amount);

    EconomyResult deposit(UUID player, BigDecimal amount, String reason);

    EconomyResult withdraw(UUID player, BigDecimal amount, String reason);

    /** Moves money between two accounts in one step (both change or neither). */
    EconomyResult transfer(UUID from, UUID to, BigDecimal amount, String reason);

    /** Exact display, e.g. "$1,234.50". */
    String format(BigDecimal amount);

    /** Short display, e.g. "$1.2M". */
    String formatCompact(BigDecimal amount);

    /** Parses player input like "250", "1.5k", "2m", "1,000". Returns null when invalid or not positive. */
    BigDecimal parse(String input);

    int getDecimals();

    String getCurrencySingular();

    String getCurrencyPlural();

    String getCurrencySymbol();

    default BigDecimal getBalanceOrZero(UUID player) {
        BigDecimal b = getBalance(player);
        return b == null ? BigDecimal.ZERO : b;
    }

    default EconomyResult deposit(UUID player, double amount, String reason) {
        return deposit(player, BigDecimal.valueOf(amount), reason);
    }

    default EconomyResult withdraw(UUID player, double amount, String reason) {
        return withdraw(player, BigDecimal.valueOf(amount), reason);
    }

    default boolean has(UUID player, double amount) {
        return has(player, BigDecimal.valueOf(amount));
    }
}
