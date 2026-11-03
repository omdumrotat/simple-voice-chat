package de.maxhenkel.voicechat.standalone;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

public class StandaloneSecret {

    public static final int SECRET_SIZE_BYTES = 16;
    public static final int IV_SIZE_BYTES = 16;
    // Compatibility version 18 (Minecraft 1.20.6 / voice chat 2.5.x): AES-128-CBC, key = secret UUID bytes,
    // payload = IV (16 bytes) + ciphertext
    public static final String CIPHER = "AES/CBC/PKCS5Padding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;
    private final SecretKeySpec keySpec;

    private StandaloneSecret(byte[] secret) {
        this.secret = secret;
        this.keySpec = new SecretKeySpec(secret, "AES");
    }

    public static StandaloneSecret fromBytes(byte[] secret) {
        return new StandaloneSecret(secret);
    }

    public byte[] getSecret() {
        return secret;
    }

    public static byte[] generateIV() {
        byte[] iv = new byte[IV_SIZE_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    public byte[] encrypt(byte[] data) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] iv = generateIV();
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
        byte[] enc = cipher.doFinal(data);
        byte[] payload = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(enc, 0, payload, iv.length, enc.length);
        return payload;
    }

    public byte[] decrypt(byte[] payload) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_SIZE_BYTES);
        byte[] data = Arrays.copyOfRange(payload, IV_SIZE_BYTES, payload.length);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StandaloneSecret)) {
            return false;
        }
        return Arrays.equals(secret, ((StandaloneSecret) o).secret);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(secret);
    }
}
