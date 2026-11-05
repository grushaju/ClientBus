package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.persistence.entity.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "connection", schema = "crm",
    uniqueConstraints = {@UniqueConstraint(name = "connectionpk", columnNames = "id"),
        @UniqueConstraint(name = "uqconnectionlogin", columnNames = {"organizationid", "provider", "login"})},
    indexes = @Index(name = "ixconnectionorganization", columnList = "organizationid, provider"),
    check = @CheckConstraint(name = "connectionck", constraint = "credentialversion > 0"))
public class CrmConnectionEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizationid", nullable = false, foreignKey = @ForeignKey(name = "fkconnectionorganization"))
    private OrganizationEntity organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 24)
    private CrmType provider;

    @Column(name = "login", nullable = false, length = 160)
    private String login;

    @Column(name = "displayname", length = 80)
    private String displayName;

    @Column(name = "secretciphertext", nullable = false, columnDefinition = "bytea")
    private byte[] secretCiphertext;

    @Column(name = "secretkeyid", nullable = false, length = 64)
    private String secretKeyId;

    @Column(name = "credentialversion", nullable = false)
    private long credentialVersion = 1;

    @Column(name = "healthgeneration", nullable = false, columnDefinition = "bigint default 0")
    @OptimisticLock(excluded = true) // Allocated under the row lock; a reservation is not a health result.
    private long healthGeneration;
    @Column(name = "healthappliedgeneration", nullable = false, columnDefinition = "bigint default 0")
    private long healthAppliedGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CrmConnectionStatus status;

    @Column(name = "lastcheckedat")
    private Instant lastCheckedAt;

    @Column(name = "lasterrorcode", length = 64)
    private String lastErrorCode;

    public OrganizationEntity getOrganization() { return organization; }
    public void setOrganization(OrganizationEntity organization) { this.organization = organization; }
    public CrmType getProvider() { return provider; }
    public void setProvider(CrmType provider) { this.provider = provider; }
    public String getLogin() { return login; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setLogin(String login) { this.login = login; }
    public byte[] getSecretCiphertext() { return secretCiphertext; }
    public void setSecretCiphertext(byte[] secretCiphertext) { this.secretCiphertext = secretCiphertext; }
    public String getSecretKeyId() { return secretKeyId; }
    public void setSecretKeyId(String secretKeyId) { this.secretKeyId = secretKeyId; }
    public long getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(long credentialVersion) { this.credentialVersion = credentialVersion; }
    public long getHealthGeneration() { return healthGeneration; }
    public void setHealthGeneration(long value) { healthGeneration=value; }
    public long getHealthAppliedGeneration() { return healthAppliedGeneration; }
    public void setHealthAppliedGeneration(long value) { healthAppliedGeneration=value; }
    public CrmConnectionStatus getStatus() { return status; }
    public void setStatus(CrmConnectionStatus status) { this.status = status; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
}
