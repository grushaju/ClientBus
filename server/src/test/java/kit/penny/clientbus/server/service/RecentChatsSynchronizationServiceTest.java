package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ClientAccountState;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.kafka.producer.IPlatformConversationPublisher;
import kit.penny.clientbus.server.kafka.producer.ISyncConversationHistoryCommandPublisher;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecentChatsSynchronizationServiceTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ClientAccountService clientAccountService;

    @Mock
    private IPlatformConversationPublisher
            platformConversationPublisher;

    @Mock
    private ISyncConversationHistoryCommandPublisher
            syncConversationHistoryCommandPublisher;

    private RecentChatsSynchronizationService service;

    @BeforeEach
    void setUp() {
        service = new RecentChatsSynchronizationService(
                clientAccountService,
                platformConversationPublisher,
                syncConversationHistoryCommandPublisher
        );
    }

    @Test
    void shouldPublishEveryRecentConversation() {

        PlatformConversationRequest first =
                conversation("200");

        PlatformConversationRequest second =
                conversation("201");

        stubAccount("200", ClientAccountState.IGNORED);
        stubAccount("201", ClientAccountState.BLOCKED);

        service.process(
                ChannelType.TELEGRAM,
                List.of(first, second)
        );

        verify(platformConversationPublisher)
                .publish(first);

        verify(platformConversationPublisher)
                .publish(second);

        verify(
                platformConversationPublisher,
                times(2)
        ).publish(any(PlatformConversationRequest.class));

        verifyNoInteractions(
                syncConversationHistoryCommandPublisher
        );
    }

    @Test
    void shouldStartHistoryForActiveAndArchivedAccounts() {

        PlatformConversationRequest active =
                conversation("200");

        PlatformConversationRequest archived =
                conversation("201");

        stubAccount("200", ClientAccountState.ACTIVE);
        stubAccount("201", ClientAccountState.ARCHIVE);

        service.process(
                ChannelType.TELEGRAM,
                List.of(active, archived)
        );

        ArgumentCaptor<SyncConversationHistoryCommand> captor =
                ArgumentCaptor.forClass(
                        SyncConversationHistoryCommand.class
                );

        verify(syncConversationHistoryCommandPublisher, times(2))
                .publish(
                        eq(ChannelType.TELEGRAM),
                        captor.capture()
                );

        List<SyncConversationHistoryCommand> commands =
                captor.getAllValues();

        assertEquals(
                List.of(
                        new SyncConversationHistoryCommand(
                                ACCOUNT_ID,
                                "200",
                                null,
                                50
                        ),
                        new SyncConversationHistoryCommand(
                                ACCOUNT_ID,
                                "201",
                                null,
                                50
                        )
                ),
                commands
        );
    }

    @Test
    void shouldNotStartHistoryForIgnoredOrBlockedAccounts() {

        PlatformConversationRequest ignored =
                conversation("200");

        PlatformConversationRequest blocked =
                conversation("201");

        stubAccount("200", ClientAccountState.IGNORED);
        stubAccount("201", ClientAccountState.BLOCKED);

        service.process(
                ChannelType.TELEGRAM,
                List.of(ignored, blocked)
        );

        verify(platformConversationPublisher)
                .publish(ignored);

        verify(platformConversationPublisher)
                .publish(blocked);

        verifyNoInteractions(
                syncConversationHistoryCommandPublisher
        );
    }

    @Test
    void shouldStartHistoryForFirstTwentyEligibleAccounts() {

        List<PlatformConversationRequest> conversations =
                new ArrayList<>();

        for (int i = 0; i < 25; i++) {
            String externalId = Integer.toString(200 + i);

            conversations.add(conversation(externalId));

            stubAccount(
                    externalId,
                    ClientAccountState.ACTIVE
            );
        }

        service.process(
                ChannelType.TELEGRAM,
                conversations
        );

        ArgumentCaptor<SyncConversationHistoryCommand> captor =
                ArgumentCaptor.forClass(
                        SyncConversationHistoryCommand.class
                );

        verify(syncConversationHistoryCommandPublisher, times(20))
                .publish(
                        eq(ChannelType.TELEGRAM),
                        captor.capture()
                );

        List<String> actualExternalIds =
                captor.getAllValues()
                        .stream()
                        .map(SyncConversationHistoryCommand::conversationExternalId)
                        .toList();

        List<String> expectedExternalIds =
                conversations.subList(0, 20)
                        .stream()
                        .map(PlatformConversationRequest::clientExternalId)
                        .toList();

        assertIterableEquals(
                expectedExternalIds,
                actualExternalIds
        );
    }

    @Test
    void shouldNotCountIneligibleAccountsAgainstHistoryLimit() {

        List<PlatformConversationRequest> conversations =
                new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            String externalId = "ignored-" + i;

            conversations.add(conversation(externalId));

            stubAccount(
                    externalId,
                    ClientAccountState.IGNORED
            );
        }

        for (int i = 0; i < 20; i++) {
            String externalId = "active-" + i;

            conversations.add(conversation(externalId));

            stubAccount(
                    externalId,
                    ClientAccountState.ACTIVE
            );
        }

        service.process(
                ChannelType.TELEGRAM,
                conversations
        );

        ArgumentCaptor<SyncConversationHistoryCommand> captor =
                ArgumentCaptor.forClass(
                        SyncConversationHistoryCommand.class
                );

        verify(syncConversationHistoryCommandPublisher, times(20))
                .publish(
                        eq(ChannelType.TELEGRAM),
                        captor.capture()
                );

        List<String> actualExternalIds =
                captor.getAllValues()
                        .stream()
                        .map(SyncConversationHistoryCommand::conversationExternalId)
                        .toList();

        List<String> expectedExternalIds =
                conversations.subList(5, 25)
                        .stream()
                        .map(PlatformConversationRequest::clientExternalId)
                        .toList();

        assertIterableEquals(
                expectedExternalIds,
                actualExternalIds
        );
    }

    @Test
    void shouldPreserveRecentChatsOrderWhenStartingHistory() {

        PlatformConversationRequest first =
                conversation("200");

        PlatformConversationRequest second =
                conversation("201");

        PlatformConversationRequest third =
                conversation("202");

        stubAccount("200", ClientAccountState.ACTIVE);
        stubAccount("201", ClientAccountState.ACTIVE);
        stubAccount("202", ClientAccountState.ACTIVE);

        service.process(
                ChannelType.TELEGRAM,
                List.of(first, second, third)
        );

        ArgumentCaptor<SyncConversationHistoryCommand> captor =
                ArgumentCaptor.forClass(
                        SyncConversationHistoryCommand.class
                );

        verify(syncConversationHistoryCommandPublisher, times(3))
                .publish(
                        eq(ChannelType.TELEGRAM),
                        captor.capture()
                );

        List<String> actualExternalIds =
                captor.getAllValues()
                        .stream()
                        .map(SyncConversationHistoryCommand::conversationExternalId)
                        .toList();

        assertIterableEquals(
                List.of("200", "201", "202"),
                actualExternalIds
        );
    }

    @Test
    void shouldDoNothingForEmptyConversationList() {

        service.process(
                ChannelType.TELEGRAM,
                List.of()
        );

        verifyNoInteractions(
                clientAccountService,
                platformConversationPublisher,
                syncConversationHistoryCommandPublisher
        );
    }

    private void stubAccount(
            String externalId,
            ClientAccountState state
    ) {
        when(clientAccountService.getOrCreateForInbound(
                eq(ChannelType.TELEGRAM),
                eq(externalId),
                isNull(),
                isNull(),
                eq("Client " + externalId)
        )).thenReturn(
                clientAccount(state)
        );
    }

    private static PlatformConversationRequest conversation(
            String externalId
    ) {
        return new PlatformConversationRequest(
                ACCOUNT_ID,
                externalId,
                null,
                null,
                "Client " + externalId,
                Instant.parse("2026-10-01T10:00:00Z"),
                "Hello",
                0
        );
    }

    private static ClientAccountEntity clientAccount(
            ClientAccountState state
    ) {
        ClientAccountEntity entity =
                new ClientAccountEntity();

        entity.setState(state);

        return entity;
    }
}