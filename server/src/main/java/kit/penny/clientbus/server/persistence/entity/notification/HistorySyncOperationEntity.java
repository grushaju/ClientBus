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
@Table(name = "history_sync_operation", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "history_sync_operation_key_uq", columnNames = {"organization_id", "initiator_employee_id", "idempotency_key"})
    },
    indexes = {
        @Index(name = "history_sync_operation_discovery_idx", columnList = "initiator_employee_id, conversation_id, created_at DESC, id DESC"),
        @Index(name = "history_sync_operation_watchdog_idx", columnList = "status, deadline_at"),
        @Index(name = "history_sync_operation_lease_idx", columnList = "status, lease_until"),
        @Index(name = "history_sync_operation_retention_idx", columnList = "retain_until")
    },
    check = @CheckConstraint(name = "history_sync_operation_ck", constraint =
            "request_limit between 1 and 100 and claim_version >= 0 and attempts >= 0 and deadline_at > created_at and retain_until > deadline_at and ((status in ('SUCCEEDED', 'FAILED') and completed_at is not null and lease_until is null) or (status in ('PENDING', 'RUNNING') and completed_at is null)) and ((status = 'RUNNING' and lease_until is not null) or (status <> 'RUNNING' and lease_until is null)) and ((status = 'SUCCEEDED' and result is not null and error_code is null) or (status <> 'SUCCEEDED' and result is null)) and (status <> 'FAILED' or error_code is not null) and (result is null or jsonb_typeof(result) = 'object')"))
public class HistorySyncOperationEntity extends NotificationRecord {
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "channel_account_id", nullable = false, updatable = false)
    private UUID channelAccountId;

    @Column(name = "initiator_employee_id", nullable = false, updatable = false)
    private UUID initiatorEmployeeId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "before_external_id", updatable = false, length = 256)
    private String beforeExternalId;

    @Column(name = "request_limit", nullable = false, updatable = false)
    private int requestLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private HistoryOperationStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "claim_version", nullable = false)
    private long claimVersion;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "deadline_at", nullable = false, updatable = false)
    private Instant deadlineAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "retain_until", nullable = false)
    private Instant retainUntil;

    public HistorySyncOperationEntity() {}

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public UUID getChannelAccountId() { return channelAccountId; }
    public void setChannelAccountId(UUID channelAccountId) { this.channelAccountId = channelAccountId; }
    public UUID getInitiatorEmployeeId() { return initiatorEmployeeId; }
    public void setInitiatorEmployeeId(UUID initiatorEmployeeId) { this.initiatorEmployeeId = initiatorEmployeeId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getBeforeExternalId() { return beforeExternalId; }
    public void setBeforeExternalId(String beforeExternalId) { this.beforeExternalId = beforeExternalId; }
    public int getRequestLimit() { return requestLimit; }
    public void setRequestLimit(int requestLimit) { this.requestLimit = requestLimit; }
    public HistoryOperationStatus getStatus() { return status; }
    public void setStatus(HistoryOperationStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public long getClaimVersion() { return claimVersion; }
    public void setClaimVersion(long claimVersion) { this.claimVersion = claimVersion; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Instant getRetainUntil() { return retainUntil; }
    public void setRetainUntil(Instant retainUntil) { this.retainUntil = retainUntil; }
}
