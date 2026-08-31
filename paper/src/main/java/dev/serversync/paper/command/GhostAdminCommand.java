package dev.serversync.paper.command;

import dev.serversync.paper.ServerSyncPaperPlugin;
import dev.serversync.paper.ghost.GhostPlayerManager;
import dev.serversync.paper.ghost.GhostSyncService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GhostAdminCommand implements CommandExecutor, TabCompleter {
    private final ServerSyncPaperPlugin plugin;

    public GhostAdminCommand(ServerSyncPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ssghosts")) {
            return listGhosts(sender);
        }
        if (command.getName().equalsIgnoreCase("sstp")) {
            return teleportToGhost(sender, args);
        }
        return false;
    }

    private boolean listGhosts(CommandSender sender) {
        GhostSyncService ghosts = plugin.ghostSyncService();
        if (ghosts == null) {
            sender.sendMessage("ServerSync ghost sync is not active on this node.");
            return true;
        }
        List<GhostPlayerManager.GhostTarget> targets = ghosts.ghostTargets();
        if (targets.isEmpty()) {
            sender.sendMessage("No remote ServerSync ghost players are currently visible.");
            return true;
        }
        sender.sendMessage("Remote ServerSync ghosts (" + targets.size() + "):");
        for (GhostPlayerManager.GhostTarget target : targets) {
            sender.sendMessage(" - " + target.key() + " world=" + target.world()
                    + " x=" + round(target.x()) + " y=" + round(target.y()) + " z=" + round(target.z()));
        }
        return true;
    }

    private boolean teleportToGhost(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command must be used by a player.");
            return true;
        }
        if (args.length != 1 || args[0].isBlank()) {
            sender.sendMessage("Usage: /sstp <player[@node]>");
            return true;
        }

        GhostSyncService ghosts = plugin.ghostSyncService();
        if (ghosts == null) {
            sender.sendMessage("ServerSync ghost sync is not active on this node.");
            return true;
        }

        List<GhostPlayerManager.GhostTarget> matches = ghosts.findGhostTargets(args[0]);
        if (matches.isEmpty()) {
            sender.sendMessage("Remote ghost not found: " + args[0]);
            return true;
        }
        if (matches.size() > 1) {
            sender.sendMessage("More than one remote ghost has that name. Use one of:");
            for (GhostPlayerManager.GhostTarget target : matches) {
                sender.sendMessage(" - /sstp " + target.key());
            }
            return true;
        }

        GhostPlayerManager.GhostTarget target = matches.getFirst();
        World world = Bukkit.getWorld(target.world());
        if (world == null) {
            sender.sendMessage("The ghost is in world '" + target.world() + "', which does not exist on this node.");
            return true;
        }

        Location destination = new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        if (!player.teleport(destination)) {
            sender.sendMessage("Teleport to " + target.key() + " was rejected by the server.");
            return true;
        }
        sender.sendMessage("Teleported to remote player " + target.key() + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("sstp") || args.length != 1) return List.of();
        GhostSyncService ghosts = plugin.ghostSyncService();
        if (ghosts == null) return List.of();

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String suggestion : ghosts.ghostTargetSuggestions()) {
            if (suggestion.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(suggestion);
        }
        return matches;
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
