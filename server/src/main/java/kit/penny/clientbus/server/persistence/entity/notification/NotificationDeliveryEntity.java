package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notificationdelivery", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notificationdeliverypk", columnNames = {"id"}),
        @UniqueConstraint(name = "notificationdeliveryintentuq", columnNames = {"notificationid", "registrationid", "bindingversion", "channel"})
    },
    indexes = {
        @Index(name = "notificationdeliverypollidx", columnList = "status, nextattemptat, createdat"),
        @Index(name = "notificationdeliveryleaseidx", columnList = "status, leaseuntil"),
        @Index(name = "notificationdeliveryretentionidx", columnList = "status, retainuntil")
    },
    check = @CheckConstraint(name = "notificationdeliveryck", constraint =
            "((status in ('PROVIDER_ACCEPTED', 'FAILED', 'CANCELLED', 'EXPIRED') and completedat is not null and retainuntil is not null and completedat >= createdat and retainuntil > completedat) or (status in ('PENDING', 'RETRY', 'IN_FLIGHT') and completedat is null and retainuntil is null)) and bindingversion > 0 and (attemptendpointversion is null or attemptendpointversion > 0) and attempts >= 0 and claimversion >= 0 and expiresat > createdat and channel = 'MOBILE_PUSH' and ((status = 'IN_FLIGHT' and leaseuntil is not null and attemptendpointversion is not null) or (status <> 'IN_FLIGHT' and leaseuntil is null))"))
public class NotificationDeliveryEntity extends NotificationRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notificationid", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notificationdeliverynotificationidfk"))
    private NotificationEntity notification;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registrationid", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notificationdeliveryregistrationidfk"))
    private PushInstallationEntity registration;

    @Column(name = "bindingversion", nullable = false, updatable = false)
    private long bindingVersion;

    @Column(name = "channel", nullable = false, updatable = false, length = 24)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DeliveryStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "claimversion", nullable = false)
    private long claimVersion;

    @Column(name = "attemptendpointversion")
    private Long attemptEndpointVersion;

    @Column(name = "nextattemptat", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "leaseuntil")
    private Instant leaseUntil;

    @Column(name = "expiresat", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "completedat")
    private Instant completedAt;

    @Column(name = "retainuntil")
    private Instant retainUntil;

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getRetainUntil() { return retainUntil; }
    public void setRetainUntil(Instant retainUntil) { this.retainUntil = retainUntil; }

    @Column(name = "providermessageid", length = 512)
    private String providerMessageId;

    @Column(name = "errorcode", length = 64)
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
