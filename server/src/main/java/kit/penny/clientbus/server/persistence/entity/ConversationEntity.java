package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "conversation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "conversation_channel_client_uix",
                        columnNames = {
                                "channelaccountid",
                                "clientaccountid"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "conversation_workspace_idx",
                        columnList = "workspaceid"
                ),
                @Index(
                        name = "conversation_client_account_idx",
                        columnList = "clientaccountid"
                ),
                @Index(
                        name = "conversation_channel_account_idx",
                        columnList = "channelaccountid"
                ),
                @Index(
                        name = "conversation_assigned_employee_idx",
                        columnList = "assignedemployeeid"
                ),
                @Index(
                        name = "conversation_last_message_at_idx",
                        columnList = "lastmessageat"
                )
        }
)
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Workspace, в рамках которого существует Conversation.
     *
     * Храним Workspace напрямую, несмотря на то,
     * что его можно получить через ChannelAccount -> Channel -> Workspace.
     *
     * Это упрощает ACL и запросы Inbox.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspaceid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "conversation_workspace_fk"
            )
    )
    private WorkspaceEntity workspace;

    /**
     * Аккаунт организации в конкретном мессенджере.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "channelaccountid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "conversation_channel_account_fk"
            )
    )
    private ChannelAccountEntity channelAccount;

    /**
     * Аккаунт клиента в том же мессенджере.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "clientaccountid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "conversation_client_account_fk"
            )
    )
    private ClientAccountEntity clientAccount;

    /**
     * Сотрудник, которому сейчас назначен диалог.
     *
     * NULL означает, что Conversation пока не назначен.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "assignedemployeeid",
            foreignKey = @ForeignKey(
                    name = "conversation_assigned_employee_fk"
            )
    )
    private EmployeeEntity assignedEmployee;

    /**
     * Время последнего сообщения в Conversation.
     *
     * Используется Inbox для сортировки.
     */
    @Column(name = "lastmessageat")
    private Instant lastMessageAt;

    /**
     * Короткое содержимое последнего сообщения.
     *
     * Используется для отображения списка Inbox
     * без загрузки последнего Message.
     */
    @Column(
            name = "lastmessagepreview",
            length = 500
    )
    private String lastMessagePreview;

    /**
     * Количество непрочитанных входящих сообщений.
     */
    @Column(
            name = "unreadcount",
            nullable = false
    )
    private int unreadCount = 0;

    @CreationTimestamp
    @Column(
            name = "createdat",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updatedat")
    private Instant updatedAt;

    public ConversationEntity() {
    }

    public ConversationEntity(
            WorkspaceEntity workspace,
            ChannelAccountEntity channelAccount,
            ClientAccountEntity clientAccount
    ) {
        this.workspace = workspace;
        this.channelAccount = channelAccount;
        this.clientAccount = clientAccount;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(
            WorkspaceEntity workspace
    ) {
        this.workspace = workspace;
    }

    public ChannelAccountEntity getChannelAccount() {
        return channelAccount;
    }

    public void setChannelAccount(
            ChannelAccountEntity channelAccount
    ) {
        this.channelAccount = channelAccount;
    }

    public ClientAccountEntity getClientAccount() {
        return clientAccount;
    }

    public void setClientAccount(
            ClientAccountEntity clientAccount
    ) {
        this.clientAccount = clientAccount;
    }

    public EmployeeEntity getAssignedEmployee() {
        return assignedEmployee;
    }

    public void setAssignedEmployee(
            EmployeeEntity assignedEmployee
    ) {
        this.assignedEmployee = assignedEmployee;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(
            Instant lastMessageAt
    ) {
        this.lastMessageAt = lastMessageAt;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public void setLastMessagePreview(
            String lastMessagePreview
    ) {
        this.lastMessagePreview = lastMessagePreview;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}