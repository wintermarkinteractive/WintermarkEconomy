package io.github.wintermarkinteractive.economy;

import io.github.wintermarkinteractive.economy.api.EconomyResult;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Vault economy provider. Only loaded when Vault/VaultUnlocked is installed,
 * so the plugin still runs without the Vault classes.
 */
@SuppressWarnings("deprecation")
public final class VaultHook implements Economy {

    private static final String REASON = "VAULT";

    private final EconomyPlugin plugin;

    private VaultHook(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    static void register(EconomyPlugin plugin) {
        Bukkit.getServicesManager().register(Economy.class, new VaultHook(plugin), plugin, ServicePriority.High);
    }

    // ---------- lookups ----------

    private UUID id(OfflinePlayer player, boolean create) {
        if (player == null) return null;
        UUID id = player.getUniqueId();
        if (create && plugin.accounts().get(id) == null) {
            String name = player.getName() == null ? id.toString().substring(0, 16) : player.getName();
            plugin.accounts().ensure(id, name);
        }
        return id;
    }

    private UUID id(String name, boolean create) {
        if (name == null) return null;
        AccountManager.Account acc = plugin.accounts().findByName(name);
        if (acc != null) return acc.id();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return id(cached, create);
        return null;
    }

    private EconomyResponse response(EconomyResult r, double amount) {
        if (r.success()) {
            return new EconomyResponse(amount, r.balance().doubleValue(), EconomyResponse.ResponseType.SUCCESS, null);
        }
        String error = switch (r.status()) {
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case NO_ACCOUNT -> "Account not found";
            case MAX_BALANCE -> "Maximum balance reached";
            default -> "Invalid amount";
        };
        return new EconomyResponse(amount, r.balance().doubleValue(), EconomyResponse.ResponseType.FAILURE, error);
    }

    private EconomyResponse withdraw(UUID id, double amount) {
        if (id == null) return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, "Account not found");
        if (amount < 0) return new EconomyResponse(amount, getBal(id), EconomyResponse.ResponseType.FAILURE, "Cannot withdraw a negative amount");
        if (amount == 0) return new EconomyResponse(0, getBal(id), EconomyResponse.ResponseType.SUCCESS, null);
        return response(plugin.service().withdraw(id, BigDecimal.valueOf(amount), REASON), amount);
    }

    private EconomyResponse deposit(UUID id, double amount) {
        if (id == null) return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, "Account not found");
        if (amount < 0) return new EconomyResponse(amount, getBal(id), EconomyResponse.ResponseType.FAILURE, "Cannot deposit a negative amount");
        if (amount == 0) return new EconomyResponse(0, getBal(id), EconomyResponse.ResponseType.SUCCESS, null);
        return response(plugin.service().deposit(id, BigDecimal.valueOf(amount), REASON), amount);
    }

    private double getBal(UUID id) {
        return id == null ? 0 : plugin.service().getBalance(id).doubleValue();
    }

    private boolean hasAmount(UUID id, double amount) {
        return id != null && plugin.service().has(id, BigDecimal.valueOf(Math.max(0, amount)));
    }

    // ---------- info ----------

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "WintermarkEconomy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return plugin.money().decimals(); }
    @Override public String format(double amount) { return plugin.service().format(BigDecimal.valueOf(amount)); }
    @Override public String currencyNamePlural() { return plugin.money().plural(); }
    @Override public String currencyNameSingular() { return plugin.money().singular(); }

    // ---------- accounts ----------

    @Override public boolean hasAccount(String name) { UUID id = id(name, false); return id != null && plugin.accounts().get(id) != null; }
    @Override public boolean hasAccount(OfflinePlayer p) { return p != null && plugin.accounts().get(p.getUniqueId()) != null; }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public boolean createPlayerAccount(String name) { return id(name, true) != null; }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return id(p, true) != null; }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }

    // ---------- balance ----------

    @Override public double getBalance(String name) { return getBal(id(name, false)); }
    @Override public double getBalance(OfflinePlayer p) { return getBal(id(p, false)); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return hasAmount(id(name, false), amount); }
    @Override public boolean has(OfflinePlayer p, double amount) { return hasAmount(id(p, false), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdraw(id(name, true), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return withdraw(id(p, true), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(id(name, true), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return deposit(id(p, true), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    // ---------- banks (not supported) ----------

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Wintermark Economy has no banks");
    }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }
}
