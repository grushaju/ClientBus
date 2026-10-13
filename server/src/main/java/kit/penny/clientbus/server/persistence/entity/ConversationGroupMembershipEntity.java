package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Членство диалога в личной группе.
 *
 * Удаление этой сущности не удаляет Conversation или сообщения.
 */
@Entity
@Table(
        name = "conversationgroupmembership",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "conversation_group_membership_group_conversation_uix",
                        columnNames = {
                                "conversationgroupid",
                                "conversationid"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "conversation_group_membership_conversation_idx",
                        columnList = "conversationid"
                )
        }
)
public class ConversationGroupMembershipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "conversationgroupmembershipid",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "conversationgroupid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "conversation_group_membership_group_fk"
            )
    )
    private ConversationGroupEntity group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "conversationid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "conversation_group_membership_conversation_fk"
            )
    )
    private ConversationEntity conversation;

    @CreationTimestamp
    @Column(
            name = "createdat",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    public ConversationGroupMembershipEntity() {
    }

    public ConversationGroupMembershipEntity(
            ConversationGroupEntity group,
            ConversationEntity conversation
    ) {
        this.group = group;
        this.conversation = conversation;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ConversationGroupEntity getGroup() {
        return group;
    }

    public void setGroup(ConversationGroupEntity group) {
        this.group = group;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public void setConversation(ConversationEntity conversation) {
        this.conversation = conversation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}