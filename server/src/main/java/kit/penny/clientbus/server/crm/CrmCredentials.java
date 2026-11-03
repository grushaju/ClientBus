package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.enums.CrmType;
import kit.penny.clientbus.crm.api.CrmAccess;
import java.util.*;

public final class CrmCredentials {
    final UUID id, organizationId;
    final CrmType provider;
    final long version;
    private final String login, keyId;
    private final byte[] ciphertext;
    CrmCredentials(UUID id, UUID organizationId, CrmType provider, long version, String login, String keyId, byte[] ciphertext) {
        this.id = id; this.organizationId = organizationId; this.provider = provider; this.version = version;
        this.login = login; this.keyId = keyId; this.ciphertext = ciphertext.clone();
    }
    CrmAccess open(CrmSecretCipher cipher) {
        char[] secret = cipher.decrypt(organizationId, id, keyId, ciphertext);
        try { return new CrmAccess(id, version, login, secret); }
        finally { Arrays.fill(secret, '\0'); }
    }
    @Override public String toString() { return "CrmCredentials[redacted]"; }
}

