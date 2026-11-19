package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.server.persistence.entity.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name="client", schema="crm", uniqueConstraints={@UniqueConstraint(name="clientpk", columnNames="id"),
        @UniqueConstraint(name="uqclientexternal", columnNames={"projectid","identitykind","externalid"})},
        indexes={@Index(name="ixcrmclientlink", columnList="clientid"), @Index(name="ixcrmclientphone", columnList="projectid,normalizedphone"),
                @Index(name="ixcrmclientauthor", columnList="mappingupdatedby"),
                @Index(name="ixcrmclientstatistics", columnList="projectid,statisticsupdatedat,id")})
public class CrmClientEntity extends CrmRecord {
    @Column(name="statisticsretryat") private Instant statisticsRetryAt;
    public Instant getStatisticsRetryAt() { return statisticsRetryAt; }
    public void setStatisticsRetryAt(Instant value) { statisticsRetryAt=value; }
    @Column(name="statisticsupdatedat") private Instant statisticsUpdatedAt;
    public Instant getStatisticsUpdatedAt() { return statisticsUpdatedAt; }
    public void setStatisticsUpdatedAt(Instant value) { statisticsUpdatedAt=value; }
    @Column(name="visitcount") private Long visitCount;
    @Column(name="totalspent", precision=19, scale=2) private java.math.BigDecimal totalSpent;
    @Column(name="averagecheck", precision=19, scale=2) private java.math.BigDecimal averageCheck;
    @Column(name="statisticscurrency", length=3) private String statisticsCurrency;
    public java.math.BigDecimal getAverageCheck() { return averageCheck; }
    public void setAverageCheck(java.math.BigDecimal value) { averageCheck=value; }
    public String getStatisticsCurrency() { return statisticsCurrency; }
    public void setStatisticsCurrency(String value) { statisticsCurrency=value; }
    public Long getVisitCount() { return visitCount; }
    public void setVisitCount(Long value) { visitCount=value; }
    public java.math.BigDecimal getTotalSpent() { return totalSpent; }
    public void setTotalSpent(java.math.BigDecimal value) { totalSpent=value; }
    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="projectid", nullable=false, foreignKey=@ForeignKey(name="fkclientproject"))
    private CrmProjectEntity project;
    @Column(name="identitykind", nullable=false, length=32) private String identityKind;
    @Column(name="externalid", nullable=false, length=128) private String externalId;
    @Column(name="displayname", columnDefinition="text") private String displayName;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="contacts", nullable=false, columnDefinition="jsonb")
    private Map<String,String> contacts = Map.of();
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="profile", nullable=false, columnDefinition="jsonb")
    private Map<String,Object> profile = Map.of();
    @Column(name="normalizedphone", length=15) private String normalizedPhone;
    /** Зеркало аватара карточки: локальный ключ вложения; сырой url провайдера в хосте не хранится. */
    @Column(name="avatarurl", length=512) private String avatarUrl;
    /** Ключ файла провайдера (путь без расширения): по нему понять, тот ли это файл, не скачивая его. */
    @Column(name="avatarorigin", length=200) private String avatarOrigin;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="clientid", foreignKey=@ForeignKey(name="fkclientcore")) private ClientEntity client;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="mappingupdatedby", foreignKey=@ForeignKey(name="fkclientmappingauthor")) private EmployeeEntity mappingUpdatedBy;
    @Column(name="mappingupdatedat") private Instant mappingUpdatedAt;
    @Column(name="mappingpreviousid") private UUID mappingPreviousId;
    @Column(name="mappingreason", length=24) private String mappingReason;
    @Column(name="lastobservedat", nullable=false) private Instant lastObservedAt;
    public CrmProjectEntity getProject() { return project; }
    public void setProject(CrmProjectEntity value) { project=value; }
    public String getIdentityKind() { return identityKind; }
    public void setIdentityKind(String value) { identityKind=value; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String value) { externalId=value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName=value; }
    public Map<String,String> getContacts() { return contacts; }
    public void setContacts(Map<String,String> value) { contacts=value; }
    public Map<String,Object> getProfile() { return profile; }
    public void setProfile(Map<String,Object> value) { profile=value; }
    public String getNormalizedPhone() { return normalizedPhone; }
    public void setNormalizedPhone(String value) { normalizedPhone=value; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String value) { avatarUrl=value; }
    public String getAvatarOrigin() { return avatarOrigin; }
    public void setAvatarOrigin(String value) { avatarOrigin=value; }
    public ClientEntity getClient() { return client; }
    public void setClient(ClientEntity value) { client=value; }
    public EmployeeEntity getMappingUpdatedBy() { return mappingUpdatedBy; }
    public void setMappingUpdatedBy(EmployeeEntity value) { mappingUpdatedBy=value; }
    public Instant getMappingUpdatedAt() { return mappingUpdatedAt; }
    public void setMappingUpdatedAt(Instant value) { mappingUpdatedAt=value; }
    public UUID getMappingPreviousId() { return mappingPreviousId; }
    public void setMappingPreviousId(UUID value) { mappingPreviousId=value; }
    public String getMappingReason() { return mappingReason; }
    public void setMappingReason(String value) { mappingReason=value; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant value) { lastObservedAt=value; }
}
