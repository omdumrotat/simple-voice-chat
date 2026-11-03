package de.maxhenkel.voicechat.standalone;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.config.StandaloneConfig;
import de.maxhenkel.voicechat.logging.SystemOutLogger;
import de.maxhenkel.voicechat.logging.VoiceChatLogger;

import java.nio.file.Path;

public class StandaloneServerMain {

    public static void main(String[] args) {
        VoiceChatLogger logger = new SystemOutLogger();
        Path configPath = Path.of(".").resolve("config").resolve("voicechat-standalone.properties");
        StandaloneConfig config;
        try {
            config = ConfigBuilder.builder(StandaloneConfig::new).path(configPath).build();
        } catch (Exception e) {
            logger.error("Failed to load standalone config", e);
            return;
        }

        if (config.controlPassword.get().isEmpty()) {
            logger.error("'control_password' is not set in {}. Set a long random password (and use the same value as 'standalone_password' on the game server) - refusing to start without it.", configPath.toAbsolutePath());
            System.exit(1);
            return;
        }

        StandaloneVoicechatServer server = new StandaloneVoicechatServer(logger, config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "VoiceChatStandaloneShutdown"));
    }
}
