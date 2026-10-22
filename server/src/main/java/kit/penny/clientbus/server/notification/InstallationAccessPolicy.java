package kit.penny.clientbus.server.notification;

import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Proof verification grants no unconditional write: the caller must CAS registrationVersion. */
@Service
public class InstallationAccessPolicy {
    public enum Mutation { CREATE, UPDATE, REBIND, DELETE }
    /** Safe conflict metadata, exposed only after proof verification. Contains no owner data. */
    public static final class VersionConflict extends org.springframework.dao.OptimisticLockingFailureException {
        private final long currentVersion;
        public VersionConflict(long currentVersion) {
            super("INSTALLATION_CONFLICT");
            this.currentVersion = currentVersion;
        }
        public long currentVersion() { return currentVersion; }
        public String etag() { return "\"" + currentVersion + "\""; }
    }
    public record Binding(UUID organizationId, UUID recipientId, long registrationVersion,
                          long bindingVersion, long endpointVersion) {}
    private record Stored(Binding binding, byte[] proof, boolean owner) {}
    private final NamedParameterJdbcTemplate jdbc;
    public InstallationAccessPolicy(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** CREATE here handles an existing-ID collision; absent-ID creation belongs to the atomic writer. */
    public Binding requireMutation(AccessSubject subject, UUID installationId, String secret,
                                   long expectedVersion, Mutation mutation) {
        if (subject.expired(Instant.now()) || mutation == null
                || (mutation == Mutation.CREATE ? expectedVersion != 0 : expectedVersion < 1)
                || secret == null || !secret.matches("[A-Za-z0-9_-]{43,128}")) throw denied();
        var rows = jdbc.query("""
                select i.organization_id, i.recipient_employee_id, i.registration_version,
                       i.binding_version, i.endpoint_version, i.ownership_secret_hash,
                       (i.recipient_employee_id=e.employeeid and i.organization_id=e.organizationid) as owner
                from dbo.push_installation i cross join dbo.employee e
                join dbo.users u on u.userid=e.userid
                where i.id=:id and e.employeeid=:employee and u.isenabled
                  and u.role in ('EMPLOYEE','SUPER_ADMIN')
                """, Map.of("id", installationId, "employee", subject.employeeId()), (rs, n) ->
                new Stored(new Binding(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getLong(3), rs.getLong(4), rs.getLong(5)), rs.getBytes(6), rs.getBoolean(7)));
        if (rows.size() != 1) throw denied();
        var row = rows.getFirst();
        if (!MessageDigest.isEqual(row.proof(), hash(secret))
                || ((mutation == Mutation.UPDATE || mutation == Mutation.DELETE) && !row.owner())) throw denied();
        if (row.binding().registrationVersion() != expectedVersion)
            throw new VersionConflict(row.binding().registrationVersion());
        return row.binding();
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Access denied"); }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
