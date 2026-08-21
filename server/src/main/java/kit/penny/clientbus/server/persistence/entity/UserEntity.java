package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "users_username_uix",
                        columnNames = "username"
                ),
                @UniqueConstraint(
                        name = "users_email_uix",
                        columnNames = "email"
                )
        }
)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(
            name = "passwordhash",
            nullable = false,
            length = 100
    )
    private String passwordHash;

    @Column(
            name = "isenabled",
            nullable = false
    )
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "role",
            nullable = false,
            length = 30
    )
    private UserRole role;

    public UserEntity() {
    }

    public UserEntity(
            String username,
            String email,
            String passwordHash,
            UserRole role
    ) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public boolean isSuperAdmin() {
        return role == UserRole.SUPER_ADMIN;
    }

    public boolean isEmployee() {
        return role == UserRole.EMPLOYEE;
    }
}