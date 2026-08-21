package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(
        name = "employeeworkspace",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "employee_workspace_uix",
                        columnNames = {
                                "employeeid",
                                "workspaceid"
                        }
                )
        }
)
public class EmployeeWorkspaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "employeeid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "employee_workspace_employee_fk"
            )
    )
    private EmployeeEntity employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspaceid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "employee_workspace_workspace_fk"
            )
    )
    private WorkspaceEntity workspace;

    public EmployeeWorkspaceEntity() {
    }

    public EmployeeWorkspaceEntity(
            EmployeeEntity employee,
            WorkspaceEntity workspace
    ) {
        this.employee = employee;
        this.workspace = workspace;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public EmployeeEntity getEmployee() {
        return employee;
    }

    public void setEmployee(EmployeeEntity employee) {
        this.employee = employee;
    }

    public WorkspaceEntity getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceEntity workspace) {
        this.workspace = workspace;
    }
}