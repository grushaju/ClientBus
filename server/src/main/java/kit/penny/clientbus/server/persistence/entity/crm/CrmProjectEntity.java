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
@Table(name = "project", schema = "crm",
    uniqueConstraints = {@UniqueConstraint(name = "projectpk", columnNames = "id"),
        @UniqueConstraint(name = "uqprojectidentity", columnNames = {"organizationid", "provider", "externalid"})},
    indexes = @Index(name = "ixprojectworkspace", columnList = "workspaceid"),
    check = @CheckConstraint(name = "projectck", constraint = "contextversion > 0 and zonerevision > 0"))
public class CrmProjectEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizationid", nullable = false, foreignKey = @ForeignKey(name = "fkprojectorganization"))
    private OrganizationEntity organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connectionid", nullable = false, foreignKey = @ForeignKey(name = "fkprojectconnection"))
    private CrmConnectionEntity connection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspaceid", nullable = false, foreignKey = @ForeignKey(name = "fkprojectworkspace"))
    private WorkspaceEntity workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 24)
    private CrmType provider;

    @Column(name = "externalid", nullable = false, length = 128)
    private String externalId;

    @Column(name = "displayname", nullable = false, columnDefinition = "text")
    private String displayName;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "zoneid", length = 128)
    private String zoneId;

    @Column(name = "zonesource", length = 24)
    private String zoneSource;

    @Column(name = "zonerevision", nullable = false)
    private long zoneRevision = 1;

    @Column(name = "contextversion", nullable = false)
    private long contextVersion = 1;

    // Null on pre-existing rows: retain their current conservative context boundary.
    @Column(name = "historycontextversion")
    private Long historyContextVersion = 1L;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    // Keep the original workspace and connection for archived history and its access checks.
    @Column(name = "archived", nullable = false, columnDefinition = "boolean default false")
    private boolean archived;

    public OrganizationEntity getOrganization() { return organization; }
    public void setOrganization(OrganizationEntity organization) { this.organization = organization; }
    public CrmConnectionEntity getConnection() { return connection; }
    public void setConnection(CrmConnectionEntity connection) { this.connection = connection; }
    public WorkspaceEntity getWorkspace() { return workspace; }
    public void setWorkspace(WorkspaceEntity workspace) { this.workspace = workspace; }
    public CrmType getProvider() { return provider; }
    public void setProvider(CrmType provider) { this.provider = provider; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
    public String getZoneSource() { return zoneSource; }
    public void setZoneSource(String zoneSource) { this.zoneSource = zoneSource; }
    public long getZoneRevision() { return zoneRevision; }
    public void setZoneRevision(long zoneRevision) { this.zoneRevision = zoneRevision; }
    public long getContextVersion() { return contextVersion; }
    public void setContextVersion(long contextVersion) {
        if (historyContextVersion == null) historyContextVersion = this.contextVersion;
        this.contextVersion = contextVersion;
    }
    public long getHistoryContextVersion() { return historyContextVersion == null ? contextVersion : historyContextVersion; }
    public void resetHistoryContext() { historyContextVersion = contextVersion; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
}
