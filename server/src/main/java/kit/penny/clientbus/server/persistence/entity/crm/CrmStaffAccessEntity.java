package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

/**
 * Наблюдаемая запись доступа DIKIDI (страница permissions + форма edit/{id}, 13.6).
 * External id — id записи доступа: это НЕ user id платформы (авторы чата ключатся user id,
 * связи «user id ↔ запись доступа» в ответах DIKIDI не наблюдалось — CRM_DIKIDI_PROTOCOL).
 * masterexternalid доказан формой; учётку-оператора (crm.employee) подбирает человек —
 * employeeid/linkupdatedat/linkupdatedby. Строка наблюдения: при недоступной странице
 * правок (ограниченная учётка) не обновляется и не стареет; связь человека снимок не трогает.
 */
@Entity
@Table(name = "staffaccess", schema = "crm",
    uniqueConstraints = {@UniqueConstraint(name = "staffaccesspk", columnNames = "id"),
        @UniqueConstraint(name = "uqstaffaccessexternal", columnNames = {"projectid", "externalid"})},
    indexes = @Index(name = "ixstaffaccessmaster", columnList = "projectid,masterexternalid"))
public class CrmStaffAccessEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projectid", nullable = false, foreignKey = @ForeignKey(name = "fkstaffaccessproject"))
    private CrmProjectEntity project;

    @Column(name = "externalid", nullable = false, length = 128)
    private String externalId;

    @Column(name = "displayname", nullable = false, columnDefinition = "text")
    private String displayName;

    @Column(name = "masterexternalid", length = 128)
    private String masterExternalId;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "ismainowner", nullable = false)
    private boolean mainOwner;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> profile = Map.of();

    @Column(name = "lastobservedat", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "lastobservedgeneration")
    private Long lastObservedGeneration;

    @Column(name = "observationstate", length = 24)
    private String observationState;

    /** Оператор — учётка проекта (crm.employee), выбранная человеком; моста в DIKIDI нет. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employeeid", foreignKey = @ForeignKey(name = "fkstaffaccessoperator"))
    private CrmEmployeeEntity employee;

    @Column(name = "linkupdatedat")
    private Instant linkUpdatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linkupdatedby", foreignKey = @ForeignKey(name = "fkstaffaccesslinkauthor"))
    private EmployeeEntity linkUpdatedBy;

    public CrmProjectEntity getProject() { return project; }
    public void setProject(CrmProjectEntity value) { project = value; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String value) { externalId = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getMasterExternalId() { return masterExternalId; }
    public void setMasterExternalId(String value) { masterExternalId = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public boolean isMainOwner() { return mainOwner; }
    public void setMainOwner(boolean value) { mainOwner = value; }
    public Map<String, Object> getProfile() { return profile; }
    public void setProfile(Map<String, Object> value) { profile = value; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant value) { lastObservedAt = value; }
    public Long getLastObservedGeneration() { return lastObservedGeneration; }
    public void setLastObservedGeneration(Long value) { lastObservedGeneration = value; }
    public String getObservationState() { return observationState; }
    public void setObservationState(String value) { observationState = value; }
    public CrmEmployeeEntity getEmployee() { return employee; }
    public void setEmployee(CrmEmployeeEntity value) { employee = value; }
    public Instant getLinkUpdatedAt() { return linkUpdatedAt; }
    public void setLinkUpdatedAt(Instant value) { linkUpdatedAt = value; }
    public EmployeeEntity getLinkUpdatedBy() { return linkUpdatedBy; }
    public void setLinkUpdatedBy(EmployeeEntity value) { linkUpdatedBy = value; }
}
