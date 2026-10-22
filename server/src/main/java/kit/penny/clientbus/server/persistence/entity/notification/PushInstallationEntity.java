package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "push_installation", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "push_installation_active_endpoint_uq", columnNames = {"provider", "app_id", "environment", "active_endpoint_fingerprint"})
    },
    indexes = {
        @Index(name = "push_installation_recipient_idx", columnList = "organization_id, recipient_employee_id, enabled"),
        @Index(name = "push_installation_activity_idx", columnList = "active_until")
    },
    check = @CheckConstraint(name = "push_installation_ck", constraint =
            "registration_version > 0 and binding_version > 0 and endpoint_version > 0 and octet_length(ownership_secret_hash) = 32 and endpoint_fingerprint ~ '^[0-9a-f]{64}$' and ((enabled and active_endpoint_fingerprint is not null and active_endpoint_fingerprint = endpoint_fingerprint) or (not enabled and active_endpoint_fingerprint is null)) and active_until > last_seen_at and ((platform = 'IOS') or provider <> 'APNS')"))
public class PushInstallationEntity extends NotificationRecord {
    @Version
    @org.hibernate.annotations.ColumnDefault("1")
    @Column(name = "registration_version", nullable = false)
    private long registrationVersion = 1;

    public long getRegistrationVersion() { return registrationVersion; }

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "recipient_employee_id", nullable = false)
    private UUID recipientEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private PushPlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private PushProviderType provider;

    @Column(name = "app_id", nullable = false, length = 128)
    private String appId;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 16)
    private PushEnvironment environment;

    @Column(name = "encrypted_endpoint", nullable = false)
    private byte[] encryptedEndpoint;

    @Column(name = "endpoint_fingerprint", nullable = false, length = 64)
    private String endpointFingerprint;

    @Column(name = "active_endpoint_fingerprint", length = 64)
    private String activeEndpointFingerprint;

    @Column(name = "ownership_secret_hash", nullable = false)
    private byte[] ownershipSecretHash;

    @Column(name = "binding_version", nullable = false)
    private long bindingVersion;

    @Column(name = "endpoint_version", nullable = false)
    private long endpointVersion;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "reported_permission", nullable = false, length = 24)
    private PushPermission reportedPermission;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "active_until", nullable = false)
    private Instant activeUntil;

    public PushInstallationEntity() {}

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getRecipientEmployeeId() { return recipientEmployeeId; }
    public void setRecipientEmployeeId(UUID recipientEmployeeId) { this.recipientEmployeeId = recipientEmployeeId; }
    public PushPlatform getPlatform() { return platform; }
    public void setPlatform(PushPlatform platform) { this.platform = platform; }
    public PushProviderType getProvider() { return provider; }
    public void setProvider(PushProviderType provider) { this.provider = provider; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public PushEnvironment getEnvironment() { return environment; }
    public void setEnvironment(PushEnvironment environment) { this.environment = environment; }
    public byte[] getEncryptedEndpoint() { return encryptedEndpoint; }
    public void setEncryptedEndpoint(byte[] encryptedEndpoint) { this.encryptedEndpoint = encryptedEndpoint; }
    public String getEndpointFingerprint() { return endpointFingerprint; }
    public void setEndpointFingerprint(String endpointFingerprint) { this.endpointFingerprint = endpointFingerprint; }
    public String getActiveEndpointFingerprint() { return activeEndpointFingerprint; }
    public void setActiveEndpointFingerprint(String activeEndpointFingerprint) { this.activeEndpointFingerprint = activeEndpointFingerprint; }
    public byte[] getOwnershipSecretHash() { return ownershipSecretHash; }
    public void setOwnershipSecretHash(byte[] ownershipSecretHash) { this.ownershipSecretHash = ownershipSecretHash; }
    public long getBindingVersion() { return bindingVersion; }
    public void setBindingVersion(long bindingVersion) { this.bindingVersion = bindingVersion; }
    public long getEndpointVersion() { return endpointVersion; }
    public void setEndpointVersion(long endpointVersion) { this.endpointVersion = endpointVersion; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public PushPermission getReportedPermission() { return reportedPermission; }
    public void setReportedPermission(PushPermission reportedPermission) { this.reportedPermission = reportedPermission; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getActiveUntil() { return activeUntil; }
    public void setActiveUntil(Instant activeUntil) { this.activeUntil = activeUntil; }
}
