package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.persistence.entity.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "syncstate", schema = "crm",
    uniqueConstraints = {@UniqueConstraint(name = "syncstatepk", columnNames = "id"),
        @UniqueConstraint(name = "uqsyncstatescope", columnNames = {"projectid", "dataset", "scopekey"})},
    indexes = @Index(name = "ixsyncstateclaim", columnList = "claimuntil"),
    check = @CheckConstraint(name = "syncstateck", constraint = "generation >= 0 and credentialversion > 0 and contextversion > 0 and zonerevision > 0"))
public class CrmSyncStateEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projectid", nullable = false, foreignKey = @ForeignKey(name = "fksyncstateproject"))
    private CrmProjectEntity project;

    @Column(name = "statekind", nullable = false, length = 16)
    private String stateKind;

    @Column(name = "writedomain", nullable = false, length = 32)
    private String writeDomain;

    @Column(name = "dataset", nullable = false, length = 64)
    private String dataset;

    @Column(name = "scopekey", nullable = false, length = 160)
    private String scopeKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scope = Map.of();

    @Column(name = "generation", nullable = false)
    private long generation;

    @Column(name = "claimtoken")
    private UUID claimToken;

    @Column(name = "claimuntil")
    private Instant claimUntil;

    @Column(name = "connectionid", nullable = false)
    private UUID connectionId;

    @Column(name = "credentialversion", nullable = false)
    private long credentialVersion;

    @Column(name = "contextversion", nullable = false)
    private long contextVersion;

    @Column(name = "zonerevision", nullable = false)
    private long zoneRevision;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "lastattemptat")
    private Instant lastAttemptAt;

    @Column(name = "lastsuccessat")
    private Instant lastSuccessAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "successfulcoverage", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> successfulCoverage = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attemptcoverage", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attemptCoverage = Map.of();

    @Column(name = "cursor", length = 512)
    private String cursor;

    @Column(name = "lasterrorcode", length = 64)
    private String lastErrorCode;

    public CrmProjectEntity getProject() { return project; }
    public void setProject(CrmProjectEntity project) { this.project = project; }
    public String getStateKind() { return stateKind; }
    public void setStateKind(String stateKind) { this.stateKind = stateKind; }
    public String getWriteDomain() { return writeDomain; }
    public void setWriteDomain(String writeDomain) { this.writeDomain = writeDomain; }
    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public Map<String, Object> getScope() { return scope; }
    public void setScope(Map<String, Object> scope) { this.scope = scope; }
    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }
    public UUID getClaimToken() { return claimToken; }
    public void setClaimToken(UUID claimToken) { this.claimToken = claimToken; }
    public Instant getClaimUntil() { return claimUntil; }
    public void setClaimUntil(Instant claimUntil) { this.claimUntil = claimUntil; }
    public UUID getConnectionId() { return connectionId; }
    public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }
    public long getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(long credentialVersion) { this.credentialVersion = credentialVersion; }
    public long getContextVersion() { return contextVersion; }
    public void setContextVersion(long contextVersion) { this.contextVersion = contextVersion; }
    public long getZoneRevision() { return zoneRevision; }
    public void setZoneRevision(long zoneRevision) { this.zoneRevision = zoneRevision; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public Map<String, Object> getSuccessfulCoverage() { return successfulCoverage; }
    public void setSuccessfulCoverage(Map<String, Object> successfulCoverage) { this.successfulCoverage = successfulCoverage; }
    public Map<String, Object> getAttemptCoverage() { return attemptCoverage; }
    public void setAttemptCoverage(Map<String, Object> attemptCoverage) { this.attemptCoverage = attemptCoverage; }
    public String getCursor() { return cursor; }
    public void setCursor(String cursor) { this.cursor = cursor; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
}

