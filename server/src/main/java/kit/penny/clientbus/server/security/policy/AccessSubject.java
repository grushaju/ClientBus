package kit.penny.clientbus.server.security.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Trusted BE identity, never populated from a public request body. Roles are read from DB. */
public record AccessSubject(UUID employeeId, Instant expiresAt) {
    public AccessSubject { Objects.requireNonNull(employeeId); }
    public static AccessSubject recipient(UUID employeeId) { return new AccessSubject(employeeId, null); }
    public static AccessSubject session(UUID employeeId, Instant expiresAt) {
        return new AccessSubject(employeeId, Objects.requireNonNull(expiresAt));
    }
    public boolean expired(Instant now) { return expiresAt != null && !now.isBefore(expiresAt); }
}
