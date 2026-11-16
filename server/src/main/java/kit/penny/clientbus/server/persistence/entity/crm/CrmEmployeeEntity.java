package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.persistence.entity.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Сотрудник CRM-проекта (решение пользователя 16.11): одна таблица для всех сущностей доступа
 * проекта — мастер (исключительно оказание услуг из перечня и график) и не-мастер (ресепшн,
 * админ, стажёр — только ведение записи). Различие — <code>ismaster</code>; привязка сотрудника
 * ClientBus (employeeid) не зависит от роли. Пространство externalid — платформенные id
 * пользователей DIKIDI (masters.info и доступы проекта отдают строчки под user id авторов).
 */
@Entity
@Table(name = "employee", schema = "crm",
    uniqueConstraints = {@UniqueConstraint(name = "employeepk", columnNames = "id"),
        @UniqueConstraint(name = "uqemployeeexternal", columnNames = {"projectid", "externalid"})},
    indexes = @Index(name = "ixemployeeemployee", columnList = "employeeid"))
public class CrmEmployeeEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projectid", nullable = false, foreignKey = @ForeignKey(name = "fkemployeeproject"))
    private CrmProjectEntity project;

    @Column(name = "externalid", nullable = false, length = 128)
    private String externalId;

    @Column(name = "displayname", nullable = false, columnDefinition = "text")
    private String displayName;

    @Column(name = "ismaster", nullable = false)
    private boolean isMaster;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> profile = Map.of();

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "employeeid", nullable = true, foreignKey = @ForeignKey(name = "fkemployeeemployee"))
    private EmployeeEntity employee;

    /**
     * Связь «одна строка наблюдения — один человек» (13.6): строка учётки доступа
     * (ismaster=false) ссылается на строку сотрудника каталога того же проекта. Мост
     * «user id учётки ↔ id записи доступа» в ответах DIKIDI не наблюдается, поэтому
     * связь подтверждает человек; атрибуция наследует маппинг цели (CRM_SEMANTICS §6).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "linkedemployeeid", nullable = true, foreignKey = @ForeignKey(name = "fkemployeelinked"))
    private CrmEmployeeEntity linkedEmployee;

    @Column(name = "linkupdatedat")
    private Instant linkUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "linkupdatedby", nullable = true, foreignKey = @ForeignKey(name = "fkemployeelinkauthor"))
    private EmployeeEntity linkUpdatedBy;

    @Column(name = "mappingupdatedat")
    private Instant mappingUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "mappingupdatedby", nullable = true, foreignKey = @ForeignKey(name = "fkemployeemappingauthor"))
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
    public boolean isMaster() { return isMaster; }
    public void setMaster(boolean value) { isMaster = value; }
    public Map<String, Object> getProfile() { return profile; }
    public void setProfile(Map<String, Object> profile) { this.profile = profile; }
    public EmployeeEntity getEmployee() { return employee; }
    public void setEmployee(EmployeeEntity employee) { this.employee = employee; }
    public CrmEmployeeEntity getLinkedEmployee() { return linkedEmployee; }
    public void setLinkedEmployee(CrmEmployeeEntity value) { linkedEmployee = value; }
    public Instant getLinkUpdatedAt() { return linkUpdatedAt; }
    public void setLinkUpdatedAt(Instant value) { linkUpdatedAt = value; }
    public EmployeeEntity getLinkUpdatedBy() { return linkUpdatedBy; }
    public void setLinkUpdatedBy(EmployeeEntity value) { linkUpdatedBy = value; }
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
