package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import kit.penny.clientbus.common.notification.*;
import kit.penny.clientbus.server.notification.contract.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;

@Entity
@Table(name = "notification", schema = "dbo",
    uniqueConstraints = {
        @UniqueConstraint(name = "notificationpk", columnNames = {"id"}),
        @UniqueConstraint(name = "notificationdeduprowuq", columnNames = {"dedupid"})
    },
    indexes = {
        @Index(name = "notificationrecipientpageidx", columnList = "organizationid, recipientemployeeid, createdat DESC, id DESC"),
        @Index(name = "notificationunreadidx", columnList = "organizationid, recipientemployeeid, readat"),
        @Index(name = "notificationworkspaceidx", columnList = "workspaceid"),
        @Index(name = "notificationretentionidx", columnList = "retainuntil")
    },
    check = @CheckConstraint(name = "notificationck", constraint =
            "(alertinvalidatedat is null or alertinvalidatedat >= createdat) and alertexpiresat > createdat and retainuntil >= alertexpiresat and (readat is null or readat >= createdat) and jsonb_typeof(templateargs) = 'object'"))
public class NotificationEntity extends NotificationRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dedupid", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notificationdedupidfk"))
    private NotificationDedupEntity dedup;

    @Column(name = "organizationid", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "recipientemployeeid", nullable = false, updatable = false)
    private UUID recipientEmployeeId;

    @Column(name = "workspaceid", updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "targetkind", nullable = false, updatable = false, length = 32)
    private NotificationTargetKind targetKind;

    @Column(name = "targetid", nullable = false, updatable = false)
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "templateargs", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, String> templateArgs;

    @Column(name = "readat")
    private Instant readAt;

    @Column(name = "alertinvalidatedat")
    private Instant alertInvalidatedAt;

    public Instant getAlertInvalidatedAt() { return alertInvalidatedAt; }

    /** Closing an alert is irreversible; center history and read state are preserved. */
    public void invalidateAlert(Instant at) {
        java.util.Objects.requireNonNull(at);
        if (alertInvalidatedAt == null) alertInvalidatedAt = at;
    }

    @Column(name = "alertexpiresat", nullable = false, updatable = false)
    private Instant alertExpiresAt;

    @Column(name = "retainuntil", nullable = false)
    private Instant retainUntil;

    public NotificationEntity() {}

    public NotificationDedupEntity getDedup() { return dedup; }
    public void setDedup(NotificationDedupEntity dedup) { this.dedup = dedup; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public UUID getRecipientEmployeeId() { return recipientEmployeeId; }
    public void setRecipientEmployeeId(UUID recipientEmployeeId) { this.recipientEmployeeId = recipientEmployeeId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public NotificationTargetKind getTargetKind() { return targetKind; }
    public void setTargetKind(NotificationTargetKind targetKind) { this.targetKind = targetKind; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public Map<String, String> getTemplateArgs() { return templateArgs; }
    public void setTemplateArgs(Map<String, String> templateArgs) { this.templateArgs = templateArgs; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public Instant getAlertExpiresAt() { return alertExpiresAt; }
    public void setAlertExpiresAt(Instant alertExpiresAt) { this.alertExpiresAt = alertExpiresAt; }
    public Instant getRetainUntil() { return retainUntil; }
    public void setRetainUntil(Instant retainUntil) { this.retainUntil = retainUntil; }
}
