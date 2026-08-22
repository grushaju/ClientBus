package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ClientAccountState;

import java.util.UUID;

@Entity
@Table(
        name = "clientaccount",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "clientaccount_client_type_external_uix",
                        columnNames = {
                                "clientid",
                                "channeltype",
                                "externalid"
                        }
                )
        }
)
public class ClientAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "clientid",
            nullable = true,
            foreignKey = @ForeignKey(
                    name = "clientaccount_client_fk"
            )
    )
    private ClientEntity client;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "channeltype",
            nullable = false,
            length = 30
    )
    private ChannelType channelType;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "state",
            nullable = false,
            length = 20
    )
    private ClientAccountState state = ClientAccountState.ACTIVE;

    @Column(
            name = "externalid",
            nullable = false,
            length = 200
    )
    private String externalId;

    @Column(length = 100)
    private String username;

    @Column(length = 30)
    private String phone;

    @Column(
            name = "displayname",
            length = 200
    )
    private String displayName;

    public ClientAccountEntity() {
    }

    public ClientAccountEntity(
            ClientEntity client,
            ChannelType channelType,
            String externalId,
            String username,
            String phone,
            String displayName
    ) {
        this.client = client;
        this.channelType = channelType;
        this.externalId = externalId;
        this.username = username;
        this.phone = phone;
        this.displayName = displayName;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ClientEntity getClient() {
        return client;
    }

    public void setClient(ClientEntity client) {
        this.client = client;
    }

    public ChannelType getChannelType() {
        return channelType;
    }

    public void setChannelType(ChannelType channelType) {
        this.channelType = channelType;
    }

    public ClientAccountState getState() {
        return state;
    }

    public void setState(ClientAccountState state) {
        this.state = state;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
