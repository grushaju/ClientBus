package kit.penny.clientbus.server.crm;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Component
public final class CrmSecretCipher {
    private final byte[] key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();
    public CrmSecretCipher(CrmSettings settings) {
        keyId = settings.secretKeyId();
        try { key = settings.secretKey().isBlank() ? null : Base64.getDecoder().decode(settings.secretKey()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid CRM encryption key"); }
        if (key != null && key.length != 32) throw new IllegalArgumentException("CRM encryption key must contain 32 bytes");
    }
    public boolean configured() { return key != null; }
    public String keyId() { return keyId; }
    private byte[] aad(UUID organizationId, UUID connectionId, String keyId) {
        return ("clientbus/crm-secret/1/" + organizationId + "/" + connectionId + "/" + keyId).getBytes(StandardCharsets.US_ASCII);
    }
    public byte[] encrypt(UUID organizationId, UUID connectionId, String secret) {
        if (!configured()) throw unavailable();
        byte[] clear = secret.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(organizationId, connectionId, keyId));
            byte[] encrypted = cipher.doFinal(clear);
            return ByteBuffer.allocate(13 + encrypted.length).put((byte)1).put(nonce).put(encrypted).array();
        } catch (java.security.GeneralSecurityException error) { throw unavailable(); }
        finally { Arrays.fill(clear, (byte)0); }
    }
    public char[] decrypt(UUID organizationId, UUID connectionId, String storedKeyId, byte[] encrypted) {
        if (!configured() || !keyId.equals(storedKeyId) || encrypted == null || encrypted.length < 29 || encrypted[0] != 1)
            throw unavailable();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, encrypted, 1, 12));
            cipher.updateAAD(aad(organizationId, connectionId, storedKeyId));
            byte[] clear = cipher.doFinal(encrypted, 13, encrypted.length - 13);
            try { return new String(clear, StandardCharsets.UTF_8).toCharArray(); }
            finally { Arrays.fill(clear, (byte)0); }
        } catch (java.security.GeneralSecurityException error) { throw unavailable(); }
    }
    private CrmException unavailable() {
        return new CrmException(409, "CRM_CONFIGURATION_REQUIRED", "Настройте ключ шифрования CRM на сервере.");
    }
}

