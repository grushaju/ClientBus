package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecentChatsSyncScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    RecentChatsSyncScheduler.class
            );

    private final ChannelAccountRepository
            channelAccountRepository;

    private final RecentChatsSyncCoordinator
            coordinator;

    public RecentChatsSyncScheduler(
            ChannelAccountRepository channelAccountRepository,
            RecentChatsSyncCoordinator coordinator
    ) {
        this.channelAccountRepository =
                channelAccountRepository;

        this.coordinator =
                coordinator;
    }

    @Scheduled(
            initialDelay=
                    0,
            fixedDelayString =
                    "${clientbus.sync.recent-chats.interval}"
    )
    public void synchronizeConnectedTelegramChannels() {

        var accounts =
                channelAccountRepository
                        .findAllByChannelTypeAndChannelStatus(
                                ChannelType.TELEGRAM,
                                ChannelConnectionStatus.CONNECTED
                        );

        int started = 0;
        int skipped = 0;
        int failed = 0;

        for (var account : accounts) {

            try {

                if (coordinator.request(
                        account.getId()
                )) {
                    started++;
                } else {
                    skipped++;
                }

            } catch (RuntimeException e) {

                failed++;

                log.error(
                        "Failed to start periodic recent chats sync: " +
                                "channelAccountId={}",
                        account.getId(),
                        e
                );
            }
        }

        log.info(
                "Recent chats periodic sync finished: " +
                        "candidates={}, started={}, skipped={}, failed={}",
                accounts.size(),
                started,
                skipped,
                failed
        );
    }
}