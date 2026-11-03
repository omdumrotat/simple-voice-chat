package de.maxhenkel.voicechat.voice.standalone;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Authenticated encryption for the control connection between the game server and the standalone server.
 * <p>
 * Both sides derive a key from the shared password (PBKDF2). Each connection exchanges a random nonce from both
 * sides, which are mixed into one independent AES-256-GCM key per direction. Every frame is encrypted with a strictly
 * increasing counter as the GCM nonce, so frames can't be read, modified, replayed or reordered by a third party.
 * The password itself is never sent. A peer that doesn't know the password can't produce a frame that decrypts.
 * <p>
 * This class is intentionally duplicated in the game server plugin/mod and in the standalone server, as they don't
 * share a module.
 */
public final class ControlCrypto {

    public static final int NONCE_SIZE = 16;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final byte[] SALT = "simple-voice-chat-standalone-control-v3".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static String cachedPassword;
    private static byte[] cachedKey;

    private ControlCrypto() {
    }

    public static byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_SIZE];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    public static synchronized byte[] deriveKey(String password) throws GeneralSecurityException {
        if (password.equals(cachedPassword)) {
            return cachedKey;
        }
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] key = factory.generateSecret(new PBEKeySpec(password.toCharArray(), SALT, PBKDF2_ITERATIONS, 256)).getEncoded();
        cachedPassword = password;
        cachedKey = key;
        return key;
    }

    /**
     * @param key         the key from {@link #deriveKey(String)}
     * @param serverNonce the nonce sent by the standalone server
     * @param clientNonce the nonce sent by the game server
     * @param server      if this is the standalone server side
     */
    public static Session newSession(byte[] key, byte[] serverNonce, byte[] clientNonce, boolean server) throws GeneralSecurityException {
        byte[] c2s = hmac(key, "c2s", serverNonce, clientNonce);
        byte[] s2c = hmac(key, "s2c", serverNonce, clientNonce);
        return server ? new Session(s2c, c2s) : new Session(c2s, s2c);
    }

    private static byte[] hmac(byte[] key, String label, byte[] serverNonce, byte[] clientNonce) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(label.getBytes(StandardCharsets.UTF_8));
        mac.update(serverNonce);
        mac.update(clientNonce);
        return mac.doFinal();
    }

    public static final class Session {

        private final SecretKeySpec sendKey;
        private final SecretKeySpec receiveKey;
        private long sendCounter;
        private long receiveCounter;

        private Session(byte[] sendKey, byte[] receiveKey) {
            this.sendKey = new SecretKeySpec(sendKey, "AES");
            this.receiveKey = new SecretKeySpec(receiveKey, "AES");
        }

        public synchronized byte[] encrypt(byte[] plain) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(128, nonce(sendCounter)));
            byte[] encrypted = cipher.doFinal(plain);
            sendCounter++;
            return encrypted;
        }

        /**
         * @throws GeneralSecurityException if the frame wasn't encrypted with the same key/counter or was modified
         */
        public synchronized byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, new GCMParameterSpec(128, nonce(receiveCounter)));
            byte[] plain = cipher.doFinal(encrypted);
            receiveCounter++;
            return plain;
        }

        private static byte[] nonce(long counter) {
            return ByteBuffer.allocate(12).putInt(0).putLong(counter).array();
        }
    }

}
