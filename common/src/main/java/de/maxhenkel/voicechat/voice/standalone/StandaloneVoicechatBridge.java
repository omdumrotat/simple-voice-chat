package de.maxhenkel.voicechat.voice.standalone;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.debug.VoicechatUncaughtExceptionHandler;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.plugins.impl.GroupImpl;
import de.maxhenkel.voicechat.voice.common.NamedThreadPoolFactory;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.common.Secret;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.PlayerStateManager;
import de.maxhenkel.voicechat.voice.server.Server;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class StandaloneVoicechatBridge {

    private static final int CONTROL_PROTOCOL_VERSION = 1;
    private static final byte MSG_HELLO = 0x1;
    private static final byte MSG_PLAYER_SECRET = 0x2;
    private static final byte MSG_PLAYER_STATE = 0x3;
    private static final byte MSG_PLAYER_POSITION = 0x4;
    private static final byte MSG_PLAYER_REMOVE = 0x5;

    private final BlockingQueue<StandaloneMessage> messageQueue;
    private final AtomicBoolean running;
    private ScheduledExecutorService scheduler;
    private Thread senderThread;
    private MinecraftServer server;
    private StandaloneTarget target;
    private volatile Socket socket;
    private volatile DataOutputStream out;

    public StandaloneVoicechatBridge() {
        messageQueue = new LinkedBlockingQueue<>();
        running = new AtomicBoolean(false);
    }

    public void start(MinecraftServer server) {
        if (running.get()) {
            return;
        }
        target = StandaloneTarget.fromConfig();
        if (target == null) {
            return;
        }
        this.server = server;
        running.set(true);

        scheduler = Executors.newSingleThreadScheduledExecutor(NamedThreadPoolFactory.create("VoiceChatStandaloneSync"));
        scheduler.scheduleAtFixedRate(this::queuePlayerUpdates, 0L, Voicechat.SERVER_CONFIG.standaloneUpdateInterval.get(), TimeUnit.MILLISECONDS);

        senderThread = new Thread(this::runSender, "VoiceChatStandaloneBridgeSender");
        senderThread.setDaemon(true);
        senderThread.setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        senderThread.start();
    }

    public void stop() {
        running.set(false);
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (senderThread != null) {
            senderThread.interrupt();
            senderThread = null;
        }
        messageQueue.clear();
        closeSocket();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void sendSecret(UUID playerUUID, Secret secret) {
        enqueue(message(MSG_PLAYER_SECRET, out -> {
            writeUUID(out, playerUUID);
            out.write(secret.getSecret());
        }));
    }

    public void removePlayer(UUID playerUUID) {
        enqueue(message(MSG_PLAYER_REMOVE, out -> writeUUID(out, playerUUID)));
    }

    private void queuePlayerUpdates() {
        if (!running.get()) {
            return;
        }
        MinecraftServer mcServer = server;
        if (mcServer == null) {
            return;
        }
        CommonCompatibilityManager.INSTANCE.execute(mcServer, () -> {
            Server voiceServer = Voicechat.SERVER.getServer();
            if (voiceServer == null) {
                return;
            }
            for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                queueStateAndPosition(player, voiceServer);
            }
        });
    }

    private void queueFullSync() {
        MinecraftServer mcServer = server;
        if (mcServer == null) {
            return;
        }
        CommonCompatibilityManager.INSTANCE.execute(mcServer, () -> {
            Server voiceServer = Voicechat.SERVER.getServer();
            if (voiceServer == null) {
                return;
            }
            for (ServerPlayer player : mcServer.getPlayerList().getPlayers()) {
                queueStateAndPosition(player, voiceServer);
                if (voiceServer.hasSecret(player.getUUID())) {
                    enqueue(message(MSG_PLAYER_SECRET, out -> {
                        writeUUID(out, player.getUUID());
                        out.write(voiceServer.getSecret(player.getUUID()).getSecret());
                    }));
                }
            }
        });
    }

    private void queueStateAndPosition(ServerPlayer player, Server voiceServer) {
        PlayerState state = voiceServer.getPlayerStateManager().getState(player.getUUID());
        if (state == null) {
            state = PlayerStateManager.defaultDisconnectedState(player);
        }
        final PlayerState finalState = state;
        UUID groupId = state.getGroup();
        byte groupType = toGroupType(groupId == null ? null : voiceServer.getGroupManager().getGroup(groupId));

        enqueue(message(MSG_PLAYER_STATE, out -> {
            writeUUID(out, player.getUUID());
            out.writeBoolean(finalState.isDisabled());
            out.writeBoolean(finalState.isDisconnected());
            out.writeBoolean(groupId != null);
            if (groupId != null) {
                writeUUID(out, groupId);
                out.writeByte(groupType);
            }
        }));

        String dimension = player.level().dimension().identifier().toString();
        enqueue(message(MSG_PLAYER_POSITION, out -> {
            writeUUID(out, player.getUUID());
            out.writeDouble(player.position().x);
            out.writeDouble(player.position().y);
            out.writeDouble(player.position().z);
            writeString(out, dimension);
        }));
    }

    private void runSender() {
        while (running.get()) {
            try {
                if (!ensureConnected()) {
                    Thread.sleep(2000L);
                    continue;
                }
                StandaloneMessage message = messageQueue.poll(250L, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                message.write(out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Voicechat.LOGGER.warn("Standalone voice chat bridge error", e);
                closeSocket();
            }
        }
        closeSocket();
    }

    private boolean ensureConnected() {
        if (socket != null && socket.isConnected() && !socket.isClosed() && out != null) {
            return true;
        }
        closeSocket();
        if (target == null) {
            return false;
        }
        try {
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(target.host, target.port), 5000);
            socket = newSocket;
            out = new DataOutputStream(new BufferedOutputStream(newSocket.getOutputStream()));
            messageQueue.clear();
            enqueue(createHello());
            queueFullSync();
            Voicechat.LOGGER.info("Connected to standalone voice chat server at {}:{}", target.host, target.port);
            return true;
        } catch (Exception e) {
            Voicechat.LOGGER.warn("Failed to connect to standalone voice chat server at {}:{}", target.host, target.port);
            closeSocket();
            return false;
        }
    }

    private void closeSocket() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        out = null;
        socket = null;
    }

    private StandaloneMessage createHello() {
        return message(MSG_HELLO, out -> {
            out.writeInt(CONTROL_PROTOCOL_VERSION);
            out.writeInt(Voicechat.COMPATIBILITY_VERSION);
            out.writeInt(Voicechat.SERVER_CONFIG.keepAlive.get());
            out.writeDouble(Voicechat.SERVER_CONFIG.voiceChatDistance.get());
            out.writeDouble(Voicechat.SERVER_CONFIG.whisperDistance.get());
            out.writeDouble(Voicechat.SERVER_CONFIG.broadcastRange.get());
        });
    }

    private void enqueue(StandaloneMessage message) {
        if (!running.get()) {
            return;
        }
        messageQueue.offer(message);
    }

    private static StandaloneMessage message(byte type, MessageWriter writer) {
        return new StandaloneMessage(type, writer);
    }

    private static byte toGroupType(@Nullable Group group) {
        if (group == null) {
            return 0;
        }
        return (byte) GroupImpl.TypeImpl.toInt(group.getType());
    }

    private static void writeUUID(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static class StandaloneTarget {
        private final String host;
        private final int port;

        private StandaloneTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Nullable
        public static StandaloneTarget fromConfig() {
            if (!Voicechat.SERVER_CONFIG.standaloneServer.get()) {
                return null;
            }
            String host = Voicechat.SERVER_CONFIG.standaloneHost.get().trim();
            if (host.isEmpty()) {
                Voicechat.LOGGER.warn("Standalone server is enabled but no host is configured");
                return null;
            }
            return new StandaloneTarget(host, Voicechat.SERVER_CONFIG.standalonePort.get());
        }
    }

    private interface MessageWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private static class StandaloneMessage {
        private final byte type;
        private final MessageWriter writer;

        public StandaloneMessage(byte type, MessageWriter writer) {
            this.type = type;
            this.writer = writer;
        }

        public void write(DataOutputStream out) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(buffer);
            payload.writeByte(type);
            writer.write(payload);
            payload.flush();
            byte[] data = buffer.toByteArray();
            out.writeInt(data.length);
            out.write(data);
            out.flush();
        }
    }
}
