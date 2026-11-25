package io.github.wintermarkinteractive.economy;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/** /paytoggle - stop or allow other players paying you. */
public final class PayToggleCommand implements BasicCommand {

    private final EconomyPlugin plugin;

    public PayToggleCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            plugin.messages().send(source.getSender(), "players-only");
            return;
        }
        plugin.accounts().ensure(player.getUniqueId(), player.getName());
        AccountManager.Account acc = plugin.accounts().get(player.getUniqueId());
        boolean nowDisabled = !acc.payDisabled();
        plugin.accounts().setPayDisabled(player.getUniqueId(), nowDisabled);
        plugin.messages().send(player, nowDisabled ? "paytoggle-off" : "paytoggle-on");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }

    @Override
    public String permission() {
        return "wintermark.eco.paytoggle";
    }
}
