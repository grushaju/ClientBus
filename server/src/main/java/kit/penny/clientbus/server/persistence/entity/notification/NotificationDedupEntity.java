package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_dedup", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notification_dedup_recipient_key_uq", columnNames = {"organization_id", "recipient_employee_id", "deduplication_key"})
    },
    indexes = {
        @Index(name = "notification_dedup_retention_idx", columnList = "dedup_until")
    },
    check = @CheckConstraint(name = "notification_dedup_ck", constraint =
            "dedup_until > created_at"))
public class NotificationDedupEntity extends NotificationRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "occurrence_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notification_dedup_occurrence_id_fk"))
    private NotificationOccurrenceEntity occurrence;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "recipient_employee_id", nullable = false, updatable = false)
    private UUID recipientEmployeeId;

    @Column(name = "deduplication_key", nullable = false, updatable = false, length = 64)
    private String deduplicationKey;

    @Column(name = "dedup_until", nullable = false)
    private Instant dedupUntil;

    public NotificationDedupEntity() {}

    public NotificationOccurrenceEntity getOccurrence() { return occurrence; }
    public void setOccurrence(NotificationOccurrenceEntity occurrence) { this.occurrence = occurrence; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getRecipientEmployeeId() { return recipientEmployeeId; }
    public void setRecipientEmployeeId(UUID recipientEmployeeId) { this.recipientEmployeeId = recipientEmployeeId; }
    public String getDeduplicationKey() { return deduplicationKey; }
    public void setDeduplicationKey(String deduplicationKey) { this.deduplicationKey = deduplicationKey; }
    public Instant getDedupUntil() { return dedupUntil; }
    public void setDedupUntil(Instant dedupUntil) { this.dedupUntil = dedupUntil; }
}
