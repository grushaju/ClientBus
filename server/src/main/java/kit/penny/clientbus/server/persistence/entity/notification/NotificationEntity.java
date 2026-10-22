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
        @UniqueConstraint(name = "notification_dedup_row_uq", columnNames = {"dedup_id"})
    },
    indexes = {
        @Index(name = "notification_recipient_page_idx", columnList = "organization_id, recipient_employee_id, created_at DESC, id DESC"),
        @Index(name = "notification_unread_idx", columnList = "organization_id, recipient_employee_id, read_at"),
        @Index(name = "notification_workspace_idx", columnList = "workspace_id"),
        @Index(name = "notification_retention_idx", columnList = "retain_until")
    },
    check = @CheckConstraint(name = "notification_ck", constraint =
            "(alert_invalidated_at is null or alert_invalidated_at >= created_at) and alert_expires_at > created_at and retain_until >= alert_expires_at and (read_at is null or read_at >= created_at) and jsonb_typeof(template_args) = 'object'"))
public class NotificationEntity extends NotificationRecord {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dedup_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "notification_dedup_id_fk"))
    private NotificationDedupEntity dedup;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "recipient_employee_id", nullable = false, updatable = false)
    private UUID recipientEmployeeId;

    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, updatable = false, length = 32)
    private NotificationTargetKind targetKind;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_args", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, String> templateArgs;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "alert_invalidated_at")
    private Instant alertInvalidatedAt;

    public Instant getAlertInvalidatedAt() { return alertInvalidatedAt; }

    /** Closing an alert is irreversible; center history and read state are preserved. */
    public void invalidateAlert(Instant at) {
        java.util.Objects.requireNonNull(at);
        if (alertInvalidatedAt == null) alertInvalidatedAt = at;
    }

    @Column(name = "alert_expires_at", nullable = false, updatable = false)
    private Instant alertExpiresAt;

    @Column(name = "retain_until", nullable = false)
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
