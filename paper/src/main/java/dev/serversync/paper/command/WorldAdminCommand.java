package dev.serversync.paper.command;

import dev.serversync.paper.ServerSyncPaperPlugin;
import dev.serversync.paper.world.WorldSyncService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Comparator;

public final class WorldAdminCommand implements CommandExecutor {
    private final ServerSyncPaperPlugin plugin;

    public WorldAdminCommand(ServerSyncPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        WorldSyncService service = plugin.worldSyncService();
        if (service == null) {
            sender.sendMessage("ServerSync world sync service is not initialized.");
            return true;
        }

        WorldSyncService.WorldSyncStatus status = service.status();
        sender.sendMessage("ServerSync world status:");
        sender.sendMessage(" enabled=" + status.enabled()
                + " relayConnected=" + status.relayConnected()
                + " authoritativeRelay=" + status.authoritativeRelay());
        sender.sendMessage(" intents=" + status.intentsSent()
                + " commits=" + status.commitsReceived()
                + " remoteApplied=" + status.remoteBlocksApplied()
                + " sequenceGaps=" + status.sequenceGaps());

        if (!status.lastSequenceByZone().isEmpty()) {
            sender.sendMessage(" Sequenced zones:");
            status.lastSequenceByZone().entrySet().stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .limit(12)
                    .forEach(entry -> sender.sendMessage("  - " + entry.getKey() + " seq=" + entry.getValue()));
            if (status.lastSequenceByZone().size() > 12) {
                sender.sendMessage("  ... and " + (status.lastSequenceByZone().size() - 12) + " more");
            }
        }
        return true;
    }
}
