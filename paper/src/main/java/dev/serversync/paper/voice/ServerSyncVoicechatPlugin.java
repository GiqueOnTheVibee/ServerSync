package dev.serversync.paper.voice;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoiceHostEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.bukkit.entity.Player;
import dev.serversync.common.voice.VoiceFrame;
import dev.serversync.paper.ServerSyncPaperPlugin;

import java.util.concurrent.atomic.AtomicLong;

public final class ServerSyncVoicechatPlugin implements VoicechatPlugin {
    private final ServerSyncPaperPlugin plugin;
    private final String nodeId;
    private final AtomicLong sequence = new AtomicLong();

    private volatile VoiceRelayClient relayClient;
    private volatile VoicechatServerApi serverApi;
    private volatile RemoteVoiceManager remoteVoiceManager;

    public ServerSyncVoicechatPlugin(ServerSyncPaperPlugin plugin, String nodeId) {
        this.plugin = plugin;
        this.nodeId = nodeId;
    }

    public void attachRelayClient(VoiceRelayClient relayClient) {
        this.relayClient = relayClient;
    }

    @Override
    public String getPluginId() {
        return "serversync_voice";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (!(api instanceof VoicechatServerApi voicechatServerApi)) {
            throw new IllegalStateException("ServerSync Voice requires VoicechatServerApi");
        }
        this.serverApi = voicechatServerApi;
        this.remoteVoiceManager = new RemoteVoiceManager(plugin, voicechatServerApi);
        plugin.getLogger().info("ServerSync voice bridge initialized.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoiceHostEvent.class, this::onVoiceHost);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    public void onRemoteFrame(VoiceFrame frame) {
        RemoteVoiceManager manager = remoteVoiceManager;
        if (manager != null) {
            manager.accept(frame);
        }
    }

    private void onVoiceHost(VoiceHostEvent event) {
        String publicHost = plugin.getConfig().getString("voice.public-host", "").trim();
        if (!publicHost.isEmpty()) {
            event.setVoiceHost(publicHost);
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoiceRelayClient currentRelay = relayClient;
        if (currentRelay == null || !currentRelay.isRunning()) {
            return;
        }

        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null) {
            return;
        }
        boolean forwardGroupAudio = plugin.getConfig().getBoolean("voice.forward-group-audio", false);
        if (connection.isInGroup() && !forwardGroupAudio) {
            return;
        }

        ServerPlayer voicePlayer = connection.getPlayer();
        Object platformPlayer = voicePlayer.getPlayer();
        if (!(platformPlayer instanceof Player bukkitPlayer)) {
            return;
        }

        MicrophonePacket packet = event.getPacket();
        byte[] opus = packet.getOpusEncodedData();
        if (opus == null || opus.length == 0) {
            return;
        }

        Position position = voicePlayer.getPosition();
        VoiceFrame frame = new VoiceFrame(voicePlayer.getUuid(), nodeId, bukkitPlayer.getWorld().getName(),
                sequence.incrementAndGet(), System.nanoTime(), position.getX(), position.getY(), position.getZ(),
                packet.isWhispering(), opus);
        currentRelay.sendFrame(frame);
    }
}
