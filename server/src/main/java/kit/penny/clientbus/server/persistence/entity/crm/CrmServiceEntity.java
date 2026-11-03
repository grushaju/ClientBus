package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "service", schema = "crm", uniqueConstraints = {
        @UniqueConstraint(name = "servicepk", columnNames = "id"),
        @UniqueConstraint(name = "uqserviceexternal", columnNames = {"projectid", "externalid"})})
public class CrmServiceEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projectid", nullable = false, foreignKey = @ForeignKey(name = "fkserviceproject"))
    private CrmProjectEntity project;
    @Column(name = "externalid", nullable = false, length = 128)
    private String externalId;
    @Column(name = "displayname", nullable = false, columnDefinition = "text")
    private String displayName;
    @Column(name = "category", columnDefinition = "text")
    private String category;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = Map.of();
    @Column(name = "lastobservedat", nullable = false)
    private Instant lastObservedAt;
    @Column(name = "lastobservedgeneration", nullable = false)
    private long lastObservedGeneration;
    @Column(name = "observationstate", nullable = false, length = 24)
    private String observationState = "OBSERVED";
    public CrmProjectEntity getProject() { return project; }
    public void setProject(CrmProjectEntity value) { project = value; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String value) { externalId = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> value) { attributes = value; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant value) { lastObservedAt = value; }
    public long getLastObservedGeneration() { return lastObservedGeneration; }
    public void setLastObservedGeneration(long value) { lastObservedGeneration = value; }
    public String getObservationState() { return observationState; }
    public void setObservationState(String value) { observationState = value; }
}
