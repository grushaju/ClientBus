package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(
        name = "channelaccount",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "channel_account_channel_uix",
                        columnNames = "channelid"
                )
        }
)
public class ChannelAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "channelid",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(
                    name = "channel_account_channel_fk"
            )
    )
    private ChannelEntity channel;

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

    public ChannelAccountEntity() {
    }

    public ChannelAccountEntity(
            ChannelEntity channel,
            String externalId,
            String username,
            String phone,
            String displayName
    ) {
        this.channel = channel;
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

    public ChannelEntity getChannel() {
        return channel;
    }

    public void setChannel(ChannelEntity channel) {
        this.channel = channel;
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