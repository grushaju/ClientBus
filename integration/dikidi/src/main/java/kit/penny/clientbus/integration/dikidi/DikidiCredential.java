package kit.penny.clientbus.integration.dikidi;

import java.util.Arrays;
import java.util.UUID;

/**
 * Однократный технический доступ (13.4). Ключ сессии — (connectionId,
 * credentialVersion): одна активная сессия входа на версию учётных данных.
 * Секрет не входит в DTO/ключи/toString и обнуляется закрытием.
 */
public final class DikidiCredential implements AutoCloseable {
    private final UUID connectionId;
    private final long credentialVersion;
    private final String login;
    private final char[] secret;
    public DikidiCredential(UUID connectionId, long credentialVersion, String login, char[] secret) {
        this.connectionId = connectionId; this.credentialVersion = credentialVersion;
        this.login = login; this.secret = secret == null ? new char[0] : secret.clone();
    }
    public UUID connectionId() { return connectionId; }
    public long credentialVersion() { return credentialVersion; }
    public String login() { return login; }
    public char[] secret() { return secret.clone(); }
    @Override public void close() { Arrays.fill(secret, '\0'); }
    @Override public String toString() { return "DikidiCredential[redacted]"; }
}
