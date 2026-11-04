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
                @Index(name="ixcrmclientauthor", columnList="mappingupdatedby")})
public class CrmClientEntity extends CrmRecord {
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
