package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "masterservice", schema = "crm", uniqueConstraints = {
        @UniqueConstraint(name = "masterservicepk", columnNames = "id"),
        @UniqueConstraint(name = "uqmasterservicepair", columnNames = {"projectid", "masterid", "serviceid"})},
        indexes = @Index(name = "ixmasterserviceservice", columnList = "serviceid"))
public class CrmMasterServiceEntity extends CrmRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "projectid", nullable = false, foreignKey = @ForeignKey(name = "fkmasterserviceproject"))
    private CrmProjectEntity project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "masterid", nullable = false, foreignKey = @ForeignKey(name = "fkmasterservicemaster"))
    private CrmMasterEntity master;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "serviceid", nullable = false, foreignKey = @ForeignKey(name = "fkmasterserviceservice"))
    private CrmServiceEntity service;
    @Column(name = "price", precision = 19, scale = 4)
    private BigDecimal price;
    @Column(name = "currency", length = 3)
    private String currency;
    @Column(name = "durationseconds")
    private Long durationSeconds;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "offerstate", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> offerState = Map.of();
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
    public CrmMasterEntity getMaster() { return master; }
    public void setMaster(CrmMasterEntity value) { master = value; }
    public CrmServiceEntity getService() { return service; }
    public void setService(CrmServiceEntity value) { service = value; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal value) { price = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long value) { durationSeconds = value; }
    public Map<String, Object> getOfferState() { return offerState; }
    public void setOfferState(Map<String, Object> value) { offerState = value; }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> value) { attributes = value; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant value) { lastObservedAt = value; }
    public long getLastObservedGeneration() { return lastObservedGeneration; }
    public void setLastObservedGeneration(long value) { lastObservedGeneration = value; }
    public String getObservationState() { return observationState; }
    public void setObservationState(String value) { observationState = value; }
}
