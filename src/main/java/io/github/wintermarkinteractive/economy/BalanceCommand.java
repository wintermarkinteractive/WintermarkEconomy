package io.github.wintermarkinteractive.economy;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

import static io.github.wintermarkinteractive.economy.Messages.text;

/** /balance [player]  and  /balance history [page]  (aliases /bal, /money) */
public final class BalanceCommand implements BasicCommand {

    private final EconomyPlugin plugin;

    public BalanceCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg.send(sender, "usage-balance");
                return;
            }
            plugin.accounts().ensure(player.getUniqueId(), player.getName());
            long bal = plugin.accounts().balance(player.getUniqueId());
            msg.send(sender, "balance",
                    text("amount", fmt.format(bal)),
                    text("amount_compact", fmt.formatCompact(bal)));
            return;
        }

        if (args[0].equalsIgnoreCase("history")) {
            if (!(sender instanceof Player player)) {
                msg.send(sender, "players-only");
                return;
            }
            int page = 1;
            if (args.length > 1) {
                try {
                    page = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                    page = 1;
                }
            }
            plugin.history().show(sender, player.getUniqueId(), player.getName(), page, "/money history");
            return;
        }

        if (!sender.hasPermission("wintermark.eco.others")) {
            msg.send(sender, "no-permission");
            return;
        }
        AccountManager.Account target = plugin.resolveTarget(args[0]);
        if (target == null) {
            msg.send(sender, "player-not-found", text("player", args[0]));
            return;
        }
        long bal = plugin.accounts().balance(target.id());
        msg.send(sender, "balance-other",
                text("player", target.name()),
                text("amount", fmt.format(bal)),
                text("amount_compact", fmt.formatCompact(bal)));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            java.util.List<String> options = new java.util.ArrayList<>();
            options.add("history");
            if (source.getSender().hasPermission("wintermark.eco.others")) options.addAll(plugin.onlineNames());
            return EconomyPlugin.filter(options, args.length == 0 ? "" : args[0]);
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "wintermark.eco.use";
    }
}
