package io.github.wintermarkinteractive.economy;

import io.github.wintermarkinteractive.economy.api.EconomyAPI;
import io.github.wintermarkinteractive.economy.api.EconomyResult;
import io.github.wintermarkinteractive.economy.api.EconomyResult.Status;
import io.github.wintermarkinteractive.economy.api.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

/** The EconomyAPI that other plugins get from the ServicesManager. */
public final class EconomyService implements EconomyAPI {

    private final EconomyPlugin plugin;

    public EconomyService(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    private AccountManager accounts() { return plugin.accounts(); }
    private MoneyFormat fmt() { return plugin.money(); }

    @Override
    public boolean hasAccount(UUID player) {
        return player != null && accounts().get(player) != null;
    }

    @Override
    public boolean createAccount(UUID player, String name) {
        if (player == null) return false;
        if (accounts().get(player) != null) return false;
        return accounts().ensure(player, name == null || name.isEmpty() ? player.toString() : name);
    }

    @Override
    public BigDecimal getBalance(UUID player) {
        if (player == null) return BigDecimal.ZERO;
        return fmt().toUnits(accounts().balance(player));
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        Long minor = fmt().toMinor(amount);
        return player != null && minor != null && accounts().balance(player) >= minor;
    }

    @Override
    public EconomyResult deposit(UUID player, BigDecimal amount, String reason) {
        Long minor = fmt().toMinor(amount);
        if (player == null || minor == null || minor <= 0) return invalid(player, amount);
        EconomyResult r = wrap(accounts().deposit(player, minor), amount);
        if (r.success()) log(reason, null, player, minor);
        return r;
    }

    @Override
    public EconomyResult withdraw(UUID player, BigDecimal amount, String reason) {
        Long minor = fmt().toMinor(amount);
        if (player == null || minor == null || minor <= 0) return invalid(player, amount);
        EconomyResult r = wrap(accounts().withdraw(player, minor), amount);
        if (r.success()) log(reason, player, null, minor);
        return r;
    }

    @Override
    public EconomyResult transfer(UUID from, UUID to, BigDecimal amount, String reason) {
        Long minor = fmt().toMinor(amount);
        if (from == null || to == null || minor == null || minor <= 0) return invalid(from, amount);
        EconomyResult r = wrap(accounts().transfer(from, to, minor), amount);
        if (r.success()) log(reason, from, to, minor);
        return r;
    }

    @Override
    public String format(BigDecimal amount) {
        Long minor = fmt().toMinor(amount);
        return minor == null ? String.valueOf(amount) : fmt().format(minor);
    }

    @Override
    public String formatCompact(BigDecimal amount) {
        Long minor = fmt().toMinor(amount);
        return minor == null ? String.valueOf(amount) : fmt().formatCompact(minor);
    }

    @Override
    public BigDecimal parse(String input) {
        Long minor = fmt().parse(input);
        return minor == null ? null : fmt().toUnits(minor);
    }

    @Override public int getDecimals() { return fmt().decimals(); }
    @Override public String getCurrencySingular() { return fmt().singular(); }
    @Override public String getCurrencyPlural() { return fmt().plural(); }
    @Override public String getCurrencySymbol() { return fmt().symbol(); }

    /** Writes a history entry; the reason may start with a type, e.g. "BOUNTY: claimed Steve". */
    private void log(String reason, UUID from, UUID to, long minor) {
        TransactionType.Parsed p = TransactionType.parse(reason);
        String source = p.type() == TransactionType.API ? "API" : p.type().name().charAt(0)
                + p.type().name().substring(1).toLowerCase(java.util.Locale.ROOT);
        plugin.database().logTransaction(p.type().name(),
                from, from == null ? source : nameOf(from),
                to, to == null ? source : nameOf(to),
                minor, p.note().isEmpty() ? null : p.note());
    }

    private String nameOf(UUID id) {
        AccountManager.Account a = accounts().get(id);
        return a == null ? id.toString().substring(0, 8) : a.name();
    }

    private EconomyResult invalid(UUID player, BigDecimal amount) {
        return new EconomyResult(Status.INVALID_AMOUNT, amount, getBalance(player));
    }

    private EconomyResult wrap(AccountManager.Change c, BigDecimal amount) {
        return new EconomyResult(c.status(), amount, fmt().toUnits(c.balance()));
    }
}
