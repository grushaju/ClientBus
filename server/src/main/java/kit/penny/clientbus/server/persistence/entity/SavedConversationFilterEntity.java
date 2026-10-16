package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.SavedFilterType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "savedconversationfilter",
        indexes = {
                @Index(
                        name = "saved_conversation_filter_workspace_type_idx",
                        columnList = "workspaceid, filtertype"
                ),
                @Index(
                        name = "saved_conversation_filter_workspace_owner_idx",
                        columnList = "workspaceid, owneremployeeid"
                )
        }
)
public class SavedConversationFilterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "savedconversationfilterid",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspaceid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "saved_conversation_filter_workspace_fk"
            )
    )
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "owneremployeeid",
            foreignKey = @ForeignKey(
                    name = "saved_conversation_filter_owner_employee_fk"
            )
    )
    private EmployeeEntity ownerEmployee;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "filtertype",
            nullable = false,
            length = 20
    )
    private SavedFilterType filterType = SavedFilterType.PRIVATE;


    /**
     * JSON с условиями фильтра, не снимок результатов поиска.
     */
    @Column(
            name = "filterdefinition",
            nullable = false,
            length = 8000
    )
    private String filterDefinition;

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

    public SavedConversationFilterEntity() {
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

    public SavedFilterType getFilterType() {
        return filterType;
    }

    public void setFilterType(SavedFilterType filterType) {
        this.filterType = filterType;
    }

    public String getFilterDefinition() {
        return filterDefinition;
    }

    public void setFilterDefinition(String filterDefinition) {
        this.filterDefinition = filterDefinition;
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