package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.MessageAttachmentType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "message_attachment",
        indexes = {
                @Index(
                        name = "message_attachment_message_idx",
                        columnList = "messageid"
                ),
                @Index(
                        name = "message_attachment_storage_key_uix",
                        columnList = "storagekey",
                        unique = true
                ),
                @Index(
                        name = "message_attachment_message_sort_idx",
                        columnList = "messageid,sortorder"
                )
        }
)
public class MessageAttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            nullable = false,
            updatable = false
    )
    private UUID id;

    /**
     * Сообщение, которому принадлежит вложение.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "messageid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "message_attachment_message_fk"
            )
    )
    private MessageEntity message;

    /**
     * Тип физического вложения.
     *
     * На текущем этапе:
     * IMAGE
     * AUDIO
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "type",
            nullable = false,
            length = 20
    )
    private MessageAttachmentType type;

    /**
     * Внутренний ключ объекта в Object Storage.
     *
     * Например:
     *
     * organizations/{organizationId}/
     * workspaces/{workspaceId}/
     * conversations/{conversationId}/
     * messages/{messageId}/
     * attachments/{attachmentId}
     */
    @Column(
            name = "storagekey",
            nullable = false,
            length = 1000
    )
    private String storageKey;

    /**
     * Оригинальное имя файла.
     */
    @Column(
            name = "filename",
            length = 500
    )
    private String fileName;

    /**
     * MIME type.
     *
     * Например:
     * image/jpeg
     * image/png
     * audio/mpeg
     * audio/ogg
     * audio/wav
     */
    @Column(
            name = "mimetype",
            length = 255
    )
    private String mimeType;

    /**
     * Размер файла в байтах.
     */
    @Column(
            name = "size",
            nullable = false
    )
    private long size;

    /**
     * SHA-256 checksum файла.
     */
    @Column(
            name = "checksum",
            length = 128
    )
    private String checksum;

    /**
     * Ширина изображения в пикселях.
     *
     * Используется для IMAGE.
     */
    @Column(name = "width")
    private Integer width;

    /**
     * Высота изображения в пикселях.
     *
     * Используется для IMAGE.
     */
    @Column(name = "height")
    private Integer height;

    /**
     * Продолжительность аудио в миллисекундах.
     *
     * Используется для AUDIO.
     */
    @Column(name = "durationms")
    private Long durationMs;

    /**
     * Порядок вложения внутри сообщения.
     */
    @Column(
            name = "sortorder",
            nullable = false
    )
    private int sortOrder;

    /**
     * Время создания записи.
     */
    @Column(
            name = "createdat",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    public MessageAttachmentEntity() {
    }

    public MessageAttachmentEntity(
            MessageEntity message,
            MessageAttachmentType type,
            String storageKey,
            int sortOrder
    ) {
        this.message = message;
        this.type = type;
        this.storageKey = storageKey;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}