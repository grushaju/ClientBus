package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "conversationgroup",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "conversation_group_workspace_owner_name_uix",
                        columnNames = {
                                "workspaceid",
                                "owneremployeeid",
                                "name"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "conversation_group_workspace_owner_idx",
                        columnList = "workspaceid, owneremployeeid"
                )
        }
)
public class ConversationGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "conversationgroupid",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspaceid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "conversation_group_workspace_fk"
            )
    )
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "owneremployeeid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "conversation_group_owner_employee_fk"
            )
    )
    private EmployeeEntity ownerEmployee;

    @Column(nullable = false, length = 100)
    private String name;

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

    public ConversationGroupEntity() {
    }

    public ConversationGroupEntity(
            WorkspaceEntity workspace,
            EmployeeEntity ownerEmployee,
            String name
    ) {
        this.workspace = workspace;
        this.ownerEmployee = ownerEmployee;
        this.name = name;
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

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }

    public EmployeeEntity getOwnerEmployee() {
        return ownerEmployee;
    }

    public void setOwnerEmployee(EmployeeEntity ownerEmployee) {
        this.ownerEmployee = ownerEmployee;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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