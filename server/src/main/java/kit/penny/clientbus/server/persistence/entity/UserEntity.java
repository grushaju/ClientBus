package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "users_login_uix",
                        columnNames = "login"
                )
        }
)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String login;

    @Column(name = "passwordhash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "isenabled", nullable = false)
    private boolean enabled = true;

    public UserEntity() {
    }

    public UserEntity(String login, String passwordHash) {
        this.login = login;
        this.passwordHash = passwordHash;
        this.enabled = true;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
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
}