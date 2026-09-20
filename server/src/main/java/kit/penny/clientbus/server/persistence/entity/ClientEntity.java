package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "client")
public class ClientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false, name = "clientid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizationid", nullable = false,
            foreignKey = @ForeignKey(name = "fk_client_organization"))
    private OrganizationEntity organization;

    @Column(name = "firstname", nullable = false, length = 50)
    private String firstName;

    @Column(name = "lastname", nullable = false, length = 50)
    private String lastName;

    @ElementCollection
    @CollectionTable(
            name = "clientphone",
            joinColumns = @JoinColumn(name = "clientid"),
            foreignKey = @ForeignKey(name = "fk_client_phones_client")
    )
    @Column(name = "phone", nullable = false, length = 20)
    private List<String> phoneList = new ArrayList<>();

    @Column(name = "isenabled", nullable = false)
    private boolean isEnabled = true;

    @OneToMany(
            mappedBy = "client",
            fetch = FetchType.LAZY
    )
    private List<ClientAccountEntity> clientAccounts =
            new ArrayList<>();

    @CreationTimestamp
    @Column(name = "createdat", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updatedat")
    private Instant updatedAt;

    // Конструкторы
    public ClientEntity() {
    }

    public ClientEntity(String firstName, String lastName, OrganizationEntity organization) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.organization = organization;
    }

    // Геттеры и сеттеры
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public OrganizationEntity getOrganization() {
        return organization;
    }

    public void setOrganization(OrganizationEntity organization) {
        this.organization = organization;
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

    public List<String> getPhoneList() {
        return phoneList;
    }

    public void setPhoneList(List<String> phoneList) {
        this.phoneList = phoneList != null ? phoneList : new ArrayList<>();
    }

    public void addPhone(String phone) {
        if (phone != null && !phone.isEmpty()) {
            this.phoneList.add(phone);
        }
    }

    public void removePhone(String phone) {
        this.phoneList.remove(phone);
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public List<ClientAccountEntity> getClientAccounts() {
        return clientAccounts;
    }

    public void setClientAccounts(
            List<ClientAccountEntity> clientAccounts
    ) {
        this.clientAccounts = clientAccounts;
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

    @Override
    public String toString() {
        return "ClientEntity{" +
                "id=" + id +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", phones=" + phoneList +
                ", isEnabled=" + isEnabled +
                ", organizationId=" + (organization != null ? organization.getId() : null) +
                '}';
    }
}
