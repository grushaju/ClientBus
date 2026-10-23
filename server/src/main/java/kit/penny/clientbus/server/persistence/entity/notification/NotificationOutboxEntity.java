package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;

@Entity
@Table(name = "notificationoutbox", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notificationoutboxpk", columnNames = {"id"}),
        @UniqueConstraint(name = "notificationoutboxeventuq", columnNames = {"eventid"})
    },
    indexes = {
        @Index(name = "notificationoutboxpollidx", columnList = "status, nextattemptat, createdat"),
        @Index(name = "notificationoutboxleaseidx", columnList = "status, leaseuntil"),
        @Index(name = "notificationoutboxpublishedidx", columnList = "publishedat")
    },
    check = @CheckConstraint(name = "notificationoutboxck", constraint =
            "schemaversion > 0 and attempts >= 0 and claimversion >= 0 and jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 65536 and ((status = 'IN_FLIGHT' and leaseuntil is not null) or (status <> 'IN_FLIGHT' and leaseuntil is null)) and ((status = 'PUBLISHED' and publishedat is not null) or (status <> 'PUBLISHED' and publishedat is null))"))
public class NotificationOutboxEntity extends NotificationRecord {
    @Column(name = "eventid", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "organizationid", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "destination", nullable = false, updatable = false, length = 128)
    private String destination;

    @Column(name = "eventtype", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "schemaversion", nullable = false, updatable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "claimversion", nullable = false)
    private long claimVersion;

    @Column(name = "nextattemptat", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "leaseuntil")
    private Instant leaseUntil;

    @Column(name = "publishedat")
    private Instant publishedAt;

    @Column(name = "errorcode", length = 64)
    private String errorCode;

    @Column(name = "operationid", updatable = false)
    private UUID operationId;

    public NotificationOutboxEntity() {}

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public long getClaimVersion() { return claimVersion; }
    public void setClaimVersion(long claimVersion) { this.claimVersion = claimVersion; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID operationId) { this.operationId = operationId; }
}
