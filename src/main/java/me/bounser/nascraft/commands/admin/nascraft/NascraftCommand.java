package me.bounser.nascraft.commands.admin.nascraft;

import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.Port;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Admin command (nascraft.admin):
 *   /nascraft reload                 - reloads config, lang and ports
 *   /nascraft stop | resume          - pauses/resumes all trading
 *   /nascraft restock <portId|all>   - immediately restocks ports
 *   /nascraft ports                  - lists all ports
 *   /nascraft log [player]           - opens the trade-log GUI (global or per player)
 */
public class NascraftCommand extends Command {

    private static final String PREFIX = ChatColor.DARK_PURPLE + "[NC] ";

    private final List<String> arguments = Arrays.asList("reload", "stop", "resume", "restock", "ports", "log");

    public NascraftCommand() {
        super(
                "nascraft",
                new String[]{Config.getInstance().getCommandAlias("nascraft")},
                "Admin command",
                "nascraft.admin"
        );
    }

    @Override
    public void execute(CommandSender sender, String[] args) {

        if (sender instanceof Player && !sender.hasPermission("nascraft.admin")) {
            Lang.get().message((Player) sender, Message.NO_PERMISSION);
            return;
        }

        if (args.length == 0) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Wrong syntax. Available arguments: " + String.join(" | ", arguments));
            return;
        }

        switch (args[0].toLowerCase()) {

            case "reload":
                handleReload(sender);
                return;

            case "stop":
                handleStop(sender);
                return;

            case "resume":
                handleResume(sender);
                return;

            case "restock":
                handleRestock(sender, args);
                return;

            case "ports":
                handlePorts(sender);
                return;

            case "log":
                handleLog(sender, args);
                return;

            default:
                sender.sendMessage(PREFIX + ChatColor.RED + "Wrong syntax. Available arguments: " + String.join(" | ", arguments));
        }
    }

    private void handleReload(CommandSender sender) {

        sender.sendMessage(PREFIX + ChatColor.GRAY + "Reloading...");

        // Config#reload also reloads the lang file and rebuilds all ports.
        try {
            Config.getInstance().reload();
        } catch (RuntimeException ex) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Reload failed: " + ex.getMessage());
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.GRAY + "Reloaded! " +
                MarketManager.getInstance().getPorts().size() + " ports loaded. Language: " +
                Config.getInstance().getSelectedLanguage());
    }

    private void handleStop(CommandSender sender) {

        if (MarketManager.getInstance().getActive()) {
            MarketManager.getInstance().stop();
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Trading stopped. Resume it with /nascraft resume.");
        } else {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Trading is already stopped!");
        }
    }

    private void handleResume(CommandSender sender) {

        if (!MarketManager.getInstance().getActive()) {
            try {
                MarketManager.getInstance().resume();
                sender.sendMessage(PREFIX + ChatColor.GRAY + "Trading resumed.");
            } catch (RuntimeException ex) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Cannot resume while the database is unavailable: " + ex.getMessage());
            }
        } else {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Trading is already active!");
        }
    }

    private void handleRestock(CommandSender sender, String[] args) {

        if (args.length != 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Wrong syntax. /nascraft restock <portId|all>");
            return;
        }

        if (args[1].equalsIgnoreCase("all")) {

            for (Port port : MarketManager.getInstance().getPorts())
                port.restock();

            sender.sendMessage(PREFIX + ChatColor.GRAY + "Restocked " + MarketManager.getInstance().getPorts().size() + " ports.");
            return;
        }

        Port port = MarketManager.getInstance().getPort(args[1]);

        if (port == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Unknown port: " + args[1]);
            return;
        }

        port.restock();

        sender.sendMessage(PREFIX + ChatColor.GRAY + "Port " + port.getPlainDisplayName() + " (" + port.getId() + ") restocked.");
    }

    private void handlePorts(CommandSender sender) {

        sendFormatted(sender, Lang.get().message(Message.PORT_LIST_HEADER));

        for (Port port : MarketManager.getInstance().getPorts()) {

            Location center = port.getCenter();

            String x = center != null ? String.valueOf(center.getBlockX()) : "?";
            String z = center != null ? String.valueOf(center.getBlockZ()) : "?";

            sendFormatted(sender, Lang.get().message(Message.PORT_LIST_SEGMENT)
                    .replace("[PORT]", port.getPlainDisplayName())
                    .replace("[ID]", port.getId())
                    .replace("[WORLD]", port.getWorldName())
                    .replace("[X]", x)
                    .replace("[Z]", z));
        }
    }

    private void handleLog(CommandSender sender, String[] args) {

        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "That command can only be used in-game.");
            return;
        }

        Player viewer = (Player) sender;

        if (args.length == 1) {
            NascraftLogListener.openTradeLog(viewer, null);
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);

        if (target != null) {
            NascraftLogListener.openTradeLog(viewer, target.getUniqueId());
            return;
        }

        UUID uuid = parseUUID(args[1]);

        if (uuid != null) {
            NascraftLogListener.openTradeLog(viewer, uuid);
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.RED + "Player not found: " + args[1]);
    }

    private void sendFormatted(CommandSender sender, String miniMessage) {

        if (sender instanceof Player) {
            Lang.get().message((Player) sender, miniMessage);
        } else {
            sender.sendMessage(Formatter.extractPlainText(MiniMessage.miniMessage().deserialize(miniMessage)));
        }
    }

    private static UUID parseUUID(String uuidString) {
        try {
            return UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {

        if (args.length == 1)
            return StringUtil.copyPartialMatches(args[0], arguments, new ArrayList<>());

        if (args.length == 2 && args[0].equalsIgnoreCase("restock")) {

            List<String> options = new ArrayList<>(MarketManager.getInstance().getPortIds());
            options.add("all");

            return StringUtil.copyPartialMatches(args[1], options, new ArrayList<>());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("log")) {

            List<String> names = new ArrayList<>();

            for (Player player : Bukkit.getOnlinePlayers())
                names.add(player.getName());

            return StringUtil.copyPartialMatches(args[1], names, new ArrayList<>());
        }

        return Collections.emptyList();
    }
}
