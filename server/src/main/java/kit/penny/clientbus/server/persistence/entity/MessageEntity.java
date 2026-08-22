package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
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
                ),
                @Index(
                        name = "message_processing_status_idx",
                        columnList = "processingstatus"
                ),
                @Index(
                        name = "message_delivery_status_idx",
                        columnList = "deliverystatus"
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "conversationid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "message_conversation_fk"
            )
    )
    private ConversationEntity conversation;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "type",
            nullable = false,
            length = 20
    )
    private MessageType type;

    /**
     * INBOUND / OUTBOUND.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "direction",
            nullable = false,
            length = 20
    )
    private MessageDirection direction;

    /**
     * CLIENT / EMPLOYEE / SYSTEM.
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
     * Для входящих сообщений обычно заполняется
     * Connector'ом.
     *
     * Для SYSTEM может быть null.
     */
    @Column(
            name = "externalid",
            length = 255
    )
    private String externalId;

    /**
     * Текст сообщения или caption.
     */
    @Column(
            name = "content",
            columnDefinition = "TEXT"
    )
    private String content;

    /**
     * Дополнительные структурированные данные.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb"
    )
    private String metadata;

    /**
     * Время события на внешней платформе.
     *
     * Для OUTBOUND — время отправки/создания
     * сообщения на платформе.
     *
     * Для INBOUND — время получения сообщения
     * пользователем на платформе.
     */
    @Column(name = "sentat")
    private Instant sentAt;

    /**
     * Время создания записи Message в ClientBus.
     */
    @CreationTimestamp
    @Column(
            name = "createdat",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /**
     * Состояние внутренней обработки сообщения.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "processingstatus",
            nullable = false,
            length = 20
    )
    private MessageProcessingStatus processingStatus;

    /**
     * Состояние доставки исходящего сообщения.
     *
     * Для INBOUND = null.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "deliverystatus",
            length = 20
    )
    private MessageDeliveryStatus deliveryStatus;

    /**
     * Время успешного завершения внутренней обработки.
     */
    @Column(name = "processedat")
    private Instant processedAt;

    /**
     * Время передачи OUTBOUND сообщения
     * внешней платформе.
     */
    @Column(name = "deliveredat")
    private Instant deliveredAt;

    /**
     * Время, когда внешняя платформа
     * подтвердила прочтение.
     */
    @Column(name = "readat")
    private Instant readAt;

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

        this.processingStatus =
                MessageProcessingStatus.RECEIVED;

        this.deliveryStatus =
                direction == MessageDirection.OUTBOUND
                        ? MessageDeliveryStatus.PENDING
                        : null;
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

    public MessageProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(
            MessageProcessingStatus processingStatus
    ) {
        this.processingStatus = processingStatus;
    }

    public MessageDeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(
            MessageDeliveryStatus deliveryStatus
    ) {
        this.deliveryStatus = deliveryStatus;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }
}