package world.marchoflegacy.serversync.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(id = "serversync", name = "ServerSync", version = "0.1.0-SNAPSHOT")
public final class ServerSyncVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public ServerSyncVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("ServerSync Velocity started. Relay selection/coordinator integration is prototype-only.");
    }

    public ProxyServer proxy() {
        return proxy;
    }
}
