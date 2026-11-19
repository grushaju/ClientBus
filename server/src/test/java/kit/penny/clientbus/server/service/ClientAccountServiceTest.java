package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.client.ClientLinkCandidateDto;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ClientAccountState;
import kit.penny.clientbus.server.mapper.ClientAccountMapper;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientAccountServiceTest {

    @Mock
    private ClientAccountRepository clientAccountRepository;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private ClientAccountMapper clientAccountMapper;

    @Mock
    private CurrentUserService currentUserService;

    /** Нужен新方法у кандидатов: requireAccountAccess смотрит видимость учётки в диалогах. */
    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private ClientAccountService clientAccountService;

    @Test
    void workspaceAccounts_requiresWorkspaceAccessBeforeReadingAccounts() {
        UUID workspaceId = UUID.randomUUID();
        when(clientAccountRepository.findAllByWorkspaceId(workspaceId)).thenReturn(java.util.List.of());
        assertTrue(clientAccountService.getWorkspaceAccounts(workspaceId).isEmpty());
        var order = inOrder(currentUserService, clientAccountRepository);
        order.verify(currentUserService).requireWorkspaceAccess(workspaceId);
        order.verify(clientAccountRepository).findAllByWorkspaceId(workspaceId);
    }

    @Test
    void workspaceAccounts_filtersBySpecificChannelAccounts() {
        UUID workspaceId = UUID.randomUUID();
        var channelIds = java.util.List.of(UUID.randomUUID(), UUID.randomUUID());
        when(clientAccountRepository.findAllByWorkspaceIdAndChannelAccountIds(workspaceId, channelIds))
                .thenReturn(java.util.List.of());
        assertTrue(clientAccountService.getWorkspaceAccounts(workspaceId, channelIds).isEmpty());
        var order = inOrder(currentUserService, clientAccountRepository);
        order.verify(currentUserService).requireWorkspaceAccess(workspaceId);
        order.verify(clientAccountRepository).findAllByWorkspaceIdAndChannelAccountIds(workspaceId, channelIds);
        verify(clientAccountRepository, never()).findAllByWorkspaceId(any());
    }

    @Test
    void workspaceAccounts_doesNotReadAnInaccessibleWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        doThrow(new org.springframework.security.access.AccessDeniedException("Forbidden"))
                .when(currentUserService).requireWorkspaceAccess(workspaceId);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> clientAccountService.getWorkspaceAccounts(workspaceId));
        verifyNoInteractions(clientAccountRepository);
    }

    @Test
    void getOrCreateForInbound_shouldCreateAccountWithTelegramProfile() {

        String externalId = "123456789";
        String username = "test_user";
        String phone = "+79991234567";
        String displayName = "Test User";

        when(
                clientAccountRepository.findByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        externalId
                )
        ).thenReturn(Optional.empty());

        when(
                clientAccountRepository.saveAndFlush(
                        any(ClientAccountEntity.class)
                )
        ).thenAnswer(invocation -> invocation.getArgument(0));

        ClientAccountEntity result =
                clientAccountService.getOrCreateForInbound(
                        ChannelType.TELEGRAM,
                        externalId,
                        username,
                        phone,
                        displayName,
                        "avatars/telegram/test-user.jpg"
                );

        assertNotNull(result);

        assertEquals(
                ChannelType.TELEGRAM,
                result.getChannelType()
        );
        assertEquals(
                externalId,
                result.getExternalId()
        );
        assertEquals(
                username,
                result.getUsername()
        );
        assertEquals(
                phone,
                result.getPhone()
        );
        assertEquals(
                displayName,
                result.getDisplayName()
        );
        assertEquals(
                ClientAccountState.ACTIVE,
                result.getState()
        );
        assertEquals(
                "avatars/telegram/test-user.jpg",
                result.getAvatarUrl()
        );

        assertNull(result.getClient());

        verify(clientAccountRepository)
                .saveAndFlush(any(ClientAccountEntity.class));
    }

    @Test
    void getOrCreateForInbound_shouldUpdateExistingAccountProfile() {

        UUID accountId = UUID.randomUUID();

        String externalId = "123456789";
        String oldUsername = "old_user";
        String oldPhone = "+79990000000";
        String oldDisplayName = "Old Name";

        String newUsername = "new_user";
        String newPhone = "+79991234567";
        String newDisplayName = "New Name";

        ClientAccountEntity existingAccount =
                new ClientAccountEntity();

        existingAccount.setId(accountId);
        existingAccount.setChannelType(ChannelType.TELEGRAM);
        existingAccount.setExternalId(externalId);
        existingAccount.setUsername(oldUsername);
        existingAccount.setPhone(oldPhone);
        existingAccount.setDisplayName(oldDisplayName);
        existingAccount.setState(ClientAccountState.ACTIVE);

        when(
                clientAccountRepository.findByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        externalId
                )
        ).thenReturn(Optional.of(existingAccount));

        ClientAccountEntity result =
                clientAccountService.getOrCreateForInbound(
                        ChannelType.TELEGRAM,
                        externalId,
                        newUsername,
                        newPhone,
                        newDisplayName,
                        null
                );

        assertSame(existingAccount, result);

        assertEquals(
                externalId,
                result.getExternalId()
        );
        assertEquals(
                newUsername,
                result.getUsername()
        );
        assertEquals(
                newPhone,
                result.getPhone()
        );
        assertEquals(
                newDisplayName,
                result.getDisplayName()
        );

        assertEquals(accountId, result.getId());

        verify(clientAccountRepository, never())
                .save(any(ClientAccountEntity.class));
    }

    @Test
    void updateExistingProfile_shouldUpdateProfileWithoutChangingState() {

        UUID accountId = UUID.randomUUID();

        ClientAccountEntity existingAccount =
                new ClientAccountEntity();

        existingAccount.setId(accountId);
        existingAccount.setChannelType(ChannelType.TELEGRAM);
        existingAccount.setExternalId("123456789");
        existingAccount.setUsername("old_user");
        existingAccount.setPhone("+79990000000");
        existingAccount.setDisplayName("Old Name");
        existingAccount.setState(ClientAccountState.ARCHIVE);

        when(
                clientAccountRepository.findById(accountId)
        ).thenReturn(Optional.of(existingAccount));

        ClientAccountEntity result =
                clientAccountService.updateExistingProfile(
                        accountId,
                        "new_user",
                        "+79991234567",
                        "New Name",
                        null
                );

        assertSame(existingAccount, result);
        assertEquals(accountId, result.getId());
        assertEquals("123456789", result.getExternalId());
        assertEquals("new_user", result.getUsername());
        assertEquals("+79991234567", result.getPhone());
        assertEquals("New Name", result.getDisplayName());
        assertEquals(ClientAccountState.ARCHIVE, result.getState());

        verify(clientAccountRepository, never())
                .save(any(ClientAccountEntity.class));

        verify(clientAccountRepository, never())
                .saveAndFlush(any(ClientAccountEntity.class));
    }

    @Test
    void updateExistingProfile_shouldNotCreateMissingAccount() {

        UUID accountId = UUID.randomUUID();

        when(
                clientAccountRepository.findById(accountId)
        ).thenReturn(Optional.empty());

        assertThrows(
                jakarta.persistence.EntityNotFoundException.class,
                () -> clientAccountService.updateExistingProfile(
                        accountId,
                        "new_user",
                        "+79991234567",
                        "New Name",
                        null
                )
        );

        verify(clientAccountRepository, never())
                .save(any(ClientAccountEntity.class));

        verify(clientAccountRepository, never())
                .saveAndFlush(any(ClientAccountEntity.class));
    }

    @Test
    void clientLinkCandidates_takeTheHintFromTheAccountNotFromTheBrowser() {
        UUID accountId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        var account = new ClientAccountEntity();
        account.setId(accountId);
        account.setPhone("8 (999) 123-45-67");
        when(clientAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(currentUserService.isSuperAdmin()).thenReturn(true);
        when(currentUserService.getCurrentOrganizationId()).thenReturn(organizationId);
        var dialog = new kit.penny.clientbus.server.persistence.entity.ConversationEntity();
        dialog.setId(UUID.randomUUID());
        when(conversationRepository.findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(accountId, organizationId))
                .thenReturn(java.util.List.of(dialog));

        UUID matched = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(clientRepository.searchLinkCandidateIds(eq(organizationId), eq(""), eq("%%"), eq("9991234567"), anyString()))
                .thenReturn(java.util.List.of(other, matched));
        when(clientRepository.findById(other)).thenReturn(Optional.of(client(other, "Иван", "Безтелефонный", java.util.List.of())));
        when(clientRepository.findById(matched)).thenReturn(Optional.of(client(matched, "Пётр", "Другой",
                java.util.List.of("7 999 123-45-67", "+7 888 000 00 00"))));

        var result = clientAccountService.clientLinkCandidates(accountId, "   ");

        // Телефон подсказки сервер берёт из учётной записи: строка поиска браузера — только текст
        // (иначе endpoint подбирал бы людей по номеру). Совпавшая строка идёт первой, даже если
        // база вернула её второй: подсказка затем и нужна, чтобы не листать список.
        assertEquals(java.util.List.of(matched, other), result.stream().map(ClientLinkCandidateDto::id).toList());
        assertTrue(result.getFirst().phoneMatch());
        assertEquals(java.util.List.of("7 999 123-45-67", "+7 888 000 00 00"), result.getFirst().phoneList());
        assertFalse(result.getLast().phoneMatch());
    }

    @Test
    void clientLinkCandidates_withoutAnAccountPhoneThereIsNoHintAtAll() {
        UUID accountId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        var account = new ClientAccountEntity();
        account.setId(accountId);
        when(clientAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(currentUserService.isEmployee()).thenReturn(true);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(UUID.randomUUID());
        when(currentUserService.getCurrentOrganizationId()).thenReturn(organizationId);
        var dialog = new kit.penny.clientbus.server.persistence.entity.ConversationEntity();
        dialog.setId(UUID.randomUUID());
        when(conversationRepository.findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(eq(accountId), any()))
                .thenReturn(java.util.List.of(dialog));
        when(clientRepository.searchLinkCandidateIds(eq(organizationId), eq("анна"), eq("%анна%"), eq(""), anyString()))
                .thenReturn(java.util.List.of());

        assertTrue(clientAccountService.clientLinkCandidates(accountId, " Анна ").isEmpty());
    }

    @Test
    void clientLinkCandidates_deniedWhenTheAccountIsNotVisibleInTheDialogs() {
        UUID accountId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        var account = new ClientAccountEntity();
        account.setId(accountId);
        account.setPhone("79991234567");
        when(clientAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(currentUserService.isSuperAdmin()).thenReturn(true);
        when(currentUserService.getCurrentOrganizationId()).thenReturn(organizationId);
        when(conversationRepository.findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(accountId, organizationId))
                .thenReturn(java.util.List.of());

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> clientAccountService.clientLinkCandidates(accountId, "Иван"));
        verify(clientRepository, never()).searchLinkCandidateIds(any(), any(), any(), any(), any());
    }

    private static kit.penny.clientbus.server.persistence.entity.ClientEntity client(
            UUID id, String firstName, String lastName, java.util.List<String> phones) {
        var entity = new kit.penny.clientbus.server.persistence.entity.ClientEntity();
        entity.setId(id);
        entity.setFirstName(firstName);
        entity.setLastName(lastName);
        entity.setPhoneList(new java.util.ArrayList<>(phones));
        return entity;
    }
}
