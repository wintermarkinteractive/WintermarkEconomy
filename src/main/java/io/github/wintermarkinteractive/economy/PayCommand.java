package io.github.wintermarkinteractive.economy;

import io.github.wintermarkinteractive.economy.api.TransactionType;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.wintermarkinteractive.economy.Messages.text;

/**
 * /pay <player> <amount>  and  /pay confirm
 * Safety: minimum amount, no self-pay, /paytoggle, cooldown, click-to-confirm above a set amount.
 */
public final class PayCommand implements BasicCommand {

    private record Pending(UUID target, long amount, long expiresAt) { }

    private final EconomyPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPay = new ConcurrentHashMap<>();

    public PayCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();

        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return;
        }

        // /pay confirm
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            Pending p = pending.remove(player.getUniqueId());
            if (p == null || p.expiresAt() < System.currentTimeMillis()) {
                msg.send(sender, "pay-confirm-none");
                return;
            }
            AccountManager.Account target = plugin.accounts().get(p.target());
            if (target == null) {
                msg.send(sender, "pay-confirm-none");
                return;
            }
            if (checksFail(player, target)) return;
            doPay(player, target, p.amount());
            return;
        }

        if (args.length != 2) {
            msg.send(sender, "usage-pay");
            return;
        }

        Long amount = fmt.parse(args[1]);
        if (amount == null) {
            msg.send(sender, "invalid-amount", text("input", args[1]));
            return;
        }
        if (amount < plugin.payMinimum()) {
            msg.send(sender, "pay-min", text("min", fmt.format(plugin.payMinimum())));
            return;
        }

        AccountManager.Account target = plugin.resolveTarget(args[0]);
        if (target == null) {
            msg.send(sender, "player-not-found", text("player", args[0]));
            return;
        }
        if (target.id().equals(player.getUniqueId())) {
            msg.send(sender, "pay-self");
            return;
        }
        if (checksFail(player, target)) return;

        plugin.accounts().ensure(player.getUniqueId(), player.getName());
        long confirmAbove = plugin.payConfirmAbove();
        if (confirmAbove > 0 && amount >= confirmAbove) {
            if (plugin.accounts().balance(player.getUniqueId()) < amount) {
                msg.send(sender, "insufficient-funds", text("amount", fmt.format(amount)),
                        text("balance", fmt.format(plugin.accounts().balance(player.getUniqueId()))));
                return;
            }
            int seconds = plugin.payConfirmSeconds();
            pending.put(player.getUniqueId(),
                    new Pending(target.id(), amount, System.currentTimeMillis() + seconds * 1000L));
            msg.send(sender, "pay-confirm",
                    text("player", target.name()),
                    text("amount", fmt.format(amount)),
                    text("seconds", String.valueOf(seconds)),
                    Placeholder.styling("confirm_click", ClickEvent.runCommand("/pay confirm")));
            return;
        }
        doPay(player, target, amount);
    }

    /** Offline / paytoggle / cooldown checks. Sends the message and returns true when the payment must stop. */
    private boolean checksFail(Player player, AccountManager.Account target) {
        Messages msg = plugin.messages();
        if (Bukkit.getPlayer(target.id()) == null && !plugin.payAllowOffline()) {
            msg.send(player, "pay-offline-disabled", text("player", target.name()));
            return true;
        }
        if (target.payDisabled() && !player.hasPermission("wintermark.eco.admin")) {
            msg.send(player, "pay-target-disabled", text("player", target.name()));
            return true;
        }
        int cooldown = plugin.payCooldownSeconds();
        if (cooldown > 0 && !player.hasPermission("wintermark.eco.bypass.cooldown")) {
            Long last = lastPay.get(player.getUniqueId());
            long left = last == null ? 0 : last + cooldown * 1000L - System.currentTimeMillis();
            if (left > 0) {
                msg.send(player, "pay-cooldown", text("seconds", String.valueOf((left + 999) / 1000)));
                return true;
            }
        }
        return false;
    }

    private void doPay(Player player, AccountManager.Account target, long amount) {
        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();
        AccountManager accounts = plugin.accounts();

        accounts.ensure(player.getUniqueId(), player.getName());
        AccountManager.Change change = accounts.transfer(player.getUniqueId(), target.id(), amount);
        switch (change.status()) {
            case SUCCESS -> {
                lastPay.put(player.getUniqueId(), System.currentTimeMillis());
                plugin.database().logTransaction(TransactionType.PAY.name(), player.getUniqueId(), player.getName(),
                        target.id(), target.name(), amount, null);
                msg.send(player, "pay-sent",
                        text("player", target.name()),
                        text("amount", fmt.format(amount)),
                        text("balance", fmt.format(change.balance())));
                Player online = Bukkit.getPlayer(target.id());
                if (online != null) {
                    msg.send(online, "pay-received",
                            text("player", player.getName()),
                            text("amount", fmt.format(amount)),
                            text("balance", fmt.format(accounts.balance(target.id()))));
                }
            }
            case INSUFFICIENT_FUNDS -> msg.send(player, "insufficient-funds",
                    text("amount", fmt.format(amount)),
                    text("balance", fmt.format(change.balance())));
            case MAX_BALANCE -> msg.send(player, "max-balance",
                    text("player", target.name()),
                    text("max", fmt.format(accounts.maxBalance())));
            default -> msg.send(player, "player-not-found", text("player", target.name()));
        }
    }

    public void forget(UUID player) {
        pending.remove(player);
        lastPay.remove(player);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            List<String> names = plugin.onlineNames();
            names.remove(source.getSender().getName());
            return EconomyPlugin.filter(names, args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && args[1].isEmpty()) {
            return List.of("100", "1k", "10k");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "wintermark.eco.use";
    }
}
