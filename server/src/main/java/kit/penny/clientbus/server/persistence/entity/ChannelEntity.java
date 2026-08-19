package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

@Entity
@Table(name = "channel")
public class ChannelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspaceid",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "channel_workspace_fk"
            )
    )
    private WorkspaceEntity workspace;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "type",
            nullable = false,
            length = 30
    )
    private ChannelType type;

    @Column(nullable = false, length = 100)
    private String name;

    @OneToOne(
            mappedBy = "channel",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private ChannelAccountEntity account;

    public ChannelEntity() {
    }

    public ChannelEntity(
            WorkspaceEntity workspace,
            ChannelType type,
            String name
    ) {
        this.workspace = workspace;
        this.type = type;
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

    public ChannelType getType() {
        return type;
    }

    public void setType(ChannelType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ChannelAccountEntity getAccount() {
        return account;
    }

    public void setAccount(ChannelAccountEntity account) {
        this.account = account;

        if (account != null && account.getChannel() != this) {
            account.setChannel(this);
        }
    }
}
