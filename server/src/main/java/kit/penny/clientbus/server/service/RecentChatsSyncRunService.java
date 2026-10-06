package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.RecentChatsSyncRunEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.RecentChatsSyncRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecentChatsSyncRunService {

    private final ChannelAccountRepository
            channelAccountRepository;

    private final RecentChatsSyncRunRepository
            syncRunRepository;

    public RecentChatsSyncRunService(
            ChannelAccountRepository channelAccountRepository,
            RecentChatsSyncRunRepository syncRunRepository
    ) {
        this.channelAccountRepository =
                channelAccountRepository;

        this.syncRunRepository =
                syncRunRepository;
    }

    /**
     * Atomically tries to start a recent-chats synchronization.
     *
     * Returned ChannelType means that the run was acquired.
     * Empty result means:
     * - channel is not CONNECTED;
     * - another run is still active.
     */
    @Transactional
    public Optional<ChannelType> tryStart(
            UUID channelAccountId,
            UUID syncRunId,
            Duration lease
    ) {
        validateIds(
                channelAccountId,
                syncRunId
        );

        validateLease(lease);

        /*
         * Stable serialization point.
         *
         * This lock exists even when there is no SyncRun row yet,
         * which closes the concurrent INSERT race.
         */
        ChannelAccountEntity channelAccount =
                channelAccountRepository
                        .findByIdForUpdate(
                                channelAccountId
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Channel account not found: "
                                                + channelAccountId
                                )
                        );

        ChannelEntity channel =
                channelAccount.getChannel();

        if (channel == null) {
            throw new IllegalStateException(
                    "Channel account has no channel: "
                            + channelAccountId
            );
        }

        if (channel.getStatus()
                != ChannelConnectionStatus.CONNECTED) {
            return Optional.empty();
        }

        ChannelType channelType =
                channel.getType();

        if (channelType == null) {
            throw new IllegalStateException(
                    "Channel has no type: "
                            + channel.getId()
            );
        }

        Instant now =
                syncRunRepository.currentTimestamp();

        Optional<RecentChatsSyncRunEntity>
                currentRun =
                syncRunRepository
                        .findByChannelAccountIdForUpdate(
                                channelAccountId
                        );

        if (currentRun.isPresent()) {

            RecentChatsSyncRunEntity run =
                    currentRun.get();

            /*
             * Existing lease is still valid.
             * The account is already being synchronized.
             */
            if (run.getLeaseUntil().isAfter(now)) {
                return Optional.empty();
            }

            /*
             * Lease expired.
             *
             * SyncRun has syncRunId as its own PK,
             * therefore takeover is represented by:
             *
             * old run DELETE
             * +
             * new run INSERT
             */
            syncRunRepository.delete(run);

            syncRunRepository.flush();
        }

        RecentChatsSyncRunEntity newRun =
                new RecentChatsSyncRunEntity(
                        syncRunId,
                        channelAccount,
                        now,
                        now.plus(lease)
                );

        syncRunRepository.saveAndFlush(
                newRun
        );

        return Optional.of(channelType);
    }

    /**
     * Fenced release.
     *
     * Deletes only the run identified by BOTH:
     * - channelAccountId
     * - syncRunId
     */
    @Transactional
    public boolean complete(
            UUID channelAccountId,
            UUID syncRunId
    ) {
        validateIds(
                channelAccountId,
                syncRunId
        );

        return syncRunRepository
                .deleteByChannelAccount_IdAndSyncRunId(
                        channelAccountId,
                        syncRunId
                ) > 0;
    }

    /**
     * Checks whether the exact run still owns the execution slot
     * and the channel remains connected.
     */
    @Transactional(readOnly = true)
    public boolean isExecutable(
            UUID channelAccountId,
            UUID syncRunId
    ) {
        validateIds(
                channelAccountId,
                syncRunId
        );

        return syncRunRepository.isExecutable(
                channelAccountId,
                syncRunId,
                ChannelConnectionStatus.CONNECTED
        );
    }

    private void validateIds(
            UUID channelAccountId,
            UUID syncRunId
    ) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (syncRunId == null) {
            throw new IllegalArgumentException(
                    "syncRunId must not be null"
            );
        }
    }

    private void validateLease(
            Duration lease
    ) {
        if (lease == null
                || lease.isZero()
                || lease.isNegative()) {
            throw new IllegalArgumentException(
                    "lease must be positive"
            );
        }
    }
}