package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "employee",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_employee_user", columnNames = "userid")
        })
public class EmployeeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspaceid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "employee_workspace_fk"
            )
    )
    private WorkspaceEntity workspace;

    @OneToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "userid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "employee_user_fk"
            )
    )
    private UserEntity user;

    @Column(
            name = "firstname",
            nullable = false,
            length = 50
    )
    private String firstName;

    @Column(
            name = "lastname",
            nullable = false,
            length = 50
    )
    private String lastName;

    @Column(length = 20)
    private String phone;

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

    public EmployeeEntity() {
    }

    public EmployeeEntity(
            WorkspaceEntity workspace,
            UserEntity user,
            String firstName,
            String lastName,
            String phone
    ) {
        this.workspace = workspace;
        this.user = user;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
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

    public UserEntity getUser() {
        return user;
    }

    public void setUser(UserEntity user) {
        this.user = user;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
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