package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.capability.DialogReader;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
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

    private final ChannelConnectorRegistry
            channelConnectorRegistry;

    public RecentChatsSyncScheduler(
            ChannelAccountRepository channelAccountRepository,
            RecentChatsSyncCoordinator coordinator,
            ChannelConnectorRegistry channelConnectorRegistry
    ) {
        this.channelAccountRepository =
                channelAccountRepository;

        this.coordinator =
                coordinator;

        this.channelConnectorRegistry =
                channelConnectorRegistry;
    }

    @Scheduled(
            initialDelay=
                    0,
            fixedDelayString =
                    "${clientbus.sync.recent-chats.interval}"
    )
    public void synchronizeConnectedChannels() {

        int started = 0;
        int skipped = 0;
        int failed = 0;
        int candidates = 0;

        // 13.3: отбор по зарегистрированным возможностям (DialogReader) и состоянию
        // аккаунта — планировщик не знает конкретных платформ.
        for (ChannelType channelType : ChannelType.values()) {

            var connector =
                    channelConnectorRegistry
                            .findConnector(channelType)
                            .orElse(null);

            if (!(connector instanceof DialogReader)) {
                continue;
            }

            var accounts =
                    channelAccountRepository
                            .findAllByChannelTypeAndChannelStatus(
                                    channelType,
                                    ChannelConnectionStatus.CONNECTED
                            );

            candidates += accounts.size();

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
        }

        log.info(
                "Recent chats periodic sync finished: " +
                        "candidates={}, started={}, skipped={}, failed={}",
                candidates,
                started,
                skipped,
                failed
        );
    }
}