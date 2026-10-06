package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "recentchatssyncrun",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "recent_chats_sync_run_channel_account_uix",
                        columnNames = "channelaccountid"
                )
        }
)
public class RecentChatsSyncRunEntity {

    @Id
    @Column(
            name = "syncrunid",
            nullable = false,
            updatable = false
    )
    private UUID syncRunId;

    /**
     * У одного ChannelAccount может существовать
     * не более одного активного/current SyncRun.
     *
     * Дополнительная UNIQUE constraint на уровне БД
     * является обязательным инвариантом.
     */
    @OneToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "channelaccountid",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(
                    name = "recent_chats_sync_run_channel_account_fk"
            )
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChannelAccountEntity channelAccount;

    /**
     * Момент запуска именно этого run.
     */
    @Column(
            name = "startedat",
            nullable = false
    )
    private Instant startedAt;

    /**
     * После этого момента run считается протухшим.
     */
    @Column(
            name = "leaseuntil",
            nullable = false
    )
    private Instant leaseUntil;

    public RecentChatsSyncRunEntity() {
    }

    public RecentChatsSyncRunEntity(
            UUID syncRunId,
            ChannelAccountEntity channelAccount,
            Instant startedAt,
            Instant leaseUntil
    ) {
        this.syncRunId = syncRunId;
        this.channelAccount = channelAccount;
        this.startedAt = startedAt;
        this.leaseUntil = leaseUntil;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }

    public void setSyncRunId(UUID syncRunId) {
        this.syncRunId = syncRunId;
    }

    public ChannelAccountEntity getChannelAccount() {
        return channelAccount;
    }

    public void setChannelAccount(
            ChannelAccountEntity channelAccount
    ) {
        this.channelAccount = channelAccount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }
}