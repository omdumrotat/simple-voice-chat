package de.maxhenkel.voicechat.config;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.configbuilder.entry.ConfigEntry;
import de.maxhenkel.voicechat.BuildConstants;

public class StandaloneConfig {

    public ConfigEntry<Integer> port;
    public ConfigEntry<String> bindAddress;
    public ConfigEntry<Integer> controlPort;
    public ConfigEntry<Boolean> allowPings;
    public ConfigEntry<Integer> keepAlive;
    public ConfigEntry<Double> voiceChatDistance;
    public ConfigEntry<Double> whisperDistance;
    public ConfigEntry<Double> broadcastRange;

    public StandaloneConfig(ConfigBuilder builder) {
        builder.header(String.format("Simple Voice Chat standalone config v%s", BuildConstants.MOD_VERSION));

        port = builder
                .integerEntry("port", 24454, 1, 65535,
                        "The port number to use for the voice chat UDP server"
                );

        bindAddress = builder
                .stringEntry("bind_address", "",
                        "The IP address to bind the voice chat UDP server to",
                        "Leave blank to bind to all available addresses",
                        "To bind to the wildcard IP address, use '*'"
                );

        controlPort = builder
                .integerEntry("control_port", -1, -1, 65535,
                        "The TCP port to use for control updates from the game server",
                        "Set to '-1' to use the same value as 'port'"
                );

        allowPings = builder
                .booleanEntry("allow_pings", true,
                        "If the standalone server should reply to external pings"
                );

        keepAlive = builder
                .integerEntry("keep_alive", 1000, 1000, Integer.MAX_VALUE,
                        "The frequency at which keep-alive packets are sent (in milliseconds)"
                );

        voiceChatDistance = builder
                .doubleEntry("max_voice_distance", 48D, 1D, 1_000_000D,
                        "The distance to which the voice can be heard"
                );

        whisperDistance = builder
                .doubleEntry("whisper_distance", 24D, 1D, 1_000_000D,
                        "The distance to which the voice can be heard when whispering"
                );

        broadcastRange = builder
                .doubleEntry("broadcast_range", -1D, -1D, Double.MAX_VALUE,
                        "The range in which the voice chat should broadcast audio",
                        "A value less than 0 means 'max_voice_distance'"
                );
    }
}
