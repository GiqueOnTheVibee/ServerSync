package world.marchoflegacy.serversync.relay.voice;

import world.marchoflegacy.serversync.common.voice.VoiceFrame;
import world.marchoflegacy.serversync.common.voice.VoiceMessageType;
import world.marchoflegacy.serversync.common.voice.VoiceProtocol;
import world.marchoflegacy.serversync.common.voice.VoiceWireMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceRelayServer implements AutoCloseable {
    private final VoiceRelayConfig config;
    private final Map<String, NodeSession> nodes = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DatagramSocket socket;
    private Thread receiverThread;
    private Thread cleanupThread;

    public VoiceRelayServer(VoiceRelayConfig config) {
        this.config = config;
    }

    public synchronized void start() throws SocketException {
        if (running.get()) return;
        DatagramSocket newSocket = new DatagramSocket(new InetSocketAddress(config.bindAddress(), config.port()));
        newSocket.setReceiveBufferSize(4 << 20);
        newSocket.setSendBufferSize(4 << 20);
        socket = newSocket;
        running.set(true);

        receiverThread = new Thread(this::receiveLoop, "serversync-voice-relay");
        receiverThread.setDaemon(false);
        receiverThread.start();
        cleanupThread = new Thread(this::cleanupLoop, "serversync-voice-relay-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    public int activeNodes() {
        expireDeadNodes();
        return nodes.size();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[VoiceProtocol.MAX_DATAGRAM_BYTES];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                handle(packet);
            } catch (SocketException ex) {
                if (running.get()) System.err.println("Voice relay socket failed: " + ex.getMessage());
                return;
            } catch (IOException ex) {
                if (running.get()) System.err.println("Voice relay I/O error: " + ex.getMessage());
            }
        }
    }

    private void handle(DatagramPacket packet) {
        VoiceWireMessage message;
        try {
            message = VoiceProtocol.decode(packet.getData(), packet.getLength(), config.key());
        } catch (GeneralSecurityException | IOException ex) {
            return;
        }

        InetSocketAddress source = new InetSocketAddress(packet.getAddress(), packet.getPort());
        long now = System.nanoTime();
        if (message.type() == VoiceMessageType.REGISTER || message.type() == VoiceMessageType.HEARTBEAT) {
            nodes.put(message.nodeId(), new NodeSession(source, now));
            return;
        }
        if (message.type() != VoiceMessageType.VOICE_FRAME) return;

        VoiceFrame frame = message.voiceFrame();
        if (!message.nodeId().equals(frame.sourceNodeId())) return;
        nodes.put(message.nodeId(), new NodeSession(source, now));
        expireDeadNodes();

        for (Map.Entry<String, NodeSession> entry : nodes.entrySet()) {
            if (entry.getKey().equals(message.nodeId())) continue;
            NodeSession target = entry.getValue();
            try {
                socket.send(new DatagramPacket(packet.getData(), packet.getLength(), target.address().getAddress(),
                        target.address().getPort()));
            } catch (IOException ignored) {
            }
        }
    }

    private void cleanupLoop() {
        while (running.get()) {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            expireDeadNodes();
        }
    }

    private void expireDeadNodes() {
        long cutoff = System.nanoTime() - config.nodeTimeout().toNanos();
        nodes.entrySet().removeIf(entry -> entry.getValue().lastSeenNanos() < cutoff);
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) return;
        if (socket != null) socket.close();
        if (receiverThread != null) receiverThread.interrupt();
        if (cleanupThread != null) cleanupThread.interrupt();
        nodes.clear();
    }

    private record NodeSession(InetSocketAddress address, long lastSeenNanos) {
    }
}
