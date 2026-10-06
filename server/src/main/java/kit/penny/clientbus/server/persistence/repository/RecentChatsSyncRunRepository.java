package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.persistence.entity.RecentChatsSyncRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RecentChatsSyncRunRepository
        extends JpaRepository<RecentChatsSyncRunEntity, UUID> {

    /**
     * Блокирует current run для конкретного account.
     *
     * Реальная serialization стартов достигается через
     * PESSIMISTIC_WRITE на ChannelAccountEntity,
     * а этот lock дополнительно сериализует работу
     * непосредственно с существующим run.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT run
            FROM RecentChatsSyncRunEntity run
            WHERE run.channelAccount.id = :channelAccountId
            """)
    Optional<RecentChatsSyncRunEntity>
    findByChannelAccountIdForUpdate(
            @Param("channelAccountId")
            UUID channelAccountId
    );

    /**
     * Время БД, а не время конкретного application instance.
     *
     * Это исключает зависимость lease-логики от clock skew
     * между JVM.
     */
    @Query("SELECT CURRENT_TIMESTAMP")
    Instant currentTimestamp();

    /**
     * Fencing check перед фактическим выполнением sync.
     *
     * Проверяется сразу:
     * - runId;
     * - account;
     * - lease;
     * - CONNECTED.
     */
    @Query("""
            SELECT CASE
                       WHEN COUNT(run) > 0 THEN true
                       ELSE false
                   END
            FROM RecentChatsSyncRunEntity run
            WHERE run.syncRunId = :syncRunId
              AND run.channelAccount.id = :channelAccountId
              AND run.leaseUntil > CURRENT_TIMESTAMP
              AND run.channelAccount.channel.status =
                    :requiredStatus
            """)
    boolean isExecutable(
            @Param("channelAccountId")
            UUID channelAccountId,
            @Param("syncRunId")
            UUID syncRunId,
            @Param("requiredStatus")
            ChannelConnectionStatus requiredStatus
    );

    /**
     * Удаляет только конкретного владельца run.
     *
     * Старый run никогда не сможет удалить новый.
     */
    long deleteByChannelAccount_IdAndSyncRunId(
            UUID channelAccountId,
            UUID syncRunId
    );
}