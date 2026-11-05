package kit.penny.clientbus.server.persistence.entity.crm;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
@Entity
@Table(name="appointmentmaster",schema="crm",uniqueConstraints={@UniqueConstraint(name="appointmentmasterpk",columnNames="id"),@UniqueConstraint(name="uqappointmentmasterposition",columnNames={"appointmentid","position"})},
        check=@CheckConstraint(name="appointmentmasterck",constraint="position>=0 and (durationseconds is null or durationseconds>=0) and (price is null or price>=0) and (startlocal is null or endlocal is null or endlocal>startlocal)"))
public class CrmAppointmentMasterEntity extends CrmRecord {
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="attributes",nullable=false,columnDefinition="jsonb") private Map<String,Object> attributes=Map.of();
    public Map<String,Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String,Object> value) { attributes=value; }
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="appointmentid",nullable=false,foreignKey=@ForeignKey(name="fkappointmentmasterappointment")) private CrmAppointmentEntity appointment;
    @Column(name="position",nullable=false) private int position;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="masterid",foreignKey=@ForeignKey(name="fkappointmentmastermaster")) private CrmMasterEntity master;
    @Column(name="externalmasterid",nullable=false,length=128) private String externalMasterId;
    @Column(name="displayname",columnDefinition="text") private String displayName;
    @Column(name="startlocal") private LocalDateTime startLocal;
    @Column(name="endlocal") private LocalDateTime endLocal;
    @Column(name="durationseconds") private Long durationSeconds;
    @Column(name="price",columnDefinition="numeric") private BigDecimal price;
    @Column(name="currency",length=3) private String currency;
    public CrmAppointmentEntity getAppointment() { return appointment; }
    public void setAppointment(CrmAppointmentEntity value) { appointment=value; }
    public int getPosition() { return position; }
    public void setPosition(int value) { position=value; }
    public CrmMasterEntity getMaster() { return master; }
    public void setMaster(CrmMasterEntity value) { master=value; }
    public String getExternalMasterId() { return externalMasterId; }
    public void setExternalMasterId(String value) { externalMasterId=value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName=value; }
    public LocalDateTime getStartLocal() { return startLocal; }
    public void setStartLocal(LocalDateTime value) { startLocal=value; }
    public LocalDateTime getEndLocal() { return endLocal; }
    public void setEndLocal(LocalDateTime value) { endLocal=value; }
    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long value) { durationSeconds=value; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal value) { price=value==null ? null : value.stripTrailingZeros(); }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency=value; }
}
