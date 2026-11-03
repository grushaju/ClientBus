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
@Table(name = "master", schema = "crm",
    uniqueConstraints = {@UniqueConstraint(name = "masterpk", columnNames = "id"),
        @UniqueConstraint(name = "uqmasterexternal", columnNames = {"projectid", "externalid"})},
    indexes = @Index(name = "ixmasteremployee", columnList = "employeeid"))
public class CrmMasterEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projectid", nullable = false, foreignKey = @ForeignKey(name = "fkmasterproject"))
    private CrmProjectEntity project;

    @Column(name = "externalid", nullable = false, length = 128)
    private String externalId;

    @Column(name = "displayname", nullable = false, columnDefinition = "text")
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> profile = Map.of();

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "employeeid", nullable = true, foreignKey = @ForeignKey(name = "fkmasteremployee"))
    private EmployeeEntity employee;

    @Column(name = "mappingupdatedat")
    private Instant mappingUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "mappingupdatedby", nullable = true, foreignKey = @ForeignKey(name = "fkmastermappingauthor"))
    private EmployeeEntity mappingUpdatedBy;

    @Column(name = "mappingpreviousid")
    private UUID mappingPreviousId;

    @Column(name = "mappingreason", length = 24)
    private String mappingReason;

    @Column(name = "lastobservedat", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "lastobservedgeneration")
    private Long lastObservedGeneration;
    @Column(name = "observationstate", length = 24)
    private String observationState;

    public Long getLastObservedGeneration() { return lastObservedGeneration; }
    public void setLastObservedGeneration(Long value) { lastObservedGeneration = value; }
    public String getObservationState() { return observationState; }
    public void setObservationState(String value) { observationState = value; }

    public CrmProjectEntity getProject() { return project; }
    public void setProject(CrmProjectEntity project) { this.project = project; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Map<String, Object> getProfile() { return profile; }
    public void setProfile(Map<String, Object> profile) { this.profile = profile; }
    public EmployeeEntity getEmployee() { return employee; }
    public void setEmployee(EmployeeEntity employee) { this.employee = employee; }
    public Instant getMappingUpdatedAt() { return mappingUpdatedAt; }
    public void setMappingUpdatedAt(Instant mappingUpdatedAt) { this.mappingUpdatedAt = mappingUpdatedAt; }
    public EmployeeEntity getMappingUpdatedBy() { return mappingUpdatedBy; }
    public void setMappingUpdatedBy(EmployeeEntity mappingUpdatedBy) { this.mappingUpdatedBy = mappingUpdatedBy; }
    public UUID getMappingPreviousId() { return mappingPreviousId; }
    public void setMappingPreviousId(UUID mappingPreviousId) { this.mappingPreviousId = mappingPreviousId; }
    public String getMappingReason() { return mappingReason; }
    public void setMappingReason(String mappingReason) { this.mappingReason = mappingReason; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant lastObservedAt) { this.lastObservedAt = lastObservedAt; }
}
