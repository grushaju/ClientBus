package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notificationcommand", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notificationcommandpk", columnNames = {"id"}),
        @UniqueConstraint(name = "notificationcommandkeyuq", columnNames = {"organizationid", "recipientemployeeid", "idempotencykey"})
    },
    indexes = {
        @Index(name = "notificationcommandretentionidx", columnList = "retainuntil")
    },
    check = @CheckConstraint(name = "notificationcommandck", constraint =
            "updatedcount >= 0 and retainuntil > createdat"))
public class NotificationCommandEntity extends NotificationRecord {
    @Column(name = "organizationid", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "recipientemployeeid", nullable = false, updatable = false)
    private UUID recipientEmployeeId;

    @Column(name = "idempotencykey", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "requesthash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "scope", nullable = false, updatable = false, length = 32)
    private String scope;

    @Column(name = "readat", nullable = false)
    private Instant readAt;

    @Column(name = "updatedcount", nullable = false)
    private long updatedCount;

    @Column(name = "retainuntil", nullable = false)
    private Instant retainUntil;

    public NotificationCommandEntity() {}

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getRecipientEmployeeId() { return recipientEmployeeId; }
    public void setRecipientEmployeeId(UUID recipientEmployeeId) { this.recipientEmployeeId = recipientEmployeeId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public long getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(long updatedCount) { this.updatedCount = updatedCount; }
    public Instant getRetainUntil() { return retainUntil; }
    public void setRetainUntil(Instant retainUntil) { this.retainUntil = retainUntil; }
}
