package dev.serversync.relay;

import dev.serversync.relay.voice.VoiceRelayConfig;
import dev.serversync.relay.voice.VoiceRelayServer;

import java.util.concurrent.CountDownLatch;

public final class RelayMain {
    private RelayMain() {
    }

    public static void main(String[] args) throws Exception {
        RelayState state = new RelayState(System.getenv().getOrDefault("SERVERSYNC_RELAY_ID", "relay-local"));
        System.out.println("ServerSync relay started: " + state.relayId());
        System.out.println("Gameplay arbiter: in-memory prototype.");

        VoiceRelayConfig voiceConfig;
        try {
            voiceConfig = VoiceRelayConfig.fromEnvironment();
        } catch (Exception ex) {
            System.err.println("Voice relay configuration error: " + ex.getMessage());
            System.err.println("Cross-region voice relay did not start.");
            return;
        }

        VoiceRelayServer voiceRelay = new VoiceRelayServer(voiceConfig);
        voiceRelay.start();
        Runtime.getRuntime().addShutdownHook(new Thread(voiceRelay::close, "serversync-relay-shutdown"));
        System.out.println("Voice relay listening on " + voiceConfig.bindAddress().getHostAddress() + ":" + voiceConfig.port());
        new CountDownLatch(1).await();
    }
}
