package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ClientAccountState;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformConversationPublisher;
import kit.penny.clientbus.server.kafka.producer.ISyncConversationHistoryCommandPublisher;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecentChatsSynchronizationService {

    private static final int INITIAL_HISTORY_LIMIT = 20;

    private static final int INITIAL_HISTORY_PAGE_SIZE = 50;

    private final ClientAccountService clientAccountService;

    private final IPlatformConversationPublisher
            platformConversationPublisher;

    private final ISyncConversationHistoryCommandPublisher
            syncConversationHistoryCommandPublisher;

    public RecentChatsSynchronizationService(
            ClientAccountService clientAccountService,
            IPlatformConversationPublisher platformConversationPublisher,
            ISyncConversationHistoryCommandPublisher
                    syncConversationHistoryCommandPublisher
    ) {
        this.clientAccountService =
                clientAccountService;

        this.platformConversationPublisher =
                platformConversationPublisher;

        this.syncConversationHistoryCommandPublisher =
                syncConversationHistoryCommandPublisher;
    }

    public void process(
            ChannelType channelType,
            List<PlatformConversationRequest> conversations
    ) {

        if (channelType == null) {
            throw new IllegalArgumentException(
                    "channelType must not be null"
            );
        }

        if (conversations == null
                || conversations.isEmpty()) {
            return;
        }

        int historySyncCount = 0;

        for (PlatformConversationRequest conversation :
                conversations) {

            if (conversation == null) {
                throw new IllegalArgumentException(
                        "Platform conversation must not be null"
                );
            }

            ClientAccountEntity clientAccount =
                    clientAccountService.getOrCreateForInbound(
                            channelType,
                            conversation.clientExternalId(),
                            conversation.clientUsername(),
                            conversation.clientPhone(),
                            conversation.clientDisplayName()
                    );

            platformConversationPublisher.publish(
                    conversation
            );

            if (historySyncCount >= INITIAL_HISTORY_LIMIT) {
                continue;
            }

            if (!isHistorySyncEligible(clientAccount)) {
                continue;
            }

            syncConversationHistoryCommandPublisher.publish(
                    channelType,
                    new SyncConversationHistoryCommand(
                            conversation.channelAccountId(),
                            conversation.clientExternalId(),
                            null,
                            INITIAL_HISTORY_PAGE_SIZE
                    )
            );

            historySyncCount++;
        }
    }

    private boolean isHistorySyncEligible(
            ClientAccountEntity clientAccount
    ) {

        ClientAccountState state =
                clientAccount.getState();

        return state == ClientAccountState.ACTIVE
                || state == ClientAccountState.ARCHIVE;
    }
}