package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "workinterval", schema = "crm",
    uniqueConstraints = {@UniqueConstraint(name = "workintervalpk", columnNames = "id"),
        @UniqueConstraint(name = "uqworkinterval", columnNames = {"projectid", "masterid", "startlocal", "endlocal", "kind"})},
    indexes = @Index(name = "ixworkintervalscope", columnList = "projectid,masterid,startlocal"),
    check = @CheckConstraint(name = "workintervalck", constraint = "endlocal > startlocal and lastobservedgeneration >= 0"))
public class CrmWorkIntervalEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projectid", nullable = false, foreignKey = @ForeignKey(name = "fkworkintervalproject"))
    private CrmProjectEntity project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "masterid", nullable = false, foreignKey = @ForeignKey(name = "fkworkintervalmaster"))
    private CrmMasterEntity master;
    @Column(name = "startlocal", nullable = false) private LocalDateTime startLocal;
    @Column(name = "endlocal", nullable = false) private LocalDateTime endLocal;
    @Column(name = "kind", nullable = false, length = 24) private String kind;
    @Column(name = "externalref", length = 128) private String externalRef;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb") private Map<String, Object> attributes = Map.of();
    @Column(name = "lastobservedat", nullable = false) private Instant lastObservedAt;
    @Column(name = "lastobservedgeneration", nullable = false) private long lastObservedGeneration;
    public CrmProjectEntity getProject() { return project; }
    public void setProject(CrmProjectEntity value) { project = value; }
    public CrmMasterEntity getMaster() { return master; }
    public void setMaster(CrmMasterEntity value) { master = value; }
    public LocalDateTime getStartLocal() { return startLocal; }
    public void setStartLocal(LocalDateTime value) { startLocal = value; }
    public LocalDateTime getEndLocal() { return endLocal; }
    public void setEndLocal(LocalDateTime value) { endLocal = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String value) { externalRef = value; }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> value) { attributes = value; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant value) { lastObservedAt = value; }
    public long getLastObservedGeneration() { return lastObservedGeneration; }
    public void setLastObservedGeneration(long value) { lastObservedGeneration = value; }
}
