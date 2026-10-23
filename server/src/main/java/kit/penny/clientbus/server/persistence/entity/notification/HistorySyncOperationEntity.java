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
@Table(name = "historysyncoperation", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "historysyncoperationpk", columnNames = {"id"}),
        @UniqueConstraint(name = "historysyncoperationkeyuq", columnNames = {"organizationid", "initiatoremployeeid", "idempotencykey"})
    },
    indexes = {
        @Index(name = "historysyncoperationdiscoveryidx", columnList = "initiatoremployeeid, conversationid, createdat DESC, id DESC"),
        @Index(name = "historysyncoperationwatchdogidx", columnList = "status, deadlineat"),
        @Index(name = "historysyncoperationleaseidx", columnList = "status, leaseuntil"),
        @Index(name = "historysyncoperationretentionidx", columnList = "retainuntil")
    },
    check = @CheckConstraint(name = "historysyncoperationck", constraint =
            "requestlimit between 1 and 100 and claimversion >= 0 and attempts >= 0 and deadlineat > createdat and retainuntil > deadlineat and ((status in ('SUCCEEDED', 'FAILED') and completedat is not null and leaseuntil is null) or (status in ('PENDING', 'RUNNING') and completedat is null)) and ((status = 'RUNNING' and leaseuntil is not null) or (status <> 'RUNNING' and leaseuntil is null)) and ((status = 'SUCCEEDED' and result is not null and errorcode is null) or (status <> 'SUCCEEDED' and result is null)) and (status <> 'FAILED' or errorcode is not null) and (result is null or jsonb_typeof(result) = 'object')"))
public class HistorySyncOperationEntity extends NotificationRecord {
    @Column(name = "organizationid", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "workspaceid", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "conversationid", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "channelaccountid", nullable = false, updatable = false)
    private UUID channelAccountId;

    @Column(name = "initiatoremployeeid", nullable = false, updatable = false)
    private UUID initiatorEmployeeId;

    @Column(name = "idempotencykey", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "requesthash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "beforeexternalid", updatable = false, length = 256)
    private String beforeExternalId;

    @Column(name = "requestlimit", nullable = false, updatable = false)
    private int requestLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private HistoryOperationStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "claimversion", nullable = false)
    private long claimVersion;

    @Column(name = "leaseuntil")
    private Instant leaseUntil;

    @Column(name = "deadlineat", nullable = false, updatable = false)
    private Instant deadlineAt;

    @Column(name = "completedat")
    private Instant completedAt;

    @Column(name = "updatedat", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "errorcode", length = 64)
    private String errorCode;

    @Column(name = "retainuntil", nullable = false)
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
