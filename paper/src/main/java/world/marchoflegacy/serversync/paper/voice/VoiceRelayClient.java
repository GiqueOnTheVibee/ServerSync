package world.marchoflegacy.serversync.paper.voice;

import world.marchoflegacy.serversync.common.voice.VoiceFrame;
import world.marchoflegacy.serversync.common.voice.VoiceMessageType;
import world.marchoflegacy.serversync.common.voice.VoiceProtocol;
import world.marchoflegacy.serversync.common.voice.VoiceWireMessage;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VoiceRelayClient implements AutoCloseable {
    private final Logger logger;
    private final String nodeId;
    private final InetSocketAddress relayAddress;
    private final int bindPort;
    private final SecretKey key;
    private final Duration heartbeatInterval;
    private final Consumer<VoiceFrame> remoteFrameConsumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService heartbeatExecutor;

    private DatagramSocket socket;
    private Thread receiverThread;

    public VoiceRelayClient(Logger logger, String nodeId, InetSocketAddress relayAddress, int bindPort,
                            SecretKey key, Duration heartbeatInterval, Consumer<VoiceFrame> remoteFrameConsumer) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.relayAddress = Objects.requireNonNull(relayAddress, "relayAddress");
        this.bindPort = bindPort;
        this.key = Objects.requireNonNull(key, "key");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.remoteFrameConsumer = Objects.requireNonNull(remoteFrameConsumer, "remoteFrameConsumer");
        ThreadFactory heartbeatFactory = runnable -> {
            Thread thread = new Thread(runnable, "serversync-voice-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(heartbeatFactory);
    }

    public synchronized void start() throws SocketException {
        if (running.get()) {
            return;
        }
        DatagramSocket newSocket = new DatagramSocket(bindPort);
        newSocket.setReceiveBufferSize(1 << 20);
        newSocket.setSendBufferSize(1 << 20);
        newSocket.connect(relayAddress);
        socket = newSocket;
        running.set(true);

        receiverThread = new Thread(this::receiveLoop, "serversync-voice-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();

        sendControl(VoiceMessageType.REGISTER);
        long heartbeatMs = Math.max(1_000L, heartbeatInterval.toMillis());
        heartbeatExecutor.scheduleAtFixedRate(() -> sendControl(VoiceMessageType.HEARTBEAT), heartbeatMs,
                heartbeatMs, TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void sendFrame(VoiceFrame frame) {
        if (!running.get()) {
            return;
        }
        if (!nodeId.equals(frame.sourceNodeId())) {
            logger.warning("Refusing to send voice frame whose source node is " + frame.sourceNodeId()
                    + " from local node " + nodeId);
            return;
        }
        try {
            send(VoiceProtocol.encode(VoiceWireMessage.voice(nodeId, frame), key));
        } catch (IOException | GeneralSecurityException ex) {
            logger.log(Level.WARNING, "Failed to encode/send voice frame", ex);
        }
    }

    private void sendControl(VoiceMessageType type) {
        if (!running.get()) {
            return;
        }
        try {
            send(VoiceProtocol.encode(VoiceWireMessage.control(type, nodeId), key));
        } catch (IOException | GeneralSecurityException ex) {
            logger.log(Level.WARNING, "Failed to send voice relay " + type + " message", ex);
        }
    }

    private void send(byte[] payload) throws IOException {
        DatagramSocket current = socket;
        if (current == null || current.isClosed()) {
            return;
        }
        current.send(new DatagramPacket(payload, payload.length));
    }

    private void receiveLoop() {
        byte[] buffer = new byte[VoiceProtocol.MAX_DATAGRAM_BYTES];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                VoiceWireMessage message = VoiceProtocol.decode(packet.getData(), packet.getLength(), key);
                if (message.type() != VoiceMessageType.VOICE_FRAME) {
                    continue;
                }
                VoiceFrame frame = message.voiceFrame();
                if (!message.nodeId().equals(frame.sourceNodeId()) || nodeId.equals(frame.sourceNodeId())) {
                    continue;
                }
                remoteFrameConsumer.accept(frame);
            } catch (SocketException ex) {
                if (running.get()) {
                    logger.log(Level.WARNING, "Voice relay socket failed", ex);
                }
                return;
            } catch (GeneralSecurityException ex) {
                logger.log(Level.FINE, "Rejected unauthenticated/corrupt voice datagram", ex);
            } catch (IOException | RuntimeException ex) {
                if (running.get()) {
                    logger.log(Level.WARNING, "Failed to receive remote voice frame", ex);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            heartbeatExecutor.shutdownNow();
            return;
        }
        heartbeatExecutor.shutdownNow();
        if (socket != null) {
            socket.close();
        }
        if (receiverThread != null) {
            receiverThread.interrupt();
        }
    }
}
