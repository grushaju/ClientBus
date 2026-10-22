package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_command", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notification_command_key_uq", columnNames = {"organization_id", "recipient_employee_id", "idempotency_key"})
    },
    indexes = {
        @Index(name = "notification_command_retention_idx", columnList = "retain_until")
    },
    check = @CheckConstraint(name = "notification_command_ck", constraint =
            "updated_count >= 0 and retain_until > created_at"))
public class NotificationCommandEntity extends NotificationRecord {
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "recipient_employee_id", nullable = false, updatable = false)
    private UUID recipientEmployeeId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "scope", nullable = false, updatable = false, length = 32)
    private String scope;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    @Column(name = "updated_count", nullable = false)
    private long updatedCount;

    @Column(name = "retain_until", nullable = false)
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
