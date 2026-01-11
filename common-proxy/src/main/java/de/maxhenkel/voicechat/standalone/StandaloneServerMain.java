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

        StandaloneVoicechatServer server = new StandaloneVoicechatServer(logger, config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "VoiceChatStandaloneShutdown"));
    }
}
