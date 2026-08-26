package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.MessageAttachmentType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messageattachment")
public class MessageAttachmentEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "messageid",
            nullable = false
    )
    private MessageEntity message;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "type",
            nullable = false,
            length = 30
    )
    private MessageAttachmentType type;

    @Column(
            name = "filename",
            nullable = false
    )
    private String fileName;

    @Column(
            name = "contenttype",
            nullable = false
    )
    private String contentType;

    @Column(
            name = "size",
            nullable = false
    )
    private long size;

    @Column(
            name = "storagekey",
            nullable = false
    )
    private String storageKey;

    /**
     * ID исходного Message, из которого это вложение
     * было создано при forward.
     *
     * NULL  -> оригинальное вложение, Message владеет
     *          физическим объектом в Storage.
     *
     * NOT NULL -> вложение создано при forward и является
     *             ссылкой на существующий storageKey.
     */
    @Column(name = "forwardfrom")
    private UUID forwardFrom;

    @Column(
            name = "createdat",
            nullable = false
    )
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public MessageEntity getMessage() {
        return message;
    }

    public void setMessage(MessageEntity message) {
        this.message = message;
    }

    public MessageAttachmentType getType() {
        return type;
    }

    public void setType(MessageAttachmentType type) {
        this.type = type;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public UUID getForwardFrom() {
        return forwardFrom;
    }

    public void setForwardFrom(UUID forwardFrom) {
        this.forwardFrom = forwardFrom;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}