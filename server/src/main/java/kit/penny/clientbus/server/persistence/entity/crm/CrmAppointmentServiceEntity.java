package kit.penny.clientbus.server.persistence.entity.crm;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
@Entity
@Table(name="appointmentservice",schema="crm",uniqueConstraints={@UniqueConstraint(name="appointmentservicepk",columnNames="id"),@UniqueConstraint(name="uqappointmentserviceposition",columnNames={"appointmentid","position"})},
        check=@CheckConstraint(name="appointmentserviceck",constraint="position>=0 and (durationseconds is null or durationseconds>=0) and (price is null or price>=0)"))
public class CrmAppointmentServiceEntity extends CrmRecord {
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="attributes",nullable=false,columnDefinition="jsonb") private Map<String,Object> attributes=Map.of();
    public Map<String,Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String,Object> value) { attributes=value; }
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="appointmentid",nullable=false,foreignKey=@ForeignKey(name="fkappointmentserviceappointment")) private CrmAppointmentEntity appointment;
    @Column(name="position",nullable=false) private int position;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="serviceid",foreignKey=@ForeignKey(name="fkappointmentserviceservice")) private CrmServiceEntity service;
    @Column(name="externalserviceid",length=128) private String externalServiceId;
    @Column(name="externallineid",length=128) private String externalLineId;
    @Column(name="displayname",columnDefinition="text") private String displayName;
    @Column(name="durationseconds") private Long durationSeconds;
    @Column(name="price",columnDefinition="numeric") private BigDecimal price;
    @Column(name="currency",length=3) private String currency;
    public CrmAppointmentEntity getAppointment() { return appointment; }
    public void setAppointment(CrmAppointmentEntity value) { appointment=value; }
    public int getPosition() { return position; }
    public void setPosition(int value) { position=value; }
    public CrmServiceEntity getService() { return service; }
    public void setService(CrmServiceEntity value) { service=value; }
    public String getExternalServiceId() { return externalServiceId; }
    public void setExternalServiceId(String value) { externalServiceId=value; }
    public String getExternalLineId() { return externalLineId; }
    public void setExternalLineId(String value) { externalLineId=value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName=value; }
    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long value) { durationSeconds=value; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal value) { price=value==null ? null : value.stripTrailingZeros(); }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency=value; }
}
