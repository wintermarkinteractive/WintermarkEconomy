package io.github.wintermarkinteractive.economy;

import io.github.wintermarkinteractive.economy.api.TransactionType;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static io.github.wintermarkinteractive.economy.Messages.text;

/**
 * /eco give|take|set|reset|history|reload|help
 * Works from the console, so other plugins can pay out with "eco give <player> <amount>".
 */
public final class EcoCommand implements BasicCommand {

    private static final List<String> SUBS = List.of("give", "take", "set", "reset", "history", "reload", "help");

    private final EconomyPlugin plugin;

    public EcoCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Messages msg = plugin.messages();

        if (args.length == 0) {
            msg.send(sender, "eco-help");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give", "add" -> change(sender, args, Mode.GIVE);
            case "take", "remove" -> change(sender, args, Mode.TAKE);
            case "set" -> change(sender, args, Mode.SET);
            case "reset" -> reset(sender, args);
            case "history" -> history(sender, args);
            case "reload" -> {
                plugin.reload(sender);
                msg = plugin.messages();
                msg.send(sender, "reloaded");
            }
            default -> msg.send(sender, "eco-help");
        }
    }

    private enum Mode { GIVE, TAKE, SET }

    private void change(CommandSender sender, String[] args, Mode mode) {
        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();
        AccountManager accounts = plugin.accounts();

        if (args.length != 3) {
            msg.send(sender, "eco-help");
            return;
        }

        Long amount;
        if (mode == Mode.SET && args[2].trim().equals("0")) {
            amount = 0L;
        } else {
            amount = fmt.parse(args[2]);
        }
        if (amount == null) {
            msg.send(sender, "invalid-amount", text("input", args[2]));
            return;
        }

        // "*" = every online player (give only)
        if (args[1].equals("*")) {
            if (mode != Mode.GIVE) {
                msg.send(sender, "eco-all-give-only");
                return;
            }
            int count = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                accounts.ensure(p.getUniqueId(), p.getName());
                AccountManager.Change c = accounts.deposit(p.getUniqueId(), amount);
                if (c.ok()) {
                    count++;
                    log(sender, Mode.GIVE, p.getUniqueId(), p.getName(), amount);
                    msg.send(p, "eco-target-give", text("amount", fmt.format(amount)),
                            text("balance", fmt.format(c.balance())));
                }
            }
            msg.send(sender, "eco-give-all", text("amount", fmt.format(amount)),
                    text("count", String.valueOf(count)));
            return;
        }

        AccountManager.Account target = plugin.resolveTarget(args[1]);
        if (target == null) {
            msg.send(sender, "player-not-found", text("player", args[1]));
            return;
        }

        AccountManager.Change c = switch (mode) {
            case GIVE -> accounts.deposit(target.id(), amount);
            case TAKE -> accounts.withdraw(target.id(), amount);
            case SET -> accounts.set(target.id(), amount);
        };

        Player online = Bukkit.getPlayer(target.id());
        switch (c.status()) {
            case SUCCESS -> {
                log(sender, mode, target.id(), target.name(), mode == Mode.SET ? c.balance() : amount);
                String key = switch (mode) {
                    case GIVE -> "eco-give";
                    case TAKE -> "eco-take";
                    case SET -> "eco-set";
                };
                msg.send(sender, key, text("player", target.name()),
                        text("amount", fmt.format(amount)), text("balance", fmt.format(c.balance())));
                if (online != null && online != sender) {
                    String tkey = switch (mode) {
                        case GIVE -> "eco-target-give";
                        case TAKE -> "eco-target-take";
                        case SET -> "eco-target-set";
                    };
                    msg.send(online, tkey, text("amount", fmt.format(amount)),
                            text("balance", fmt.format(c.balance())));
                }
            }
            case INSUFFICIENT_FUNDS -> msg.send(sender, "eco-take-insufficient",
                    text("player", target.name()), text("balance", fmt.format(c.balance())));
            case MAX_BALANCE -> msg.send(sender, "max-balance",
                    text("player", target.name()), text("max", fmt.format(accounts.maxBalance())));
            default -> msg.send(sender, "player-not-found", text("player", args[1]));
        }
    }

    private void reset(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();
        if (args.length != 2) {
            msg.send(sender, "eco-help");
            return;
        }
        AccountManager.Account target = plugin.resolveTarget(args[1]);
        if (target == null) {
            msg.send(sender, "player-not-found", text("player", args[1]));
            return;
        }
        long start = plugin.accounts().startingBalance();
        plugin.accounts().set(target.id(), start);
        plugin.database().logTransaction(TransactionType.ADMIN.name(), null, senderName(sender),
                target.id(), target.name(), start, "set (reset)");
        msg.send(sender, "eco-reset", text("player", target.name()), text("balance", fmt.format(start)));
        Player online = Bukkit.getPlayer(target.id());
        if (online != null && online != sender) {
            msg.send(online, "eco-target-set", text("amount", fmt.format(start)),
                    text("balance", fmt.format(start)));
        }
    }

    private void history(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(sender, "eco-help");
            return;
        }
        AccountManager.Account target = plugin.resolveTarget(args[1]);
        if (target == null) {
            msg.send(sender, "player-not-found", text("player", args[1]));
            return;
        }
        int page = 1;
        if (args.length > 2) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        plugin.history().show(sender, target.id(), target.name(), page, "/eco history " + target.name());
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player p ? p.getName() : "Console";
    }

    private void log(CommandSender sender, Mode mode, java.util.UUID id, String name, long amount) {
        String type = TransactionType.ADMIN.name();
        switch (mode) {
            case GIVE -> plugin.database().logTransaction(type, null, senderName(sender), id, name, amount, "give");
            case TAKE -> plugin.database().logTransaction(type, id, name, null, senderName(sender), amount, "take");
            case SET -> plugin.database().logTransaction(type, null, senderName(sender), id, name, amount, "set");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1];
        int pos = Math.max(1, args.length);
        if (pos == 1) return EconomyPlugin.filter(new ArrayList<>(SUBS), last);
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean needsPlayer = List.of("give", "add", "take", "remove", "set", "reset", "history").contains(sub);
        if (pos == 2 && needsPlayer) {
            List<String> names = plugin.onlineNames();
            if (sub.equals("give") || sub.equals("add")) names.add("*");
            return EconomyPlugin.filter(names, last);
        }
        if (pos == 3 && needsPlayer && !sub.equals("reset") && !sub.equals("history") && last.isEmpty()) {
            return List.of("100", "1k", "10k", "1m");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "wintermark.eco.admin";
    }
}
