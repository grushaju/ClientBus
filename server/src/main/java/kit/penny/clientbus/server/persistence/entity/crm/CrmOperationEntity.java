package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Durable journal. SQL transitions are conditional; business projections are owned by their readers. */
@Entity
@Table(name="operation",schema="crm",uniqueConstraints={@UniqueConstraint(name="operationpk",columnNames="id"),@UniqueConstraint(name="uqoperationkey",
        columnNames={"projectid","initiatoremployeeid","idempotencykey"})},indexes={
        @Index(name="ixoperationdue",columnList="status,createdat"),
        @Index(name="ixoperationowner",columnList="projectid,initiatoremployeeid,status")},
        check=@CheckConstraint(name="operationstatusck",constraint="status in ('REQUESTED','IN_FLIGHT','CONFIRMED','REJECTED','UNKNOWN')"))
public class CrmOperationEntity extends CrmRecord {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="projectid",nullable=false,foreignKey=@ForeignKey(name="fkoperationproject"))
    private CrmProjectEntity project;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="initiatoremployeeid",nullable=false,foreignKey=@ForeignKey(name="fkoperationauthor"))
    private EmployeeEntity initiatorEmployee;
    @Column(name="idempotencykey",nullable=false) private UUID idempotencyKey;
    @Column(name="requesthash",nullable=false,length=64) private String requestHash;
    @Column(name="type",nullable=false,length=16) private String type;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="targetappointmentid",foreignKey=@ForeignKey(name="fkoperationappointment"))
    private CrmAppointmentEntity appointment;
    @Column(name="expectedversion") private Long expectedVersion;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="connectionid",nullable=false,foreignKey=@ForeignKey(name="fkoperationconnection"))
    private CrmConnectionEntity connection;
    @Column(name="credentialversion",nullable=false) private long credentialVersion;
    @Column(name="contextversion",nullable=false) private long contextVersion;
    @Column(name="zonerevision",nullable=false) private long zoneRevision;
    @Column(name="externalprojectid",nullable=false,length=128) private String externalProjectId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="requestbody",nullable=false,columnDefinition="jsonb") private String requestBody;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="payload",nullable=false,columnDefinition="jsonb") private String payload;
    @Column(name="status",nullable=false,length=16) private String status;
    @Column(name="effectstartedat") private Instant effectStartedAt;
    @Column(name="deadlineat") private Instant deadlineAt;
    @Column(name="reconcileuntil") private Instant reconcileUntil;
    @Column(name="refreshprocessedat") private Instant refreshProcessedAt;
    @Column(name="externalreference",length=128) private String externalReference;
    @Column(name="errorcode",length=80) private String errorCode;
}
