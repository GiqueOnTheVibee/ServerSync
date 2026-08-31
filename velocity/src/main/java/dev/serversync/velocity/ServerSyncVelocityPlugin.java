package dev.serversync.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Plugin(id = "serversync", name = "ServerSync", version = "0.1.0-SNAPSHOT")
public final class ServerSyncVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public ServerSyncVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            Path config = dataDirectory.resolve("config.yml");
            if (Files.notExists(config)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in == null) throw new IOException("Bundled config.yml is missing");
                    Files.copy(in, config, StandardCopyOption.REPLACE_EXISTING);
                }
                logger.info("Created ServerSync Velocity config at {}", config.toAbsolutePath());
            }
        } catch (IOException ex) {
            logger.error("Could not create ServerSync Velocity config.yml", ex);
        }
        logger.info("ServerSync Velocity started. Relay/coordinator integration remains experimental.");
    }

    public ProxyServer proxy() {
        return proxy;
    }
}
