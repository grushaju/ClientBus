package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_occurrence", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notification_occurrence_key_uq", columnNames = {"organization_id", "occurrence_key"})
    },
    indexes = {
        @Index(name = "notification_occurrence_retention_idx", columnList = "dedup_until")
    },
    check = @CheckConstraint(name = "notification_occurrence_ck", constraint =
            "dedup_until > created_at and origin = 'LIVE'"))
public class NotificationOccurrenceEntity extends NotificationRecord {
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "occurrence_key", nullable = false, updatable = false, length = 64)
    private String occurrenceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 20)
    private EventOrigin origin;

    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recipient_employee_ids", nullable = false, updatable = false, columnDefinition = "uuid[]")
    private UUID[] recipientEmployeeIds;

    @Column(name = "dedup_until", nullable = false)
    private Instant dedupUntil;

    public NotificationOccurrenceEntity() {}

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getOccurrenceKey() { return occurrenceKey; }
    public void setOccurrenceKey(String occurrenceKey) { this.occurrenceKey = occurrenceKey; }
    public EventOrigin getOrigin() { return origin; }
    public void setOrigin(EventOrigin origin) { this.origin = origin; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public UUID[] getRecipientEmployeeIds() { return recipientEmployeeIds; }
    public void setRecipientEmployeeIds(UUID[] recipientEmployeeIds) { this.recipientEmployeeIds = recipientEmployeeIds; }
    public Instant getDedupUntil() { return dedupUntil; }
    public void setDedupUntil(Instant dedupUntil) { this.dedupUntil = dedupUntil; }
}
