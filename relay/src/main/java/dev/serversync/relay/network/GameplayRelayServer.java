package dev.serversync.relay.network;

import dev.serversync.common.protocol.WorldBlockCommit;
import dev.serversync.common.protocol.WorldBlockIntent;
import dev.serversync.common.protocol.WorldMessage;
import dev.serversync.common.protocol.WorldProtocol;
import dev.serversync.common.security.SharedSecretKeys;
import dev.serversync.relay.RelayConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class GameplayRelayServer implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long WORLD_REQUEST_CACHE_MILLIS = 10 * 60 * 1000L;

    private final String relayId;
    private final RelayConfig.GameplayConfig config;
    private final RelayConfig.SecurityConfig security;
    private final byte[] authKey;
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> zoneSequences = new ConcurrentHashMap<>();
    private final Map<UUID, CommittedWorldRequest> worldRequests = new ConcurrentHashMap<>();
    private final Semaphore connectionSlots;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public GameplayRelayServer(String relayId, RelayConfig.GameplayConfig config,
                               RelayConfig.SecurityConfig security, byte[] rootSecret) {
        this.relayId = relayId;
        this.config = config;
        this.security = security;
        this.authKey = SharedSecretKeys.derive(rootSecret, "relay-auth-v1");
        this.connectionSlots = new Semaphore(config.maxConnections());
    }

    public synchronized void start() throws IOException {
        if (!config.enabled() || running.get()) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.bindAddress(), config.port()));
        serverSocket = socket;
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "serversync-gameplay-relay");
        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    public int activeNodes() {
        return sessions.size();
    }

    public int trackedWorldZones() {
        return zoneSequences.size();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (!connectionSlots.tryAcquire()) {
                    socket.close();
                    continue;
                }
                clients.submit(() -> {
                    try {
                        handleClient(socket);
                    } finally {
                        connectionSlots.release();
                    }
                });
            } catch (IOException ex) {
                if (running.get()) {
                    System.err.println("Gameplay relay accept failed: " + ex.getMessage());
                }
                return;
            }
        }
    }

    private void handleClient(Socket socket) {
        String nodeId = null;
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(config.authTimeoutMillis());

            String hello = readLineLimited(reader, 8192);
            AuthHello auth = parseAndVerifyHello(hello);
            nodeId = auth.nodeId();
            sendWelcome(writer, nodeId);
            socket.setSoTimeout(0);

            ClientSession session = new ClientSession(nodeId, socket, writer);
            ClientSession previous = sessions.put(nodeId, session);
            if (previous != null && previous != session) previous.close();
            System.out.println("Gameplay node authenticated: " + nodeId + " from " + socket.getRemoteSocketAddress());

            int maxLine = Math.max(8192, (config.maxPayloadBytes() * 2) + 1024);
            while (running.get() && !socket.isClosed()) {
                String line = readLineLimited(reader, maxLine);
                if (line == null) break;
                handleCommand(session, line);
            }
        } catch (Exception ex) {
            if (running.get()) {
                String remote = String.valueOf(socket.getRemoteSocketAddress());
                System.err.println("Rejected/closed gameplay relay connection from " + remote + ": " + ex.getMessage());
            }
        } finally {
            if (nodeId != null) {
                ClientSession current = sessions.get(nodeId);
                if (current != null && current.socket() == socket) sessions.remove(nodeId, current);
            }
        }
    }

    private AuthHello parseAndVerifyHello(String line) throws IOException {
        if (line == null) throw new IOException("Connection closed before authentication");
        String[] parts = line.split("\\t", -1);
        if (parts.length != 6 || !parts[0].equals("HELLO")) {
            throw new IOException("Expected HELLO authentication frame");
        }
        int version = Integer.parseInt(parts[1]);
        if (version != PROTOCOL_VERSION) throw new IOException("Unsupported relay protocol version: " + version);
        String nodeId = parts[2].trim();
        if (nodeId.isEmpty() || nodeId.length() > 128) throw new IOException("Invalid node id");
        if (!security.nodeAllowed(nodeId)) throw new IOException("Node id is not allowed by relay config");

        long timestamp = Long.parseLong(parts[3]);
        long now = System.currentTimeMillis();
        long tolerance = security.timestampTolerance().toMillis();
        if (Math.abs(now - timestamp) > tolerance) throw new IOException("Authentication timestamp outside tolerance");

        String nonce = parts[4].trim();
        byte[] nonceBytes;
        try {
            nonceBytes = Base64.getDecoder().decode(nonce);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid authentication nonce");
        }
        if (nonceBytes.length < 16 || nonceBytes.length > 64) throw new IOException("Invalid authentication nonce size");

        expireNonces(now);
        String replayKey = nodeId + ':' + nonce;
        Long previous = seenNonces.putIfAbsent(replayKey, now);
        if (previous != null) throw new IOException("Replay detected");

        String signed = "HELLO|" + PROTOCOL_VERSION + '|' + nodeId + '|' + timestamp + '|' + nonce;
        if (!SharedSecretKeys.verifyHmacBase64(authKey, signed, parts[5])) {
            seenNonces.remove(replayKey);
            throw new IOException("Authentication failed");
        }
        return new AuthHello(nodeId);
    }

    private void sendWelcome(BufferedWriter writer, String nodeId) throws IOException {
        byte[] nonceBytes = new byte[24];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        long timestamp = System.currentTimeMillis();
        String signed = "WELCOME|" + PROTOCOL_VERSION + '|' + relayId + '|' + nodeId + '|' + timestamp + '|' + nonce;
        String mac = SharedSecretKeys.hmacBase64(authKey, signed);
        writeLine(writer, "WELCOME\t" + PROTOCOL_VERSION + '\t' + relayId + '\t' + timestamp + '\t' + nonce + '\t' + mac);
    }

    private void handleCommand(ClientSession source, String line) throws IOException {
        String[] parts = line.split("\\t", 3);
        if (parts.length == 0) return;
        switch (parts[0]) {
            case "PING" -> {
                String token = parts.length >= 2 ? parts[1] : "";
                source.send("PONG\t" + token + '\t' + System.currentTimeMillis());
            }
            case "SEND" -> {
                if (parts.length != 3) throw new IOException("SEND requires target node and payload");
                byte[] decoded = decodePayload(parts[2]);
                if (WorldProtocol.looksLikeWorldMessage(decoded)) {
                    throw new IOException("World mutations must use relay-authoritative BROADCAST");
                }
                ClientSession target = sessions.get(parts[1]);
                if (target == null) {
                    source.send("UNAVAILABLE\t" + parts[1]);
                    return;
                }
                target.send("DATA\t" + source.nodeId() + '\t' + parts[2]);
            }
            case "BROADCAST" -> {
                if (parts.length < 2) throw new IOException("BROADCAST requires payload");
                String encoded = parts.length == 2 ? parts[1] : parts[2];
                byte[] decoded = decodePayload(encoded);
                if (WorldProtocol.looksLikeWorldMessage(decoded)) {
                    handleWorldMessage(source, decoded);
                } else {
                    broadcastData(source.nodeId(), encoded, false);
                }
            }
            case "QUIT" -> source.close();
            default -> throw new IOException("Unknown relay command");
        }
    }

    private void handleWorldMessage(ClientSession source, byte[] payload) throws IOException {
        WorldMessage message = WorldProtocol.decode(payload);
        if (!(message instanceof WorldBlockIntent intent)) {
            throw new IOException("Clients may only submit world block intents; commits are relay-generated");
        }
        if (!intent.sourceNodeId().equals(source.nodeId())) {
            throw new IOException("World intent source node does not match authenticated node");
        }
        sequenceWorldIntent(intent);
    }

    private synchronized void sequenceWorldIntent(WorldBlockIntent intent) throws IOException {
        long now = System.currentTimeMillis();
        expireWorldRequests(now);

        CommittedWorldRequest previous = worldRequests.get(intent.requestId());
        WorldBlockCommit commit;
        if (previous != null) {
            commit = previous.commit();
        } else {
            long sequence = zoneSequences.computeIfAbsent(intent.zoneId(), ignored -> new AtomicLong()).incrementAndGet();
            long epoch = 1L; // Persistent ownership epochs arrive in the next world-sync stage.
            commit = new WorldBlockCommit(
                    intent.requestId(),
                    intent.playerId(),
                    intent.sourceNodeId(),
                    relayId,
                    intent.world(),
                    intent.zoneId(),
                    epoch,
                    sequence,
                    intent.x(),
                    intent.y(),
                    intent.z(),
                    intent.oldBlockData(),
                    intent.newBlockData(),
                    now
            );
            worldRequests.put(intent.requestId(), new CommittedWorldRequest(commit, now));
        }

        byte[] commitBytes = WorldProtocol.encode(commit);
        if (commitBytes.length > config.maxPayloadBytes()) {
            throw new IOException("Relay-generated world commit exceeds max payload size");
        }
        String encodedCommit = Base64.getEncoder().encodeToString(commitBytes);
        // Include the source node as an acknowledgement. Applying the same BlockData is idempotent.
        broadcastData(intent.sourceNodeId(), encodedCommit, true);
    }

    private void broadcastData(String sourceNodeId, String encodedPayload, boolean includeSource) {
        for (ClientSession target : sessions.values()) {
            if (!includeSource && target.nodeId().equals(sourceNodeId)) continue;
            try {
                target.send("DATA\t" + sourceNodeId + '\t' + encodedPayload);
            } catch (IOException ex) {
                target.close();
                sessions.remove(target.nodeId(), target);
            }
        }
    }

    private byte[] decodePayload(String encoded) throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > config.maxPayloadBytes()) {
                throw new IOException("Relay payload exceeds " + config.maxPayloadBytes() + " bytes");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Relay payload is not valid base64");
        }
    }

    private void expireNonces(long nowMillis) {
        long cutoff = nowMillis - security.replayCache().toMillis();
        seenNonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private void expireWorldRequests(long nowMillis) {
        long cutoff = nowMillis - WORLD_REQUEST_CACHE_MILLIS;
        worldRequests.entrySet().removeIf(entry -> entry.getValue().committedAtMillis() < cutoff);
    }

    private static String readLineLimited(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maxChars, 1024));
        while (true) {
            int ch = reader.read();
            if (ch == -1) return line.isEmpty() ? null : line.toString();
            if (ch == '\n') return line.toString();
            if (ch == '\r') continue;
            if (line.length() >= maxChars) throw new IOException("Relay frame is too large");
            line.append((char) ch);
        }
    }

    private static void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (ClientSession session : sessions.values()) session.close();
        sessions.clear();
        seenNonces.clear();
        zoneSequences.clear();
        worldRequests.clear();
        clients.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }

    private record AuthHello(String nodeId) {
    }

    private record CommittedWorldRequest(WorldBlockCommit commit, long committedAtMillis) {
    }

    private record ClientSession(String nodeId, Socket socket, BufferedWriter writer) {
        synchronized void send(String line) throws IOException {
            writeLine(writer, line);
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
