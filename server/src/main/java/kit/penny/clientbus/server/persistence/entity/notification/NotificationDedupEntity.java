package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notificationdedup", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notificationdeduppk", columnNames = {"id"}),
        @UniqueConstraint(name = "notificationdeduprecipientkeyuq", columnNames = {"organizationid", "recipientemployeeid", "deduplicationkey"})
    },
    indexes = {
        @Index(name = "notificationdedupretentionidx", columnList = "dedupuntil")
    },
    check = @CheckConstraint(name = "notificationdedupck", constraint =
            "dedupuntil > createdat"))
public class NotificationDedupEntity extends NotificationRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "occurrenceid", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notificationdedupoccurrenceidfk"))
    private NotificationOccurrenceEntity occurrence;

    @Column(name = "organizationid", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "recipientemployeeid", nullable = false, updatable = false)
    private UUID recipientEmployeeId;

    @Column(name = "deduplicationkey", nullable = false, updatable = false, length = 64)
    private String deduplicationKey;

    @Column(name = "dedupuntil", nullable = false)
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
