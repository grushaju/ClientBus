package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notification_delivery_intent_uq", columnNames = {"notification_id", "registration_id", "binding_version", "channel"})
    },
    indexes = {
        @Index(name = "notification_delivery_poll_idx", columnList = "status, next_attempt_at, created_at"),
        @Index(name = "notification_delivery_lease_idx", columnList = "status, lease_until"),
        @Index(name = "notification_delivery_retention_idx", columnList = "status, retain_until")
    },
    check = @CheckConstraint(name = "notification_delivery_ck", constraint =
            "((status in ('PROVIDER_ACCEPTED', 'FAILED', 'CANCELLED', 'EXPIRED') and completed_at is not null and retain_until is not null and completed_at >= created_at and retain_until > completed_at) or (status in ('PENDING', 'RETRY', 'IN_FLIGHT') and completed_at is null and retain_until is null)) and binding_version > 0 and (attempt_endpoint_version is null or attempt_endpoint_version > 0) and attempts >= 0 and claim_version >= 0 and expires_at > created_at and channel = 'MOBILE_PUSH' and ((status = 'IN_FLIGHT' and lease_until is not null and attempt_endpoint_version is not null) or (status <> 'IN_FLIGHT' and lease_until is null))"))
public class NotificationDeliveryEntity extends NotificationRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notification_delivery_notification_id_fk"))
    private NotificationEntity notification;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notification_delivery_registration_id_fk"))
    private PushInstallationEntity registration;

    @Column(name = "binding_version", nullable = false, updatable = false)
    private long bindingVersion;

    @Column(name = "channel", nullable = false, updatable = false, length = 24)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DeliveryStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "claim_version", nullable = false)
    private long claimVersion;

    @Column(name = "attempt_endpoint_version")
    private Long attemptEndpointVersion;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "retain_until")
    private Instant retainUntil;

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getRetainUntil() { return retainUntil; }
    public void setRetainUntil(Instant retainUntil) { this.retainUntil = retainUntil; }

    @Column(name = "provider_message_id", length = 512)
    private String providerMessageId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    public NotificationDeliveryEntity() {}

    public NotificationEntity getNotification() { return notification; }
    public void setNotification(NotificationEntity notification) { this.notification = notification; }
    public PushInstallationEntity getRegistration() { return registration; }
    public void setRegistration(PushInstallationEntity registration) { this.registration = registration; }
    public long getBindingVersion() { return bindingVersion; }
    public void setBindingVersion(long bindingVersion) { this.bindingVersion = bindingVersion; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public long getClaimVersion() { return claimVersion; }
    public void setClaimVersion(long claimVersion) { this.claimVersion = claimVersion; }
    public Long getAttemptEndpointVersion() { return attemptEndpointVersion; }
    public void setAttemptEndpointVersion(Long attemptEndpointVersion) { this.attemptEndpointVersion = attemptEndpointVersion; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
}
