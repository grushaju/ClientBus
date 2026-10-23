package kit.penny.clientbus.server.notification.push;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Version byte, random nonce, authenticated ciphertext. Key is supplied outside the database. */
@Component
public class PushEndpointCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    private static final byte[] AAD = "clientbus/push-endpoint/v1".getBytes(StandardCharsets.US_ASCII);
    public PushEndpointCipher(PushSettings settings) {
        try { key = settings.endpointKey().isBlank() ? null : Base64.getDecoder().decode(settings.endpointKey()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid push encryption key"); }
        if (key != null && key.length != 32) throw new IllegalArgumentException("Push encryption key must contain 32 bytes");
    }
    public void requireConfigured() { if (key == null) throw new Unavailable(); }
    public byte[] encrypt(String reference) {
        requireConfigured();
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(reference.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1+nonce.length+encrypted.length).put((byte)1).put(nonce).put(encrypted).array();
        } catch (java.security.GeneralSecurityException error) { throw new Unavailable(); }
    }
    public String decrypt(byte[] encrypted) {
        requireConfigured();
        if (encrypted == null || encrypted.length < 29 || encrypted[0] != 1) throw new Unavailable();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,encrypted,1,12));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(encrypted,13,encrypted.length-13),StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException error) { throw new Unavailable(); }
    }
    public static final class Unavailable extends RuntimeException { public Unavailable() { super("Push endpoint unavailable"); } }
}
