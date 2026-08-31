package dev.serversync.paper.network;

import dev.serversync.common.security.SharedSecretKeys;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RelayClient implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String nodeId;
    private final String relayId;
    private final InetSocketAddress address;
    private final byte[] authKey;
    private final Duration connectTimeout;
    private final Duration pingInterval;
    private final Consumer<String> logger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastRttNanos = new AtomicLong(-1L);
    private final ConcurrentHashMap<String, Long> pendingPings = new ConcurrentHashMap<>();

    private Socket socket;
    private BufferedWriter writer;
    private Thread readerThread;
    private Thread pingThread;

    public RelayClient(String nodeId, String relayId, InetSocketAddress address, byte[] rootSecret,
                       Duration connectTimeout, Duration pingInterval, Consumer<String> logger) {
        this.nodeId = nodeId;
        this.relayId = relayId;
        this.address = address;
        this.authKey = SharedSecretKeys.derive(rootSecret, "relay-auth-v1");
        this.connectTimeout = connectTimeout;
        this.pingInterval = pingInterval;
        this.logger = logger;
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        Socket newSocket = new Socket();
        newSocket.connect(address, Math.toIntExact(connectTimeout.toMillis()));
        newSocket.setTcpNoDelay(true);
        newSocket.setKeepAlive(true);
        newSocket.setSoTimeout(Math.toIntExact(connectTimeout.toMillis()));

        BufferedReader reader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter newWriter = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
        authenticate(reader, newWriter);
        newSocket.setSoTimeout(0);

        socket = newSocket;
        writer = newWriter;
        running.set(true);
        readerThread = new Thread(() -> readLoop(reader), "serversync-relay-reader-" + relayId);
        readerThread.setDaemon(true);
        readerThread.start();
        pingThread = new Thread(this::pingLoop, "serversync-relay-ping-" + relayId);
        pingThread.setDaemon(true);
        pingThread.start();
        logger.accept("Authenticated to relay " + relayId + " at " + address.getHostString() + ':' + address.getPort());
    }

    public boolean isConnected() {
        return running.get() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public double lastRttMs() {
        long nanos = lastRttNanos.get();
        return nanos < 0 ? Double.POSITIVE_INFINITY : nanos / 1_000_000.0;
    }

    public void sendToNode(String targetNodeId, byte[] payload) throws IOException {
        if (!isConnected()) throw new IOException("Relay " + relayId + " is not connected");
        String encoded = Base64.getEncoder().encodeToString(payload);
        sendLine("SEND\t" + targetNodeId + '\t' + encoded);
    }

    private void authenticate(BufferedReader reader, BufferedWriter writer) throws IOException {
        byte[] nonceBytes = new byte[24];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        long timestamp = System.currentTimeMillis();
        String signed = "HELLO|" + PROTOCOL_VERSION + '|' + nodeId + '|' + timestamp + '|' + nonce;
        String mac = SharedSecretKeys.hmacBase64(authKey, signed);
        writeLine(writer, "HELLO\t" + PROTOCOL_VERSION + '\t' + nodeId + '\t' + timestamp + '\t' + nonce + '\t' + mac);

        String response = reader.readLine();
        if (response == null) throw new IOException("Relay closed during authentication");
        String[] parts = response.split("\\t", -1);
        if (parts.length != 6 || !parts[0].equals("WELCOME")) {
            throw new IOException("Relay rejected authentication");
        }
        int version = Integer.parseInt(parts[1]);
        if (version != PROTOCOL_VERSION) throw new IOException("Unsupported relay protocol version: " + version);
        String actualRelayId = parts[2];
        long serverTimestamp = Long.parseLong(parts[3]);
        String serverNonce = parts[4];
        String serverSigned = "WELCOME|" + PROTOCOL_VERSION + '|' + actualRelayId + '|' + nodeId + '|' + serverTimestamp + '|' + serverNonce;
        if (!SharedSecretKeys.verifyHmacBase64(authKey, serverSigned, parts[5])) {
            throw new IOException("Relay server authentication failed");
        }
    }

    private void readLoop(BufferedReader reader) {
        try {
            while (running.get()) {
                String line = reader.readLine();
                if (line == null) break;
                String[] parts = line.split("\\t", 3);
                if (parts.length >= 2 && parts[0].equals("PONG")) {
                    Long started = pendingPings.remove(parts[1]);
                    if (started != null) lastRttNanos.set(System.nanoTime() - started);
                }
                // DATA frames are intentionally left for the gameplay sync transport layer to consume later.
            }
        } catch (IOException ex) {
            if (running.get()) logger.accept("Relay " + relayId + " connection closed: " + ex.getMessage());
        } finally {
            close();
        }
    }

    private void pingLoop() {
        while (running.get()) {
            try {
                String token = Long.toUnsignedString(RANDOM.nextLong(), 36);
                pendingPings.put(token, System.nanoTime());
                sendLine("PING\t" + token);
                Thread.sleep(Math.max(1000L, pingInterval.toMillis()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException ex) {
                if (running.get()) logger.accept("Relay " + relayId + " ping failed: " + ex.getMessage());
                close();
                return;
            }
        }
    }

    private synchronized void sendLine(String line) throws IOException {
        if (writer == null) throw new IOException("Relay writer is not available");
        writeLine(writer, line);
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false) && socket == null) return;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        if (readerThread != null && readerThread != Thread.currentThread()) readerThread.interrupt();
        if (pingThread != null && pingThread != Thread.currentThread()) pingThread.interrupt();
        pendingPings.clear();
    }
}
