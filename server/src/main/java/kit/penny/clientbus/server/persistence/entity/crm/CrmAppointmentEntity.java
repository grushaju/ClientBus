package kit.penny.clientbus.server.persistence.entity.crm;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
@Entity
@Table(name="appointment",schema="crm",uniqueConstraints={@UniqueConstraint(name="appointmentpk",columnNames="id"),@UniqueConstraint(name="uqappointmentexternal",columnNames={"projectid","externalid"})},indexes={@Index(name="ixappointmentperiod",columnList="projectid,startlocal"),@Index(name="ixappointmentclient",columnList="crmclientid,startlocal")},
        check=@CheckConstraint(name="appointmentck",constraint="(endlocal is null or endlocal>startlocal) and lastobservedgeneration>=0 and financialgeneration>=0"))
public class CrmAppointmentEntity extends CrmRecord {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="projectid",nullable=false,foreignKey=@ForeignKey(name="fkappointmentproject")) private CrmProjectEntity project;
    @Column(name="externalid",nullable=false,length=128) private String externalId;
    @Column(name="kind",nullable=false,length=24) private String kind;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="crmclientid",foreignKey=@ForeignKey(name="fkappointmentcrmclient")) private CrmClientEntity crmClient;
    @Column(name="externalclientid",length=128) private String externalClientId;
    @Column(name="clientidentitykind",length=24) private String clientIdentityKind;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="clientsnapshot",nullable=false,columnDefinition="jsonb") private Map<String,Object> clientSnapshot = Map.of();
    @Column(name="startlocal",nullable=false) private LocalDateTime startLocal;
    @Column(name="endlocal") private LocalDateTime endLocal;
    @Column(name="zoneid",length=128) private String zoneId;
    @Column(name="derivedzonerevision") private Long derivedZoneRevision;
    @Column(name="startat") private Instant startAt;
    @Column(name="endat") private Instant endAt;
    @Column(name="appointmentstate",nullable=false,length=24) private String appointmentState = "UNKNOWN";
    @Column(name="confirmationstate",nullable=false,length=24) private String confirmationState = "UNKNOWN";
    @Column(name="attendancestate",nullable=false,length=24) private String attendanceState = "UNKNOWN";
    @Column(name="paymentstate",nullable=false,length=24) private String paymentState = "UNKNOWN";
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="providerstate",nullable=false,columnDefinition="jsonb") private Map<String,String> providerState = Map.of();
    @Column(name="comment",columnDefinition="text") private String comment;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="sourceattributes",nullable=false,columnDefinition="jsonb") private Map<String,Object> sourceAttributes = Map.of();
    @Column(name="observationstate",nullable=false,length=24) private String observationState = "UNKNOWN";
    @Column(name="lastobservedat",nullable=false) private Instant lastObservedAt;
    @Column(name="lastobservedgeneration",nullable=false) private long lastObservedGeneration;
    @Column(name="observationcheckedat") private Instant observationCheckedAt;
    @Column(name="missinggeneration") private Long missingGeneration;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="financialsnapshot",nullable=false,columnDefinition="jsonb") private Map<String,Object> financialSnapshot = Map.of();
    @Column(name="financialobservedat") private Instant financialObservedAt;
    @Column(name="financialgeneration",nullable=false) private long financialGeneration;
    @Column(name="financialcompleteness",nullable=false,length=24) private String financialCompleteness = "UNKNOWN";
    public CrmProjectEntity getProject() { return project; }
    public void setProject(CrmProjectEntity value) { project=value; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String value) { externalId=value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind=value; }
    public CrmClientEntity getCrmClient() { return crmClient; }
    public void setCrmClient(CrmClientEntity value) { crmClient=value; }
    public String getExternalClientId() { return externalClientId; }
    public void setExternalClientId(String value) { externalClientId=value; }
    public String getClientIdentityKind() { return clientIdentityKind; }
    public void setClientIdentityKind(String value) { clientIdentityKind=value; }
    public Map<String,Object> getClientSnapshot() { return clientSnapshot; }
    public void setClientSnapshot(Map<String,Object> value) { clientSnapshot=value; }
    public LocalDateTime getStartLocal() { return startLocal; }
    public void setStartLocal(LocalDateTime value) { startLocal=value; }
    public LocalDateTime getEndLocal() { return endLocal; }
    public void setEndLocal(LocalDateTime value) { endLocal=value; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String value) { zoneId=value; }
    public Long getDerivedZoneRevision() { return derivedZoneRevision; }
    public void setDerivedZoneRevision(Long value) { derivedZoneRevision=value; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant value) { startAt=value; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant value) { endAt=value; }
    public String getAppointmentState() { return appointmentState; }
    public void setAppointmentState(String value) { appointmentState=value; }
    public String getConfirmationState() { return confirmationState; }
    public void setConfirmationState(String value) { confirmationState=value; }
    public String getAttendanceState() { return attendanceState; }
    public void setAttendanceState(String value) { attendanceState=value; }
    public String getPaymentState() { return paymentState; }
    public void setPaymentState(String value) { paymentState=value; }
    public Map<String,String> getProviderState() { return providerState; }
    public void setProviderState(Map<String,String> value) { providerState=value; }
    public String getComment() { return comment; }
    public void setComment(String value) { comment=value; }
    public Map<String,Object> getSourceAttributes() { return sourceAttributes; }
    public void setSourceAttributes(Map<String,Object> value) { sourceAttributes=value; }
    public String getObservationState() { return observationState; }
    public void setObservationState(String value) { observationState=value; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant value) { lastObservedAt=value; }
    public long getLastObservedGeneration() { return lastObservedGeneration; }
    public void setLastObservedGeneration(long value) { lastObservedGeneration=value; }
    public Instant getObservationCheckedAt() { return observationCheckedAt; }
    public void setObservationCheckedAt(Instant value) { observationCheckedAt=value; }
    public Long getMissingGeneration() { return missingGeneration; }
    public void setMissingGeneration(Long value) { missingGeneration=value; }
    public Map<String,Object> getFinancialSnapshot() { return financialSnapshot; }
    public void setFinancialSnapshot(Map<String,Object> value) { financialSnapshot=value; }
    public Instant getFinancialObservedAt() { return financialObservedAt; }
    public void setFinancialObservedAt(Instant value) { financialObservedAt=value; }
    public long getFinancialGeneration() { return financialGeneration; }
    public void setFinancialGeneration(long value) { financialGeneration=value; }
    public String getFinancialCompleteness() { return financialCompleteness; }
    public void setFinancialCompleteness(String value) { financialCompleteness=value; }
}
