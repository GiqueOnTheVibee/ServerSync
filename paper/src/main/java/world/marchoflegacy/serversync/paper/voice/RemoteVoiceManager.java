package world.marchoflegacy.serversync.paper.voice;

import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import world.marchoflegacy.serversync.common.voice.VoiceFrame;
import world.marchoflegacy.serversync.paper.ServerSyncPaperPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteVoiceManager {
    private final ServerSyncPaperPlugin plugin;
    private final VoicechatServerApi api;
    private final Map<ChannelKey, LocationalAudioChannel> channels = new ConcurrentHashMap<>();
    private final Map<SpeakerKey, Long> lastSequence = new ConcurrentHashMap<>();

    public RemoteVoiceManager(ServerSyncPaperPlugin plugin, VoicechatServerApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    public void accept(VoiceFrame frame) {
        if (Bukkit.getPlayer(frame.playerId()) != null) {
            return;
        }

        SpeakerKey speakerKey = new SpeakerKey(frame.playerId(), frame.sourceNodeId());
        Long previous = lastSequence.get(speakerKey);
        if (previous != null && frame.sequence() <= previous) {
            return;
        }
        lastSequence.put(speakerKey, frame.sequence());

        String localWorldName = plugin.getConfig().getString("voice.world-map." + frame.world(), frame.world());
        World bukkitWorld = Bukkit.getWorld(localWorldName);
        if (bukkitWorld == null) {
            plugin.getLogger().finest(() -> "Ignoring remote voice frame for missing world " + localWorldName);
            return;
        }

        ChannelKey key = new ChannelKey(frame.playerId(), localWorldName);
        LocationalAudioChannel channel = channels.computeIfAbsent(key,
                ignored -> createChannel(frame.playerId(), bukkitWorld, frame));
        if (channel == null || channel.isClosed()) {
            channels.remove(key, channel);
            return;
        }

        channel.updateLocation(api.createPosition(frame.x(), frame.y(), frame.z()));
        float distance = (float) plugin.getConfig().getDouble(
                frame.whispering() ? "voice.whisper-distance" : "voice.normal-distance",
                frame.whispering() ? 24.0D : 48.0D);
        channel.setDistance(distance);
        channel.send(frame.opusData());
    }

    private LocationalAudioChannel createChannel(UUID remotePlayer, World world, VoiceFrame frame) {
        ServerLevel level = api.fromServerLevel(world);
        UUID channelId = UUID.nameUUIDFromBytes(
                ("serversync-voice:" + remotePlayer + ":" + world.getUID()).getBytes(StandardCharsets.UTF_8));
        LocationalAudioChannel channel = api.createLocationalAudioChannel(channelId, level,
                api.createPosition(frame.x(), frame.y(), frame.z()));
        if (channel != null) {
            channel.setFilter(receiver -> !receiver.getUuid().equals(remotePlayer));
        }
        return channel;
    }

    private record ChannelKey(UUID playerId, String world) {
    }

    private record SpeakerKey(UUID playerId, String sourceNodeId) {
    }
}
