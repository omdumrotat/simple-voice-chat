package de.maxhenkel.voicechat.standalone;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.config.StandaloneConfig;
import de.maxhenkel.voicechat.logging.VoiceChatLogger;
import de.maxhenkel.voicechat.util.ByteBufferWrapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class StandaloneVoicechatServer {

    private static final byte MAGIC_BYTE = (byte) 0b11111111;
    private static final UUID PING_V1 = UUID.fromString("58bc9ae9-c7a8-45e4-a11c-efbb67199425");

    private static final byte TYPE_MIC = 0x1;
    private static final byte TYPE_PLAYER_SOUND = 0x2;
    private static final byte TYPE_GROUP_SOUND = 0x3;
    private static final byte TYPE_LOCATION_SOUND = 0x4;
    private static final byte TYPE_AUTH = 0x5;
    private static final byte TYPE_AUTH_ACK = 0x6;
    private static final byte TYPE_PING = 0x7;
    private static final byte TYPE_KEEP_ALIVE = 0x8;
    private static final byte TYPE_CONNECTION_CHECK = 0x9;
    private static final byte TYPE_CONNECTION_CHECK_ACK = 0xA;

    private static final byte MSG_HELLO = 0x1;
    private static final byte MSG_PLAYER_SECRET = 0x2;
    private static final byte MSG_PLAYER_STATE = 0x3;
    private static final byte MSG_PLAYER_POSITION = 0x4;
    private static final byte MSG_PLAYER_REMOVE = 0x5;

    private static final byte GROUP_TYPE_NORMAL = 0;
    private static final byte GROUP_TYPE_OPEN = 1;
    private static final byte GROUP_TYPE_ISOLATED = 2;

    private final VoiceChatLogger logger;
    private final StandaloneConfig config;
    private final Map<UUID, StandalonePlayerState> states;
    private final Map<UUID, StandaloneSecret> secrets;
    private final Map<UUID, ClientConnection> connections;
    private final Map<UUID, ClientConnection> unCheckedConnections;
    private final BlockingQueue<RawUdpPacket> packetQueue;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread udpReaderThread;
    private Thread processThread;
    private ControlServer controlServer;

    private volatile boolean allowPings;
    private volatile int keepAlive;
    private volatile double voiceChatDistance;
    private volatile double whisperDistance;
    private volatile double broadcastRange;

    public StandaloneVoicechatServer(VoiceChatLogger logger, StandaloneConfig config) {
        this.logger = logger;
        this.config = config;
        states = new ConcurrentHashMap<>();
        secrets = new ConcurrentHashMap<>();
        connections = new ConcurrentHashMap<>();
        unCheckedConnections = new ConcurrentHashMap<>();
        packetQueue = new LinkedBlockingQueue<>();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        allowPings = config.allowPings.get();
        keepAlive = config.keepAlive.get();
        voiceChatDistance = config.voiceChatDistance.get();
        whisperDistance = config.whisperDistance.get();
        broadcastRange = config.broadcastRange.get();

        openSocket();

        udpReaderThread = new Thread(this::readLoop, "VoiceChatStandaloneUdpReader");
        processThread = new Thread(this::processLoop, "VoiceChatStandaloneProcessor");
        udpReaderThread.start();
        processThread.start();

        int controlPort = config.controlPort.get() < 0 ? config.port.get() : config.controlPort.get();
        controlServer = new ControlServer(controlPort);
        controlServer.start();
    }

    public void stop() {
        running = false;
        if (controlServer != null) {
            controlServer.close();
            controlServer = null;
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (udpReaderThread != null) {
            udpReaderThread.interrupt();
        }
        if (processThread != null) {
            processThread.interrupt();
        }
        packetQueue.clear();
        connections.clear();
        unCheckedConnections.clear();
        secrets.clear();
        states.clear();
    }

    private void openSocket() {
        int port = config.port.get();
        String bindAddress = config.bindAddress.get().trim();
        InetAddress address = null;
        if (bindAddress.isEmpty() || bindAddress.equals("*")) {
            bindAddress = "";
        } else {
            try {
                address = InetAddress.getByName(bindAddress);
            } catch (Exception e) {
                logger.warn("Invalid bind address '{}', binding to wildcard address", bindAddress);
                bindAddress = "";
                address = null;
            }
        }

        try {
            socket = address == null ? new DatagramSocket(port) : new DatagramSocket(port, address);
            if (bindAddress.isEmpty()) {
                logger.info("Standalone voice chat server started at port {}", port);
            } else {
                logger.info("Standalone voice chat server started at {}:{}", bindAddress, port);
            }
        } catch (BindException e) {
            logger.error("Failed to bind to port {}", port, e);
            throw new RuntimeException(e);
        } catch (Exception e) {
            logger.error("Failed to start standalone voice chat server", e);
            throw new RuntimeException(e);
        }
    }

    private void readLoop() {
        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                packetQueue.add(new RawUdpPacket(data, packet.getSocketAddress(), System.currentTimeMillis()));
            } catch (Exception e) {
                if (socket != null && !socket.isClosed()) {
                    logger.debug("Failed to read UDP packet", e);
                }
            }
        }
    }

    private void processLoop() {
        long lastKeepAlive = 0L;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastKeepAlive > keepAlive) {
                    sendKeepAlives();
                    lastKeepAlive = now;
                }

                RawUdpPacket packet = packetQueue.poll(10, TimeUnit.MILLISECONDS);
                if (packet == null) {
                    continue;
                }
                handlePacket(packet);
            } catch (Exception e) {
                logger.error("Standalone voice chat server error", e);
            }
        }
    }

    private void handlePacket(RawUdpPacket packet) throws Exception {
        ByteBufferWrapper buffer = new ByteBufferWrapper(ByteBuffer.wrap(packet.data));
        if (buffer.readByte() != MAGIC_BYTE) {
            return;
        }
        UUID playerId = buffer.readUUID();
        StandaloneSecret secret = secrets.get(playerId);
        if (secret == null) {
            if (handlePing(playerId, packet.address, buffer)) {
                return;
            }
            return;
        }
        byte[] encryptedPayload = buffer.readByteArray();
        byte[] decrypted;
        try {
            decrypted = secret.decrypt(encryptedPayload);
        } catch (Exception e) {
            logger.debug("Failed to decrypt packet from {}", packet.address);
            return;
        }
        ByteBufferWrapper payload = new ByteBufferWrapper(ByteBuffer.wrap(decrypted));
        byte packetType = payload.readByte();

        if (packetType == TYPE_AUTH) {
            UUID authPlayer = payload.readUUID();
            byte[] secretBytes = new byte[StandaloneSecret.SECRET_SIZE_BYTES];
            payload.readBytes(secretBytes);
            StandaloneSecret authSecret = StandaloneSecret.fromBytes(secretBytes);
            if (!authSecret.equals(secret)) {
                return;
            }
            ClientConnection connection = unCheckedConnections.get(authPlayer);
            if (connection == null) {
                connection = connections.get(authPlayer);
            }
            if (connection == null) {
                connection = new ClientConnection(authPlayer, packet.address);
                unCheckedConnections.put(authPlayer, connection);
                logger.info("Authenticated player {}", authPlayer);
            }
            sendEmptyPacket(TYPE_AUTH_ACK, connection);
            return;
        }

        if (packetType == TYPE_CONNECTION_CHECK) {
            ClientConnection connection = getUnconnectedSender(packet.address);
            if (connection == null) {
                connection = getSender(packet.address);
                if (connection != null) {
                    sendEmptyPacket(TYPE_CONNECTION_CHECK_ACK, connection);
                }
                return;
            }
            connection.setLastKeepAliveResponse(System.currentTimeMillis());
            connections.put(connection.playerUUID, connection);
            unCheckedConnections.remove(connection.playerUUID);
            logger.info("Validated connection of player {}", connection.playerUUID);
            sendEmptyPacket(TYPE_CONNECTION_CHECK_ACK, connection);
            return;
        }

        ClientConnection connection = getSender(packet.address);
        if (connection == null) {
            return;
        }

        if (packetType == TYPE_MIC) {
            byte[] data = payload.readByteArray();
            long sequence = payload.readLong();
            boolean whispering = payload.readBoolean();
            onMicPacket(connection.playerUUID, data, sequence, whispering);
        } else if (packetType == TYPE_PING) {
            payload.readUUID();
            payload.readLong();
        } else if (packetType == TYPE_KEEP_ALIVE) {
            connection.setLastKeepAliveResponse(System.currentTimeMillis());
        }
    }

    private boolean handlePing(UUID playerId, SocketAddress socketAddress, ByteBufferWrapper buffer) {
        if (!allowPings) {
            return false;
        }
        if (!PING_V1.equals(playerId)) {
            return false;
        }
        try {
            byte[] payload = buffer.readByteArray();
            ByteBufferWrapper payloadBuffer = new ByteBufferWrapper(ByteBuffer.wrap(payload));
            UUID id = payloadBuffer.readUUID();
            long timestamp = payloadBuffer.readLong();

            ByteBufferWrapper responseBuffer = new ByteBufferWrapper(ByteBuffer.allocate(24));
            responseBuffer.writeUUID(id);
            responseBuffer.writeLong(timestamp);
            sendRaw(responseBuffer.toBytes(), socketAddress);
        } catch (Exception e) {
            logger.debug("Failed to send ping response to {}", socketAddress);
        }
        return true;
    }

    private void onMicPacket(UUID senderId, byte[] data, long sequenceNumber, boolean whispering) {
        StandalonePlayerState senderState = states.get(senderId);
        if (senderState == null || !senderState.hasPosition()) {
            return;
        }
        UUID groupId = senderState.groupId;
        if (groupId != null) {
            processGroupPacket(senderId, groupId, data, sequenceNumber);
            if (senderState.groupType == GROUP_TYPE_OPEN) {
                processProximityPacket(senderState, data, sequenceNumber, whispering, groupId);
            }
            return;
        }
        processProximityPacket(senderState, data, sequenceNumber, whispering, null);
    }

    private void processGroupPacket(UUID senderId, UUID groupId, byte[] data, long sequenceNumber) {
        for (StandalonePlayerState state : states.values()) {
            if (!groupId.equals(state.groupId)) {
                continue;
            }
            if (senderId.equals(state.playerUUID)) {
                continue;
            }
            if (state.disabled || state.disconnected) {
                continue;
            }
            ClientConnection connection = connections.get(state.playerUUID);
            if (connection == null) {
                continue;
            }
            sendGroupSoundPacket(connection, senderId, data, sequenceNumber);
        }
    }

    private void processProximityPacket(StandalonePlayerState senderState, byte[] data, long sequenceNumber, boolean whispering, UUID senderGroup) {
        float distance = whispering ? (float) whisperDistance : (float) voiceChatDistance;
        double range = getBroadcastRange(distance);
        double rangeSquared = range * range;

        for (StandalonePlayerState state : states.values()) {
            if (state.playerUUID.equals(senderState.playerUUID)) {
                continue;
            }
            if (!state.hasPosition()) {
                continue;
            }
            if (senderGroup != null && senderGroup.equals(state.groupId)) {
                continue;
            }
            if (state.groupType == GROUP_TYPE_ISOLATED) {
                continue;
            }
            if (state.disabled || state.disconnected) {
                continue;
            }
            if (!senderState.isSameDimension(state)) {
                continue;
            }
            double dx = senderState.x - state.x;
            double dy = senderState.y - state.y;
            double dz = senderState.z - state.z;
            if (dx * dx + dy * dy + dz * dz > rangeSquared) {
                continue;
            }
            ClientConnection connection = connections.get(state.playerUUID);
            if (connection == null) {
                continue;
            }
            sendPlayerSoundPacket(connection, senderState.playerUUID, data, sequenceNumber, whispering, distance);
        }
    }

    private double getBroadcastRange(float minRange) {
        double range = broadcastRange;
        if (range < 0D) {
            range = voiceChatDistance + 1D;
        }
        return Math.max(range, minRange);
    }

    private void sendKeepAlives() {
        long timestamp = System.currentTimeMillis();
        connections.values().removeIf(connection -> {
            if (timestamp - connection.lastKeepAliveResponse >= keepAlive * 10L) {
                logger.info("Player {} timed out", connection.playerUUID);
                return true;
            }
            return false;
        });

        for (ClientConnection connection : connections.values()) {
            sendEmptyPacket(TYPE_KEEP_ALIVE, connection);
        }
    }

    private ClientConnection getSender(SocketAddress address) {
        for (ClientConnection connection : connections.values()) {
            if (connection.address.equals(address)) {
                return connection;
            }
        }
        return null;
    }

    private ClientConnection getUnconnectedSender(SocketAddress address) {
        for (ClientConnection connection : unCheckedConnections.values()) {
            if (connection.address.equals(address)) {
                return connection;
            }
        }
        return null;
    }

    private void sendEmptyPacket(byte type, ClientConnection connection) {
        StandaloneSecret secret = secrets.get(connection.playerUUID);
        if (secret == null) {
            return;
        }
        byte[] payload = buildPacket(type, buffer -> {
        }, 8);
        sendEncrypted(secret, connection.address, payload);
    }

    private void sendGroupSoundPacket(ClientConnection connection, UUID sender, byte[] data, long sequenceNumber) {
        StandaloneSecret secret = secrets.get(connection.playerUUID);
        if (secret == null) {
            return;
        }
        int size = data.length + 64;
        byte[] payload = buildPacket(TYPE_GROUP_SOUND, buffer -> {
            buffer.writeUUID(sender);
            buffer.writeUUID(sender);
            buffer.writeByteArray(data);
            buffer.writeLong(sequenceNumber);
            buffer.writeByte((byte) 0);
        }, size);
        sendEncrypted(secret, connection.address, payload);
    }

    private void sendPlayerSoundPacket(ClientConnection connection, UUID sender, byte[] data, long sequenceNumber, boolean whispering, float distance) {
        StandaloneSecret secret = secrets.get(connection.playerUUID);
        if (secret == null) {
            return;
        }
        int size = data.length + 64;
        byte flags = 0;
        if (whispering) {
            flags |= 0b1;
        }
        final byte finalFlags = flags;
        byte[] payload = buildPacket(TYPE_PLAYER_SOUND, buffer -> {
            buffer.writeUUID(sender);
            buffer.writeUUID(sender);
            buffer.writeByteArray(data);
            buffer.writeLong(sequenceNumber);
            buffer.writeFloat(distance);
            buffer.writeByte(finalFlags);
        }, size);
        sendEncrypted(secret, connection.address, payload);
    }

    private void sendEncrypted(StandaloneSecret secret, SocketAddress address, byte[] payload) {
        try {
            byte[] encrypted = secret.encrypt(payload);
            ByteBufferWrapper wrapper = new ByteBufferWrapper(ByteBuffer.allocate(encrypted.length + 8));
            wrapper.writeByte(MAGIC_BYTE);
            wrapper.writeByteArray(encrypted);
            sendRaw(wrapper.toBytes(), address);
        } catch (Exception e) {
            logger.debug("Failed to send packet to {}", address);
        }
    }

    private void sendRaw(byte[] data, SocketAddress address) throws IOException {
        if (socket == null || socket.isClosed()) {
            return;
        }
        socket.send(new DatagramPacket(data, data.length, address));
    }

    private byte[] buildPacket(byte type, PacketWriter writer, int size) {
        ByteBufferWrapper buffer = new ByteBufferWrapper(ByteBuffer.allocate(size));
        buffer.writeByte(type);
        writer.write(buffer);
        return buffer.toBytes();
    }

    private class ControlServer {
        private final int port;
        private Thread thread;
        private ServerSocket serverSocket;
        private ControlConnection connection;

        public ControlServer(int port) {
            this.port = port;
        }

        public void start() {
            thread = new Thread(this::run, "VoiceChatStandaloneControl");
            thread.start();
        }

        public void close() {
            try {
                if (connection != null) {
                    connection.close();
                }
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (Exception ignored) {
            }
        }

        private void run() {
            try {
                serverSocket = createServerSocket();
                logger.info("Standalone control server started at port {}", port);
                while (running && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    if (connection != null) {
                        connection.close();
                    }
                    connection = new ControlConnection(socket);
                    connection.start();
                }
            } catch (Exception e) {
                if (running) {
                    logger.error("Standalone control server failed", e);
                }
            }
        }

        private ServerSocket createServerSocket() throws IOException {
            String bindAddress = config.bindAddress.get().trim();
            if (bindAddress.isEmpty() || bindAddress.equals("*")) {
                return new ServerSocket(port);
            }
            InetAddress address = InetAddress.getByName(bindAddress);
            ServerSocket socket = new ServerSocket();
            socket.bind(new InetSocketAddress(address, port));
            return socket;
        }
    }

    private class ControlConnection extends Thread {
        private final Socket socket;
        private DataInputStream in;

        public ControlConnection(Socket socket) {
            super("VoiceChatStandaloneControlConnection");
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                while (running && !socket.isClosed()) {
                    int length = in.readInt();
                    if (length <= 0) {
                        continue;
                    }
                    byte[] data = new byte[length];
                    in.readFully(data);
                    handleControlMessage(data);
                }
            } catch (EOFException ignored) {
            } catch (Exception e) {
                if (running) {
                    logger.warn("Control connection error", e);
                }
            } finally {
                close();
            }
        }

        public void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }

        private void handleControlMessage(byte[] data) throws IOException {
            DataInputStream payload = new DataInputStream(new ByteArrayInputStream(data));
            byte type = payload.readByte();
            if (type == MSG_HELLO) {
                int protocolVersion = payload.readInt();
                int compatibilityVersion = payload.readInt();
                if (protocolVersion != 1) {
                    logger.warn("Unsupported control protocol {}", protocolVersion);
                }
                if (compatibilityVersion != BuildConstants.COMPATIBILITY_VERSION) {
                    logger.warn("Standalone compatibility mismatch (server={}, client={})", BuildConstants.COMPATIBILITY_VERSION, compatibilityVersion);
                }
                keepAlive = payload.readInt();
                voiceChatDistance = payload.readDouble();
                whisperDistance = payload.readDouble();
                broadcastRange = payload.readDouble();
                return;
            }
            if (type == MSG_PLAYER_SECRET) {
                UUID player = readUUID(payload);
                byte[] secretBytes = new byte[StandaloneSecret.SECRET_SIZE_BYTES];
                payload.readFully(secretBytes);
                secrets.put(player, StandaloneSecret.fromBytes(secretBytes));
                return;
            }
            if (type == MSG_PLAYER_STATE) {
                UUID player = readUUID(payload);
                boolean disabled = payload.readBoolean();
                boolean disconnected = payload.readBoolean();
                boolean hasGroup = payload.readBoolean();
                UUID groupId = null;
                byte groupType = GROUP_TYPE_NORMAL;
                if (hasGroup) {
                    groupId = readUUID(payload);
                    groupType = payload.readByte();
                }
                StandalonePlayerState state = states.computeIfAbsent(player, StandalonePlayerState::new);
                state.disabled = disabled;
                state.disconnected = disconnected;
                state.groupId = groupId;
                state.groupType = groupType;
                return;
            }
            if (type == MSG_PLAYER_POSITION) {
                UUID player = readUUID(payload);
                double x = payload.readDouble();
                double y = payload.readDouble();
                double z = payload.readDouble();
                String dimension = readString(payload);
                StandalonePlayerState state = states.computeIfAbsent(player, StandalonePlayerState::new);
                state.x = x;
                state.y = y;
                state.z = z;
                state.dimension = dimension;
                return;
            }
            if (type == MSG_PLAYER_REMOVE) {
                UUID player = readUUID(payload);
                states.remove(player);
                secrets.remove(player);
                connections.remove(player);
                unCheckedConnections.remove(player);
            }
        }
    }

    private static UUID readUUID(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0) {
            return "";
        }
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private interface PacketWriter {
        void write(ByteBufferWrapper buffer);
    }

    private static class RawUdpPacket {
        private final byte[] data;
        private final SocketAddress address;
        private final long timestamp;

        public RawUdpPacket(byte[] data, SocketAddress address, long timestamp) {
            this.data = data;
            this.address = address;
            this.timestamp = timestamp;
        }
    }

    private static class ClientConnection {
        private final UUID playerUUID;
        private final SocketAddress address;
        private long lastKeepAliveResponse;

        public ClientConnection(UUID playerUUID, SocketAddress address) {
            this.playerUUID = playerUUID;
            this.address = address;
            this.lastKeepAliveResponse = System.currentTimeMillis();
        }

        public void setLastKeepAliveResponse(long lastKeepAliveResponse) {
            this.lastKeepAliveResponse = lastKeepAliveResponse;
        }
    }

    private static class StandalonePlayerState {
        private final UUID playerUUID;
        private double x;
        private double y;
        private double z;
        private String dimension;
        private boolean disabled;
        private boolean disconnected;
        private UUID groupId;
        private byte groupType;

        public StandalonePlayerState(UUID playerUUID) {
            this.playerUUID = playerUUID;
            this.dimension = "";
            this.groupType = GROUP_TYPE_NORMAL;
        }

        public boolean hasPosition() {
            return !dimension.isEmpty();
        }

        public boolean isSameDimension(StandalonePlayerState other) {
            return dimension.equals(other.dimension);
        }
    }
}
