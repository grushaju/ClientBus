package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.config.properties.RecentChatsSyncProperties;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.producer.ISyncRecentChatsCommandPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class RecentChatsSyncCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(
                    RecentChatsSyncCoordinator.class
            );

    private final RecentChatsSyncRunService
            syncRunService;

    private final ISyncRecentChatsCommandPublisher
            publisher;

    private final RecentChatsSyncProperties
            properties;

    public RecentChatsSyncCoordinator(
            RecentChatsSyncRunService syncRunService,
            ISyncRecentChatsCommandPublisher publisher,
            RecentChatsSyncProperties properties
    ) {
        this.syncRunService =
                syncRunService;

        this.publisher =
                publisher;

        this.properties =
                properties;
    }

    /**
     * Attempts to start one recent-chats sync.
     *
     * @return true if a new run was acquired and
     *         the Kafka command was submitted.
     */
    public boolean request(
            UUID channelAccountId
    ) {
        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        UUID syncRunId =
                UUID.randomUUID();

        Optional<ChannelType>
                channelType =
                syncRunService.tryStart(
                        channelAccountId,
                        syncRunId,
                        properties.lease()
                );

        if (channelType.isEmpty()) {
            return false;
        }

        try {

            CompletableFuture<Void> publishFuture =
                    publisher.publish(
                            channelType.get(),
                            new SyncRecentChatsCommand(
                                    channelAccountId
                            ),
                            syncRunId
                    );

            if (publishFuture == null) {

                syncRunService.complete(
                        channelAccountId,
                        syncRunId
                );

                throw new IllegalStateException(
                        "Recent chats sync publisher returned null future"
                );
            }

            publishFuture.whenComplete(
                    (ignored, error) -> {

                        if (error == null) {
                            return;
                        }

                        releaseAfterPublishFailure(
                                channelAccountId,
                                syncRunId,
                                error
                        );
                    }
            );

            log.debug(
                    "Recent chats sync started: " +
                            "channelAccountId={}, syncRunId={}, channelType={}",
                    channelAccountId,
                    syncRunId,
                    channelType.get()
            );

            return true;

        } catch (RuntimeException e) {

            syncRunService.complete(
                    channelAccountId,
                    syncRunId
            );

            throw e;
        }
    }

    public boolean isExecutable(
            UUID channelAccountId,
            UUID syncRunId
    ) {
        return syncRunService.isExecutable(
                channelAccountId,
                syncRunId
        );
    }

    public boolean complete(
            UUID channelAccountId,
            UUID syncRunId
    ) {
        boolean completed =
                syncRunService.complete(
                        channelAccountId,
                        syncRunId
                );

        if (completed) {
            log.debug(
                    "Recent chats sync completed: " +
                            "channelAccountId={}, syncRunId={}",
                    channelAccountId,
                    syncRunId
            );
        }

        return completed;
    }

    private void releaseAfterPublishFailure(
            UUID channelAccountId,
            UUID syncRunId,
            Throwable error
    ) {
        try {

            boolean released =
                    syncRunService.complete(
                            channelAccountId,
                            syncRunId
                    );

            log.error(
                    "Recent chats sync Kafka publication failed: " +
                            "channelAccountId={}, syncRunId={}, " +
                            "runReleased={}",
                    channelAccountId,
                    syncRunId,
                    released,
                    error
            );

        } catch (RuntimeException releaseError) {

            log.error(
                    "Failed to release recent chats sync run " +
                            "after Kafka publication failure: " +
                            "channelAccountId={}, syncRunId={}",
                    channelAccountId,
                    syncRunId,
                    releaseError
            );
        }
    }
}