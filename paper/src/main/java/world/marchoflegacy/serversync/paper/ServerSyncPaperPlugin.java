package world.marchoflegacy.serversync.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import world.marchoflegacy.serversync.paper.listener.BlockIntentListener;
import world.marchoflegacy.serversync.paper.listener.PlayerStateListener;
import world.marchoflegacy.serversync.paper.zone.SharedZoneManager;

public final class ServerSyncPaperPlugin extends JavaPlugin {
    private SharedZoneManager sharedZoneManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String nodeId = getConfig().getString("node-id", "unknown-node");
        int radius = getConfig().getInt("shared-zone-radius", 96);

        this.sharedZoneManager = new SharedZoneManager(nodeId, radius);
        Bukkit.getPluginManager().registerEvents(new PlayerStateListener(this, sharedZoneManager), this);
        Bukkit.getPluginManager().registerEvents(new BlockIntentListener(this, sharedZoneManager), this);

        getLogger().info("ServerSync Paper started on node " + nodeId + ". Prototype transport is not enabled yet.");
    }

    public SharedZoneManager sharedZoneManager() {
        return sharedZoneManager;
    }
}
