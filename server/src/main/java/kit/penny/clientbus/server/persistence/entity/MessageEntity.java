package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.common.enums.MessageType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "message",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "message_conversation_external_id_uix",
                        columnNames = {
                                "conversationid",
                                "externalid"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "message_conversation_sent_at_idx",
                        columnList = "conversationid,sentat"
                ),
                @Index(
                        name = "message_conversation_created_at_idx",
                        columnList = "conversationid,createdat"
                )
        }
)
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            nullable = false,
            updatable = false
    )
    private UUID id;

    /**
     * Conversation, к которому относится сообщение.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "conversationid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "message_conversation_fk"
            )
    )
    private ConversationEntity conversation;

    /**
     * Тип сообщения.
     *
     * Например:
     * TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT,
     * STICKER, LOCATION, CONTACT, SYSTEM.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "type",
            nullable = false,
            length = 20
    )
    private MessageType type;

    /**
     * Направление сообщения относительно ClientBus.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "direction",
            nullable = false,
            length = 20
    )
    private MessageDirection direction;

    /**
     * Тип отправителя.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "sendertype",
            nullable = false,
            length = 20
    )
    private MessageSenderType senderType;

    /**
     * ClientAccount отправителя.
     *
     * Заполняется для senderType = CLIENT.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "clientaccountid",
            foreignKey = @ForeignKey(
                    name = "message_client_account_fk"
            )
    )
    private ClientAccountEntity clientAccount;

    /**
     * Employee отправителя.
     *
     * Заполняется для senderType = EMPLOYEE.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "employeeid",
            foreignKey = @ForeignKey(
                    name = "message_employee_fk"
            )
    )
    private EmployeeEntity employee;

    /**
     * ID сообщения во внешней платформе.
     *
     * Например:
     * Telegram message_id,
     * VK message ID и т.д.
     *
     * Для SYSTEM-сообщений может быть null.
     */
    @Column(
            name = "externalid",
            length = 255
    )
    private String externalId;

    /**
     * Текстовое содержимое сообщения.
     *
     * Для TEXT — основной текст.
     *
     * Для IMAGE / VIDEO / DOCUMENT и т.д.
     * может содержать caption.
     *
     * Для сообщений без текстового содержимого — null.
     */
    @Column(
            name = "content",
            columnDefinition = "TEXT"
    )
    private String content;

    /**
     * Дополнительные структурированные данные сообщения.
     *
     * Используется для типов, у которых недостаточно
     * обычного текстового content.
     *
     * Например LOCATION:
     *
     * {
     *   "latitude": 59.9343,
     *   "longitude": 30.3351,
     *   "address": "..."
     * }
     *
     * Или CONTACT:
     *
     * {
     *   "name": "Иван",
     *   "phone": "+7999...",
     *   "externalId": "..."
     * }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb"
    )
    private String metadata;

    /**
     * Время создания сообщения на стороне
     * внешней платформы.
     */
    @Column(name = "sentat")
    private Instant sentAt;

    /**
     * Время сохранения сообщения в ClientBus.
     */
    @CreationTimestamp
    @Column(
            name = "createdat",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    public MessageEntity() {
    }

    public MessageEntity(
            ConversationEntity conversation,
            MessageType type,
            MessageDirection direction,
            MessageSenderType senderType
    ) {
        this.conversation = conversation;
        this.type = type;
        this.direction = direction;
        this.senderType = senderType;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public void setConversation(
            ConversationEntity conversation
    ) {
        this.conversation = conversation;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public void setDirection(
            MessageDirection direction
    ) {
        this.direction = direction;
    }

    public MessageSenderType getSenderType() {
        return senderType;
    }

    public void setSenderType(
            MessageSenderType senderType
    ) {
        this.senderType = senderType;
    }

    public ClientAccountEntity getClientAccount() {
        return clientAccount;
    }

    public void setClientAccount(
            ClientAccountEntity clientAccount
    ) {
        this.clientAccount = clientAccount;
    }

    public EmployeeEntity getEmployee() {
        return employee;
    }

    public void setEmployee(
            EmployeeEntity employee
    ) {
        this.employee = employee;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}