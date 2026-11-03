package kit.penny.clientbus.crm.api;

import java.util.Arrays;
import java.util.UUID;

/** Per-call access. Secrets are never part of a DTO, cache key or generated toString. */
public final class CrmAccess implements AutoCloseable {
    private final UUID connectionId;
    private final long credentialVersion;
    private final String login;
    private final char[] secret;

    public CrmAccess(UUID connectionId, long credentialVersion, String login, char[] secret) {
        this.connectionId = connectionId; this.credentialVersion = credentialVersion;
        this.login = login; this.secret = secret.clone();
    }
    public UUID connectionId() { return connectionId; }
    public long credentialVersion() { return credentialVersion; }
    public String login() { return login; }
    public char[] secret() { return secret.clone(); }
    @Override public void close() { Arrays.fill(secret, '\0'); }
    @Override public String toString() { return "CrmAccess[redacted]"; }
}

