package dev.serversync.relay;

import dev.serversync.relay.network.GameplayRelayServer;
import dev.serversync.relay.voice.VoiceRelayConfig;
import dev.serversync.relay.voice.VoiceRelayServer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class RelayMain {
    private RelayMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("config.yml");
        RelayConfig config;
        try {
            config = RelayConfig.loadOrCreate(configPath);
        } catch (Exception ex) {
            System.err.println("Could not load config.yml: " + ex.getMessage());
            return;
        }

        byte[] rootSecret;
        try {
            rootSecret = config.readRootSecret();
        } catch (Exception ex) {
            System.err.println("ServerSync relay security error: " + ex.getMessage());
            System.err.println("The relay was not started because unauthenticated public access is not allowed.");
            return;
        }

        RelayState state = new RelayState(config.relayId());
        System.out.println("ServerSync relay starting: " + state.relayId() + " (" + config.region() + ")");
        System.out.println("Security mode: " + config.security().mode() + " using " + config.security().secretFile());
        if (!config.security().allowedNodeIds().isEmpty()) {
            System.out.println("Allowed node IDs: " + String.join(", ", config.security().allowedNodeIds()));
        }

        GameplayRelayServer gameplayRelay = null;
        VoiceRelayServer voiceRelay = null;
        boolean started = false;

        if (config.gameplay().enabled()) {
            try {
                gameplayRelay = new GameplayRelayServer(config.relayId(), config.gameplay(), config.security(), rootSecret);
                gameplayRelay.start();
                started = true;
                System.out.println("Gameplay relay listening on TCP "
                        + config.gameplay().bindAddress().getHostAddress() + ":" + config.gameplay().port());
                System.out.println("Gameplay relay authentication and node-to-node forwarding are enabled.");
                System.out.println("World block sequencer: enabled (per-zone in-memory sequence/commit MVP).");
            } catch (Exception ex) {
                System.err.println("Gameplay relay failed to start: " + ex.getMessage());
            }
        } else {
            System.out.println("Gameplay relay is disabled in config.yml.");
        }

        if (config.voice().enabled()) {
            try {
                VoiceRelayConfig voiceConfig = VoiceRelayConfig.fromRelayConfig(config, rootSecret);
                voiceRelay = new VoiceRelayServer(voiceConfig);
                voiceRelay.start();
                started = true;
                System.out.println("Voice relay listening on UDP "
                        + voiceConfig.bindAddress().getHostAddress() + ":" + voiceConfig.port());
            } catch (Exception ex) {
                System.err.println("Voice relay failed to start: " + ex.getMessage());
            }
        } else {
            System.out.println("Voice relay is disabled in config.yml.");
        }

        if (!started) {
            System.err.println("No relay service could be started. Check config.yml and port availability.");
            return;
        }

        GameplayRelayServer gameplayForShutdown = gameplayRelay;
        VoiceRelayServer voiceForShutdown = voiceRelay;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (gameplayForShutdown != null) gameplayForShutdown.close();
            if (voiceForShutdown != null) voiceForShutdown.close();
        }, "serversync-relay-shutdown"));

        System.out.println("ServerSync relay is ready.");
        new CountDownLatch(1).await();
    }
}
