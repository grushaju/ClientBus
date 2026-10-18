package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.*;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.*;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceSearchInboxTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private ChannelAccountRepository channelAccountRepository;
    @Mock
    private ClientAccountRepository clientAccountRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private kit.penny.clientbus.server.kafka.producer.IChannelReadPublisher
            channelReadPublisher;
    @Mock
    private ConversationGroupRepository conversationGroupRepository;
    @Mock
    private ConversationGroupMembershipRepository
            conversationGroupMembershipRepository;

    @InjectMocks
    private ConversationService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    private final UUID channelId = UUID.randomUUID();
    private final UUID anotherChannelId = UUID.randomUUID();

    private final Instant from =
            Instant.parse("2026-10-10T10:00:00Z");
    private final Instant to =
            Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void clientFilter_matchesAllClientAccountsAndIntersectsExplicitAccountSelection() {
        givenEmployeeAccess();
        UUID clientId = UUID.randomUUID();
        ConversationEntity first = conversation(channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1", "Hello", 0, from);
        ConversationEntity second = conversation(channelId, UUID.randomUUID(), employeeId,
                true, "Alice 2", "alice2", "2", "Hello", 0, to);
        ConversationEntity other = conversation(channelId, UUID.randomUUID(), employeeId,
                true, "Bob", "bob", "3", "Hello", 0, to);
        ConversationEntity orphan = conversation(channelId, UUID.randomUUID(), employeeId,
                false, "Orphan", "orphan", "4", "Hello", 0, to);
        when(first.getClientAccount().getClient().getId()).thenReturn(clientId);
        when(second.getClientAccount().getClient().getId()).thenReturn(clientId);
        when(other.getClientAccount().getClient().getId()).thenReturn(UUID.randomUUID());
        givenConversations(first, second, other, orphan);
        givenMapper();
        ConversationSearchResponse allAccounts = service.searchInbox(workspaceId,
                new ConversationSearchRequest(ConversationView.MINE, null, null, null, null,
                        null, null, null, null, ConversationSort.LAST_MESSAGE_ASC, 0, 20, List.of(clientId)));
        assertThat(allAccounts.content()).extracting(ConversationDto::id)
                .containsExactly(first.getId(), second.getId());
        ConversationSearchResponse oneAccount = service.searchInbox(workspaceId,
                new ConversationSearchRequest(ConversationView.MINE, null, null, null,
                        List.of(second.getClientAccount().getId()), null, null, null, null,
                        ConversationSort.LAST_MESSAGE_ASC, 0, 20, List.of(clientId)));
        assertThat(oneAccount.content()).extracting(ConversationDto::id).containsExactly(second.getId());
    }

    @Test
    void mineView_returnsOnlyConversationsAssignedToCurrentEmployee() {
        givenEmployeeAccess();

        ConversationEntity mine = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1001",
                "Hello", 1, from
        );

        ConversationEntity anotherEmployeeConversation = conversation(
                channelId, UUID.randomUUID(), UUID.randomUUID(),
                true, "Bob", "bob", "1002",
                "Hello", 1, to
        );

        ConversationEntity unassigned = conversation(
                channelId, UUID.randomUUID(), null,
                true, "Carol", "carol", "1003",
                "Hello", 0, to
        );

        givenConversations(
                mine, anotherEmployeeConversation, unassigned
        );
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId, request(ConversationView.MINE)
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(mine.getId());
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void unassignedView_returnsOnlyUnassignedConversations() {
        givenEmployeeAccess();

        ConversationEntity mine = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1001",
                "Hello", 1, from
        );

        ConversationEntity unassigned = conversation(
                channelId, UUID.randomUUID(), null,
                false, "Bob", "bob", "1002",
                "Hello", 0, to
        );

        givenConversations(mine, unassigned);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId, request(ConversationView.UNASSIGNED)
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(unassigned.getId());
    }

    @Test
    void combinedFilters_applyChannelClientLinkUnreadActivityAndSearch() {
        givenEmployeeAccess();

        ConversationEntity matching = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1001",
                "Invoice paid", 2, from
        );

        ConversationEntity wrongChannel = conversation(
                anotherChannelId, UUID.randomUUID(), employeeId,
                true, "Bob", "bob", "1002",
                "Invoice paid", 2, from
        );

        ConversationEntity withoutClient = conversation(
                channelId, UUID.randomUUID(), employeeId,
                false, "Carol", "carol", "1003",
                "Invoice paid", 2, from
        );

        ConversationEntity readOnly = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "David", "david", "1004",
                "Invoice paid", 0, from
        );

        ConversationEntity tooOld = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Eve", "eve", "1005",
                "Invoice paid", 2,
                Instant.parse("2026-10-10T09:59:59Z")
        );

        ConversationEntity wrongSearch = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Frank", "frank", "1006",
                "Question about delivery", 2, from
        );

        givenConversations(
                matching, wrongChannel, withoutClient,
                readOnly, tooOld, wrongSearch
        );
        givenMapper();

        ConversationSearchRequest searchRequest =
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null,
                        "  INVOICE  ",
                        List.of(channelId),
                        List.of(matching.getClientAccount().getId()),
                        ConversationClientLinkFilter.WITH_CLIENT,
                        ConversationUnreadFilter.HAS_UNREAD,
                        from,
                        to,
                        ConversationSort.LAST_MESSAGE_DESC,
                        0,
                        20
                );

        ConversationSearchResponse response =
                service.searchInbox(workspaceId, searchRequest);

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(matching.getId());
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void search_matchesClientDisplayNameUsernameExternalIdAndPreview() {
        givenEmployeeAccess();

        ConversationEntity byName = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "UniqueName", "alice", "1001",
                "Hello", 0, from
        );

        ConversationEntity byUsername = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Bob", "unique_user", "1002",
                "Hello", 0, from
        );

        ConversationEntity byExternalId = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Carol", "carol", "unique_external",
                "Hello", 0, from
        );

        ConversationEntity byPreview = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "David", "david", "1004",
                "unique_message", 0, from
        );

        givenConversations(
                byName, byUsername, byExternalId, byPreview
        );
        givenMapper();

        assertSearchFinds("uniquename", byName);
        assertSearchFinds("UNIQUE_USER", byUsername);
        assertSearchFinds("unique_external", byExternalId);
        assertSearchFinds("unique_message", byPreview);
    }

    @Test
    void groupView_returnsOnlyAccessibleGroupMembers() {
        givenEmployeeAccess();

        UUID groupId = UUID.randomUUID();

        ConversationEntity member = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1001",
                "Hello", 0, from
        );

        ConversationEntity notMember = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Bob", "bob", "1002",
                "Hello", 0, to
        );

        ConversationGroupEntity group =
                mock(ConversationGroupEntity.class);

        ConversationGroupMembershipEntity membership =
                mock(ConversationGroupMembershipEntity.class);

        when(conversationGroupRepository
                .findByIdAndWorkspaceIdAndOwnerEmployeeId(
                        groupId, workspaceId, employeeId
                ))
                .thenReturn(Optional.of(group));

        when(membership.getConversation()).thenReturn(member);

        when(conversationGroupMembershipRepository
                .findAccessibleMemberships(
                        groupId, workspaceId, employeeId
                ))
                .thenReturn(List.of(membership));

        givenConversations(member, notMember);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.GROUP,
                        groupId,
                        null, null, null, null, null,
                        null, null, null, 0, 20
                )
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(member.getId());

        verify(conversationGroupMembershipRepository)
                .findAccessibleMemberships(
                        groupId, workspaceId, employeeId
                );
    }

    @Test
    void groupView_rejectsGroupOwnedByAnotherEmployee() {
        givenEmployeeAccess();

        UUID groupId = UUID.randomUUID();

        when(conversationGroupRepository
                .findByIdAndWorkspaceIdAndOwnerEmployeeId(
                        groupId, workspaceId, employeeId
                ))
                .thenReturn(Optional.empty());

        assertThrows(
                AccessDeniedException.class,
                () -> service.searchInbox(
                        workspaceId,
                        new ConversationSearchRequest(
                                ConversationView.GROUP,
                                groupId,
                                null, null, null, null, null,
                                null, null, null, 0, 20
                        )
                )
        );

        verifyNoInteractions(conversationRepository);
        verifyNoInteractions(conversationGroupMembershipRepository);
    }

    @Test
    void groupView_requiresGroupId() {
        givenEmployeeAccess();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.searchInbox(
                        workspaceId, request(ConversationView.GROUP)
                )
        );

        verifyNoInteractions(conversationRepository);
    }

    @Test
    void employeeCannotUseAllView() {
        givenEmployeeAccess();

        assertThrows(
                AccessDeniedException.class,
                () -> service.searchInbox(
                        workspaceId, request(ConversationView.ALL)
                )
        );

        verifyNoInteractions(conversationRepository);
    }

    @Test
    void superAdminCannotUseMineView() {
        when(currentUserService.isSuperAdmin()).thenReturn(true);

        assertThrows(
                AccessDeniedException.class,
                () -> service.searchInbox(
                        workspaceId, request(ConversationView.MINE)
                )
        );

        verifyNoInteractions(conversationRepository);
    }

    @Test
    void paginationReturnsCorrectPageAndMetadata() {
        givenEmployeeAccess();

        ConversationEntity first = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1001",
                "Hello", 0, from
        );

        ConversationEntity second = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Bob", "bob", "1002",
                "Hello", 0, to
        );

        givenConversations(first, second);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, null, null, null, null, null,
                        null, null,
                        ConversationSort.LAST_MESSAGE_DESC,
                        0, 1
                )
        );

        assertThat(response.content())
                .hasSize(1);
        assertThat(response.content().get(0).id())
                .isEqualTo(second.getId());
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    void rejectsNonPositivePageSize() {
        givenEmployeeAccess();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.searchInbox(
                        workspaceId,
                        new ConversationSearchRequest(
                                ConversationView.MINE,
                                null, null, null, null, null, null,
                                null, null, null, 0, 0
                        )
                )
        );
    }

    @Test
    void allView_isAvailableToWorkspaceSupervisor() {
        when(currentUserService.isSuperAdmin()).thenReturn(false);
        when(currentUserService.isSupervisor(workspaceId)).thenReturn(true);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);

        ConversationEntity assigned = conversation(
                channelId, UUID.randomUUID(), UUID.randomUUID(),
                true, "Alice", "alice", "1001",
                "Hello", 0, from
        );
        ConversationEntity unassigned = conversation(
                channelId, UUID.randomUUID(), null,
                true, "Bob", "bob", "1002",
                "Hello", 0, to
        );

        givenConversations(assigned, unassigned);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId, request(ConversationView.ALL)
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(unassigned.getId(), assigned.getId());
        assertThat(response.totalElements()).isEqualTo(2);
    }

    @Test
    void allView_isAvailableToSuperAdmin() {
        when(currentUserService.isSuperAdmin()).thenReturn(true);

        ConversationEntity conversation = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1001",
                "Hello", 0, from
        );

        givenConversations(conversation);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId, request(ConversationView.ALL)
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(conversation.getId());
    }

    @Test
    void activityRange_includesBothBoundaries() {
        givenEmployeeAccess();

        ConversationEntity atStart = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Start", "start", "1001",
                "Hello", 0, from
        );
        ConversationEntity atEnd = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "End", "end", "1002",
                "Hello", 0, to
        );
        ConversationEntity beforeStart = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Before", "before", "1003",
                "Hello", 0, from.minusSeconds(1)
        );
        ConversationEntity afterEnd = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "After", "after", "1004",
                "Hello", 0, to.plusSeconds(1)
        );

        givenConversations(atStart, atEnd, beforeStart, afterEnd);
        givenMapper();

        ConversationSearchRequest searchRequest =
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, null, null, null, null, null,
                        from, to,
                        ConversationSort.LAST_MESSAGE_ASC,
                        0, 20
                );

        ConversationSearchResponse response =
                service.searchInbox(workspaceId, searchRequest);

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(atStart.getId(), atEnd.getId());
        assertThat(response.totalElements()).isEqualTo(2);
    }

    @Test
    void lastMessageAscendingSortsEarlierMessagesFirst() {
        givenEmployeeAccess();

        ConversationEntity newer = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Newer", "newer", "1001",
                "Hello", 0, to
        );
        ConversationEntity older = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Older", "older", "1002",
                "Hello", 0, from
        );

        givenConversations(newer, older);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, null, null, null, null, null,
                        null, null,
                        ConversationSort.LAST_MESSAGE_ASC,
                        0, 20
                )
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(older.getId(), newer.getId());
    }


    @Test
    void negativePageIsNormalizedToZero() {
        givenEmployeeAccess();

        ConversationEntity first = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Alice", "alice", "1001",
                "Hello", 0, from
        );

        ConversationEntity second = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Bob", "bob", "1002",
                "Hello", 0, to
        );

        givenConversations(first, second);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, null, null, null, null, null,
                        null, null,
                        ConversationSort.LAST_MESSAGE_DESC,
                        -1, 1
                )
        );

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(second.getId());
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
    }


    @Test
    void pageSizeIsCappedAtOneHundred() {
        givenEmployeeAccess();

        List<ConversationEntity> conversations =
                new java.util.ArrayList<>();

        for (int i = 0; i < 101; i++) {
            conversations.add(conversation(
                    channelId, UUID.randomUUID(), employeeId,
                    true, "Client " + i, "user" + i,
                    String.valueOf(1000 + i),
                    "Hello", 0, from.plusSeconds(i)
            ));
        }

        givenConversations(
                conversations.toArray(new ConversationEntity[0])
        );
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, null, null, null, null, null,
                        null, null,
                        ConversationSort.LAST_MESSAGE_DESC,
                        0, 101
                )
        );

        assertThat(response.size()).isEqualTo(100);
        assertThat(response.content()).hasSize(100);
        assertThat(response.totalElements()).isEqualTo(101);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
    }


    @Test
    void ascendingSortPlacesNullTimestampsFirst() {
        givenEmployeeAccess();

        ConversationEntity withoutMessage = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "No message", "no_message", "1001",
                null, 0, null
        );

        ConversationEntity withMessage = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Has message", "has_message", "1002",
                "Hello", 0, from
        );

        givenConversations(withoutMessage, withMessage);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, null, null, null, null, null,
                        null, null,
                        ConversationSort.LAST_MESSAGE_ASC,
                        0, 20
                )
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(
                        withoutMessage.getId(),
                        withMessage.getId()
                );
    }


    @Test
    void descendingSortPlacesNullTimestampsLast() {
        givenEmployeeAccess();

        ConversationEntity withoutMessage = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "No message", "no_message", "1001",
                null, 0, null
        );

        ConversationEntity withMessage = conversation(
                channelId, UUID.randomUUID(), employeeId,
                true, "Has message", "has_message", "1002",
                "Hello", 0, from
        );

        givenConversations(withoutMessage, withMessage);
        givenMapper();

        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, null, null, null, null, null,
                        null, null,
                        ConversationSort.LAST_MESSAGE_DESC,
                        0, 20
                )
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(
                        withMessage.getId(),
                        withoutMessage.getId()
                );
    }


    @Test
    void conversationSort_supportsOnlyLastMessageDirections() {
        assertThat(ConversationSort.values())
                .containsExactly(
                        ConversationSort.LAST_MESSAGE_DESC,
                        ConversationSort.LAST_MESSAGE_ASC
                );
    }



    private void givenEmployeeAccess() {
        when(currentUserService.isSuperAdmin()).thenReturn(false);
        when(currentUserService.isSupervisor(workspaceId)).thenReturn(false);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);
    }

    private void assertSearchFinds(
            String search,
            ConversationEntity expected
    ) {
        ConversationSearchResponse response = service.searchInbox(
                workspaceId,
                new ConversationSearchRequest(
                        ConversationView.MINE,
                        null, search, null, null, null, null,
                        null, null, null, 0, 20
                )
        );

        assertThat(response.content())
                .extracting(ConversationDto::id)
                .containsExactly(expected.getId());
    }

    private ConversationSearchRequest request(ConversationView view) {
        return new ConversationSearchRequest(
                view,
                null, null, null, null, null, null,
                null, null,
                ConversationSort.LAST_MESSAGE_DESC,
                0, 20
        );
    }

    private void givenConversations(ConversationEntity... conversations) {
        when(conversationRepository
                .findAllByWorkspaceIdOrderByLastMessageAtDesc(workspaceId))
                .thenReturn(List.of(conversations));
    }

    private void givenMapper() {
        when(conversationMapper.toDto(any(ConversationEntity.class)))
                .thenAnswer(invocation ->
                        dto(invocation.getArgument(0))
                );
    }

    private ConversationDto dto(ConversationEntity conversation) {
        return new ConversationDto(
                conversation.getId(),
                workspaceId,
                conversation.getChannelAccount().getId(),
                conversation.getClientAccount().getId(),
                conversation.getAssignedEmployee() == null
                        ? null
                        : conversation.getAssignedEmployee().getId(),
                conversation.getLastMessageAt(),
                conversation.getLastMessagePreview(),
                conversation.getUnreadCount(),
                null,
                null,
                false,
                null,
                false
        );
    }

    private ConversationEntity conversation(
            UUID channelId,
            UUID clientAccountId,
            UUID assignedEmployeeId,
            boolean linkedToClient,
            String displayName,
            String username,
            String externalId,
            String preview,
            int unreadCount,
            Instant lastMessageAt
    ) {
        ChannelAccountEntity channelAccount =
                new ChannelAccountEntity();
        channelAccount.setId(channelId);

        ClientEntity client = linkedToClient
                ? mock(ClientEntity.class)
                : null;

        ClientAccountEntity clientAccount =
                new ClientAccountEntity(
                        client,
                        ChannelType.TELEGRAM,
                        externalId,
                        username,
                        null,
                        displayName
                );
        clientAccount.setId(clientAccountId);

        EmployeeEntity assignedEmployee = null;

        if (assignedEmployeeId != null) {
            assignedEmployee = new EmployeeEntity();
            assignedEmployee.setId(assignedEmployeeId);
        }

        ConversationEntity conversation =
                new ConversationEntity(
                        null,
                        channelAccount,
                        clientAccount
                );

        conversation.setId(UUID.randomUUID());
        conversation.setAssignedEmployee(assignedEmployee);
        conversation.setLastMessageAt(lastMessageAt);
        conversation.setLastMessagePreview(preview);
        conversation.setUnreadCount(unreadCount);

        return conversation;
    }
}
