package io.github.wintermarkinteractive.economy;

import io.github.wintermarkinteractive.economy.api.EconomyAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class EconomyPlugin extends JavaPlugin implements Listener {

    private Database database;
    private AccountManager accounts;
    private BaltopCache baltop;
    private EconomyService service;
    private NoteManager notes;
    private HistoryView history;
    private PayCommand payCommand;
    private BukkitTask baltopTask;

    private int decimals;
    private volatile MoneyFormat money;
    private volatile Messages messages;
    private volatile long payMinimum;
    private volatile boolean payAllowOffline;
    private volatile long payConfirmAbove;
    private volatile int payConfirmSeconds;
    private volatile int payCooldownSeconds;
    private volatile long noteMinimum;
    private volatile long noteMaximum;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingConfigKeys();
        decimals = MoneyFormat.configDecimals(getConfig());

        database = new Database(new File(getDataFolder(), "economy.db"), getLogger());
        accounts = new AccountManager(database);
        notes = new NoteManager(this);
        history = new HistoryView(this);
        try {
            database.open(decimals);
            accounts.load(database.loadAll());
            notes.load(database.loadOpenNotes());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open economy.db - disabling to protect your data.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        baltop = new BaltopCache(accounts);
        applyConfig();
        database.cleanup(getConfig().getInt("history.keep-days", 90));

        for (Player p : Bukkit.getOnlinePlayers()) accounts.ensure(p.getUniqueId(), p.getName());

        service = new EconomyService(this);
        getServer().getServicesManager().register(EconomyAPI.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new NoteListener(this), this);

        registerCommand("balance", "Show your balance or another player's", List.of("bal", "money"),
                new BalanceCommand(this));
        payCommand = new PayCommand(this);
        registerCommand("pay", "Send money to another player", List.of(), payCommand);
        registerCommand("paytoggle", "Allow or block payments from other players", List.of(),
                new PayToggleCommand(this));
        registerCommand("withdraw", "Turn money into a banknote", List.of("banknote"), new WithdrawCommand(this));
        registerCommand("baltop", "Richest players", List.of("balancetop", "moneytop"), new BaltopCommand(this));
        registerCommand("eco", "Economy admin commands", List.of("economy", "weco"), new EcoCommand(this));

        Long sample = money.toMinor(new java.math.BigDecimal("1234567.5"));
        hookVault();
        getLogger().info("Loaded " + accounts.size() + " accounts, " + notes.openCount() + " open banknotes. Format example: "
                + money.format(sample == null ? 0 : sample) + " / " + money.formatCompact(sample == null ? 0 : sample));
    }

    @Override
    public void onDisable() {
        if (baltopTask != null) baltopTask.cancel();
        getServer().getServicesManager().unregisterAll(this);
        if (database != null) database.close();
    }

    // ---------- config ----------

    private void applyConfig() {
        FileConfiguration cfg = getConfig();
        money = new MoneyFormat(cfg, decimals);
        messages = new Messages(cfg);
        accounts.setLimits(configAmount("starting-balance", "100"), configAmount("max-balance", "100t"));
        payMinimum = configAmount("pay.min-amount", "1");
        payAllowOffline = cfg.getBoolean("pay.allow-offline", true);
        payConfirmAbove = configAmount("pay.confirm-above", "10k");
        payConfirmSeconds = Math.max(5, cfg.getInt("pay.confirm-seconds", 30));
        payCooldownSeconds = Math.max(0, cfg.getInt("pay.cooldown-seconds", 3));
        noteMinimum = configAmount("banknotes.min-amount", "10");
        noteMaximum = configAmount("banknotes.max-amount", "0");

        if (baltopTask != null) baltopTask.cancel();
        long seconds = Math.max(10, cfg.getLong("baltop.refresh-seconds", 300));
        baltopTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, baltop::refresh, 20L, seconds * 20L);
    }

    /** Reads an amount like "100", "2.5k" or "0" from the config, in minor units. */
    private long configAmount(String path, String fallback) {
        String raw = String.valueOf(getConfig().get(path, fallback)).trim();
        if (raw.equals("0") || raw.equals("0.0")) return 0;
        Long v = money.parse(raw);
        if (v == null) {
            getLogger().warning("Invalid amount '" + raw + "' at " + path + ", using " + fallback);
            v = money.parse(fallback);
        }
        return v == null ? 0 : v;
    }

    public void reload(CommandSender sender) {
        reloadConfig();
        addMissingConfigKeys();
        int wanted = MoneyFormat.configDecimals(getConfig());
        applyConfig();
        if (wanted != decimals) {
            messages.send(sender, "decimals-restart");
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, baltop::refresh);
    }

    /** Adds keys that are new in this version to an existing config.yml, with their comments. */
    private void addMissingConfigKeys() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration defaults;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                defaults = YamlConfiguration.loadConfiguration(r);
            }
            FileConfiguration cfg = getConfig();
            Set<String> newSections = new HashSet<>();
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) && !cfg.contains(key, true)) newSections.add(key);
            }
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) continue;
                if (!cfg.contains(key, true)) {
                    cfg.set(key, defaults.get(key));
                    cfg.setComments(key, defaults.getComments(key));
                    changed = true;
                }
            }
            for (String key : newSections) cfg.setComments(key, defaults.getComments(key));
            if (changed) {
                saveConfig();
                reloadConfig();
                getLogger().info("Added new settings to config.yml.");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not update config.yml with new keys", e);
        }
    }

    // ---------- events ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        accounts.ensure(p.getUniqueId(), p.getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (payCommand != null) payCommand.forget(event.getPlayer().getUniqueId());
    }

    // ---------- Vault ----------

    /** Registers us as Vault's economy when Vault or VaultUnlocked is installed. */
    private void hookVault() {
        if (!getConfig().getBoolean("vault.enabled", true)) return;
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault/VaultUnlocked not found - running without the Vault hook (that's fine).");
            return;
        }
        try {
            VaultHook.register(this);
            getLogger().info("Registered as the Vault economy provider. Other plugins can now use Wintermark Economy.");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Could not register with Vault", t);
        }
    }

    // ---------- helpers for commands ----------

    /**
     * Finds an account by player name: online players first, then stored accounts,
     * then players who joined before this plugin was installed (they get an account now).
     */
    public AccountManager.Account resolveTarget(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            accounts.ensure(online.getUniqueId(), online.getName());
            return accounts.get(online.getUniqueId());
        }
        AccountManager.Account acc = accounts.findByName(name);
        if (acc != null) return acc;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore() && cached.getName() != null) {
            accounts.ensure(cached.getUniqueId(), cached.getName());
            return accounts.get(cached.getUniqueId());
        }
        return null;
    }

    public List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    public static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    // ---------- getters ----------

    public AccountManager accounts() { return accounts; }
    public MoneyFormat money() { return money; }
    public Messages messages() { return messages; }
    public BaltopCache baltop() { return baltop; }
    public long payMinimum() { return payMinimum; }
    public boolean payAllowOffline() { return payAllowOffline; }
    public EconomyAPI api() { return service; }
    public EconomyService service() { return service; }
    public Database database() { return database; }
    public NoteManager notes() { return notes; }
    public HistoryView history() { return history; }
    public long payConfirmAbove() { return payConfirmAbove; }
    public int payConfirmSeconds() { return payConfirmSeconds; }
    public int payCooldownSeconds() { return payCooldownSeconds; }
    public long noteMinimum() { return noteMinimum; }
    public long noteMaximum() { return noteMaximum; }
}
