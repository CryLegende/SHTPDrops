package org.Cry_Legende.sHTPDrops;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /shtpdrops import|reload|info : l'administration du plugin, chaque sous-commande avec sa permission
 * (shtpdrops.admin les donne toutes).
 */
final class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("import", "reload", "info");

    private final SHTPDrops plugin;

    AdminCommand(SHTPDrops plugin) {
        this.plugin = plugin;
    }

    static boolean canUse(CommandSender sender, String subCommand) {
        return sender.hasPermission("shtpdrops.admin." + subCommand);
    }

    private static List<String> allowed(CommandSender sender) {
        return SUBCOMMANDS.stream().filter(sub -> canUse(sender, sub)).toList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            plugin.sendMessage(sender, "no-permission");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!allowed.contains(sub)) {
            plugin.sendMessage(sender, "usage-line", "subcommands", String.join("|", allowed));
            return true;
        }
        switch (sub) {
            case "import" -> plugin.importPreferences(sender);
            case "reload" -> plugin.reload(sender);
            case "info" -> {
                if (args.length < 2) {
                    plugin.sendMessage(sender, "usage-line", "subcommands", String.join("|", allowed));
                } else {
                    plugin.showInfo(sender, args[1]);
                }
            }
            default -> plugin.sendMessage(sender, "usage-line", "subcommands", String.join("|", allowed));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return allowed(sender).stream().filter(sub -> sub.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info") && canUse(sender, "info")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
