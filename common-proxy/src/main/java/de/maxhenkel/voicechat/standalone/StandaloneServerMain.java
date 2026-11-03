package de.maxhenkel.voicechat.standalone;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.config.StandaloneConfig;
import de.maxhenkel.voicechat.logging.SystemOutLogger;
import de.maxhenkel.voicechat.logging.VoiceChatLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

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

        if (config.controlPassword.get().isEmpty() && !generateControlPassword(logger, config, configPath)) {
            System.exit(1);
            return;
        }

        StandaloneVoicechatServer server = new StandaloneVoicechatServer(logger, config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "VoiceChatStandaloneShutdown"));
    }

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 40;

    /**
     * Generates a random control password, saves it to the config and prints it once so it can be copied to the
     * game server's 'standalone_password'.
     *
     * @return false if the password couldn't be persisted (it would be lost on the next start)
     */
    private static boolean generateControlPassword(VoiceChatLogger logger, StandaloneConfig config, Path configPath) {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        String generated = password.toString();
        config.controlPassword.set(generated).saveSync();

        // The config library only logs save errors, so make sure the password really is in the file
        boolean persisted;
        try {
            persisted = Files.readString(configPath, StandardCharsets.UTF_8).contains(generated);
        } catch (IOException e) {
            persisted = false;
        }
        if (!persisted) {
            logger.error("'control_password' is not set and the generated password couldn't be saved to {} (is the directory writable?) - refusing to start.", configPath.toAbsolutePath());
            return false;
        }
        logger.warn("'control_password' was not set. Generated a random one and saved it to {}", configPath.toAbsolutePath());
        logger.warn("Set this on the game server (voicechat-server.properties): standalone_password={}", generated);
        return true;
    }
}
