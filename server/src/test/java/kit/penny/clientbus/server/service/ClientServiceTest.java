package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.client.AddClientAccountRequest;
import kit.penny.clientbus.common.dto.client.ClientDto;
import kit.penny.clientbus.common.dto.client.CreateClientRequest;
import kit.penny.clientbus.common.dto.client.UpdateClientRequest;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.mapper.ClientAccountMapper;
import kit.penny.clientbus.server.mapper.ClientMapper;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientServiceTest {

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private ClientAccountRepository clientAccountRepository;

    @Mock
    private ClientMapper clientMapper;

    @Mock
    private ClientAccountMapper clientAccountMapper;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private ClientService clientService;

    private UUID organizationId;
    private UUID clientId;
    private UUID accountId;

    private OrganizationEntity organization;
    private ClientEntity client;
    private ClientAccountEntity account;

    @BeforeEach
    void setUp() {

        organizationId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        organization = new OrganizationEntity();
        organization.setId(organizationId);
        organization.setName("Test Organization");

        client = new ClientEntity();
        client.setId(clientId);
        client.setFirstName("Ivan");
        client.setLastName("Ivanov");
        client.setOrganization(organization);
        client.setEnabled(true);

        account = new ClientAccountEntity();
        account.setId(accountId);
        account.setClient(client);
        account.setChannelType(ChannelType.TELEGRAM);
        account.setExternalId("123456789");
        account.setUsername("ivan");
        account.setPhone("+79990000000");
        account.setDisplayName("Ivan");

    }

    // =========================================================
    // CREATE CLIENT
    // =========================================================

    @Test
    void createClient_success() {

        CreateClientRequest request =
                new CreateClientRequest(
                        "Ivan",
                        "Ivanov",
                        List.of("+79990000000")
                );

        ClientDto expectedDto = mock(ClientDto.class);

        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.of(organization));

        when(clientMapper.toEntity(request, organization))
                .thenReturn(client);

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientDto result =
                clientService.createClient(request);

        assertSame(expectedDto, result);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(organizationRepository)
                .findById(organizationId);

        verify(clientMapper)
                .toEntity(request, organization);

        verify(clientRepository)
                .saveAndFlush(client);

        verify(clientMapper)
                .toDto(client);
    }

    @Test
    void createClient_organizationNotFound() {

        CreateClientRequest request =
                new CreateClientRequest(
                        "Ivan",
                        "Ivanov",
                        List.of()
                );

        when(organizationRepository.findById(organizationId))
                .thenReturn(Optional.empty());
        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);
        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.createClient(request)
                );

        assertEquals(
                "Organization not found: " + organizationId,
                exception.getMessage()
        );

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(organizationRepository)
                .findById(organizationId);

        verifyNoInteractions(clientMapper);
        verifyNoInteractions(clientRepository);
    }

    // =========================================================
    // GET CLIENT
    // =========================================================

    @Test
    void getClient_success() {

        ClientDto expectedDto = mock(ClientDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientDto result =
                clientService.getClient(clientId);

        assertSame(expectedDto, result);

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientMapper)
                .toDto(client);
    }

    @Test
    void getClient_notFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.getClient(clientId)
                );

        assertEquals(
                "Client not found: " + clientId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verifyNoInteractions(currentUserService);
        verifyNoInteractions(clientMapper);
    }

    @Test
    void getClient_fromAnotherOrganization_denied() {

        OrganizationEntity anotherOrganization =
                new OrganizationEntity();

        anotherOrganization.setId(UUID.randomUUID());
        anotherOrganization.setName("Another Organization");

        client.setOrganization(anotherOrganization);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        assertThrows(
                AccessDeniedException.class,
                () -> clientService.getClient(clientId)
        );

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientMapper, never())
                .toDto(any());
    }

    // =========================================================
    // UPDATE CLIENT
    // =========================================================

    @Test
    void updateClient_success() {

        UpdateClientRequest request =
                new UpdateClientRequest(
                        "Petr",
                        "Petrov",
                        List.of("+79991112233"),
                        false
                );

        ClientDto expectedDto = mock(ClientDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientDto result =
                clientService.updateClient(
                        clientId,
                        request
                );

        assertSame(expectedDto, result);

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientMapper)
                .updateEntity(client, request);

        verify(clientRepository)
                .saveAndFlush(client);

        verify(clientMapper)
                .toDto(client);
    }

    @Test
    void updateClient_notFound() {

        UpdateClientRequest request =
                new UpdateClientRequest(
                        "Petr",
                        "Petrov",
                        List.of(),
                        true
                );

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.updateClient(
                                clientId,
                                request
                        )
                );

        assertEquals(
                "Client not found: " + clientId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verifyNoInteractions(currentUserService);
        verifyNoInteractions(clientMapper);
    }

    // =========================================================
    // DELETE CLIENT
    // =========================================================

    @Test
    void deleteClient_success() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        clientService.deleteClient(clientId);

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientRepository)
                .delete(client);
    }

    @Test
    void deleteClient_notFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.deleteClient(clientId)
                );

        assertEquals(
                "Client not found: " + clientId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verifyNoInteractions(currentUserService);

        verify(clientRepository, never())
                .delete(any());
    }

    // =========================================================
    // GET CLIENTS
    // =========================================================

    @Test
    void getClients_success() {

        ClientEntity client2 = new ClientEntity();
        client2.setId(UUID.randomUUID());
        client2.setFirstName("Petr");
        client2.setLastName("Petrov");
        client2.setOrganization(organization);

        ClientDto dto1 = mock(ClientDto.class);
        ClientDto dto2 = mock(ClientDto.class);

        when(clientRepository.findAllByOrganizationId(organizationId))
                .thenReturn(List.of(client, client2));

        when(clientMapper.toDto(client))
                .thenReturn(dto1);

        when(clientMapper.toDto(client2))
                .thenReturn(dto2);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        List<ClientDto> result =
                clientService.getClients();

        assertEquals(2, result.size());
        assertSame(dto1, result.get(0));
        assertSame(dto2, result.get(1));

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientRepository)
                .findAllByOrganizationId(organizationId);

        verify(clientMapper)
                .toDto(client);

        verify(clientMapper)
                .toDto(client2);
    }

    // =========================================================
    // SEARCH CLIENTS
    // =========================================================

    @Test
    void searchClients_success() {

        ClientDto expectedDto =
                mock(ClientDto.class);

        when(clientRepository.searchClients(
                organizationId,
                "ivan"
        )).thenReturn(List.of(client));

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        List<ClientDto> result =
                clientService.searchClients("ivan");

        assertEquals(1, result.size());
        assertSame(expectedDto, result.getFirst());

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientRepository)
                .searchClients(
                        organizationId,
                        "ivan"
                );

        verify(clientMapper)
                .toDto(client);
    }

    @Test
    void searchClients_blankQuery_returnsAllClients() {

        ClientDto expectedDto =
                mock(ClientDto.class);

        when(clientRepository.findAllByOrganizationId(organizationId))
                .thenReturn(List.of(client));

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        List<ClientDto> result =
                clientService.searchClients("   ");

        assertEquals(1, result.size());
        assertSame(expectedDto, result.getFirst());

        verify(clientRepository)
                .findAllByOrganizationId(organizationId);

        verify(clientRepository, never())
                .searchClients(any(), any());
    }

    // =========================================================
    // CLIENTS WITHOUT ACCOUNTS
    // =========================================================

    @Test
    void getClientsWithoutAccounts() {

        ClientEntity client2 = new ClientEntity();
        client2.setId(UUID.randomUUID());
        client2.setFirstName("Petr");
        client2.setLastName("Petrov");
        client2.setOrganization(organization);

        ClientDto dto1 = mock(ClientDto.class);
        ClientDto dto2 = mock(ClientDto.class);

        when(clientRepository.findClientsWithoutAccounts(organizationId))
                .thenReturn(List.of(client, client2));

        when(clientMapper.toDto(client))
                .thenReturn(dto1);

        when(clientMapper.toDto(client2))
                .thenReturn(dto2);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        List<ClientDto> result =
                clientService.getClientsWithoutAccounts();

        assertEquals(2, result.size());
        assertSame(dto1, result.get(0));
        assertSame(dto2, result.get(1));

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientRepository)
                .findClientsWithoutAccounts(organizationId);

        verify(clientMapper)
                .toDto(client);

        verify(clientMapper)
                .toDto(client2);
    }

    // =========================================================
    // GET CLIENT ACCOUNTS
    // =========================================================

    @Test
    void getClientAccounts() {

        ClientAccountEntity account2 =
                new ClientAccountEntity();

        account2.setId(UUID.randomUUID());
        account2.setClient(client);
        account2.setChannelType(ChannelType.VK);
        account2.setExternalId("vk-123");

        ClientAccountDto dto1 =
                mock(ClientAccountDto.class);

        ClientAccountDto dto2 =
                mock(ClientAccountDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findAllByClientId(clientId))
                .thenReturn(List.of(account, account2));

        when(clientAccountMapper.toDto(account))
                .thenReturn(dto1);

        when(clientAccountMapper.toDto(account2))
                .thenReturn(dto2);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        List<ClientAccountDto> result =
                clientService.getClientAccounts(clientId);

        assertEquals(2, result.size());

        assertSame(dto1, result.get(0));
        assertSame(dto2, result.get(1));

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientAccountRepository)
                .findAllByClientId(clientId);

        verify(clientAccountMapper)
                .toDto(account);

        verify(clientAccountMapper)
                .toDto(account2);
    }

    @Test
    void getClientAccounts_clientNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.getClientAccounts(clientId)
                );

        assertEquals(
                "Client not found: " + clientId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verifyNoInteractions(currentUserService);
        verifyNoInteractions(clientAccountRepository);
        verifyNoInteractions(clientAccountMapper);
    }

    // =========================================================
    // ADD CLIENT ACCOUNT
    // =========================================================

    @Test
    void addClientAccount_success() {

        AddClientAccountRequest request =
                new AddClientAccountRequest(
                        ChannelType.TELEGRAM,
                        "telegram-123",
                        "ivan",
                        "+79990000000",
                        "Ivan"
                );

        ClientAccountDto expectedDto =
                mock(ClientAccountDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository
                .existsByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        "telegram-123"
                ))
                .thenReturn(false);

        when(clientAccountRepository.saveAndFlush(
                any(ClientAccountEntity.class)
        )).thenReturn(account);

        when(clientAccountMapper.toDto(account))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientAccountDto result =
                clientService.addClientAccount(
                        clientId,
                        request
                );

        assertSame(expectedDto, result);

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientAccountRepository)
                .existsByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        "telegram-123"
                );

        verify(clientAccountRepository)
                .saveAndFlush(any(ClientAccountEntity.class));

        verify(clientAccountMapper)
                .toDto(account);
    }

    @Test
    void addClientAccount_clientNotFound() {

        AddClientAccountRequest request =
                new AddClientAccountRequest(
                        ChannelType.TELEGRAM,
                        "telegram-123",
                        "ivan",
                        null,
                        "Ivan"
                );

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.addClientAccount(
                                clientId,
                                request
                        )
                );

        assertEquals(
                "Client not found: " + clientId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verifyNoInteractions(currentUserService);
        verifyNoInteractions(clientAccountRepository);
        verifyNoInteractions(clientAccountMapper);
    }

    @Test
    void addClientAccount_duplicate() {

        AddClientAccountRequest request =
                new AddClientAccountRequest(
                        ChannelType.TELEGRAM,
                        "telegram-123",
                        "ivan",
                        null,
                        "Ivan"
                );

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository
                .existsByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        "telegram-123"
                ))
                .thenReturn(true);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> clientService.addClientAccount(
                                clientId,
                                request
                        )
                );

        assertEquals(
                "Client account already exists: TELEGRAM / telegram-123",
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientAccountRepository)
                .existsByChannelTypeAndExternalId(
                        ChannelType.TELEGRAM,
                        "telegram-123"
                );

        verify(clientAccountRepository, never())
                .saveAndFlush(any());

        verifyNoInteractions(clientAccountMapper);
    }

    // =========================================================
    // ASSIGN EXISTING ACCOUNT
    // =========================================================

    @Test
    void assignClientAccount_success() {

        ClientAccountEntity unassignedAccount =
                new ClientAccountEntity();

        unassignedAccount.setId(accountId);
        unassignedAccount.setClient(null);
        unassignedAccount.setChannelType(
                ChannelType.TELEGRAM
        );
        unassignedAccount.setExternalId(
                "telegram-123"
        );

        ClientAccountDto expectedDto =
                mock(ClientAccountDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(unassignedAccount));

        when(conversationRepository
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        accountId,
                        organizationId
                ))
                .thenReturn(List.of(mock(ConversationEntity.class)));

        when(clientAccountMapper.toDto(unassignedAccount))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientAccountDto result =
                clientService.assignClientAccount(
                        clientId,
                        accountId
                );

        assertSame(expectedDto, result);

        assertSame(
                client,
                unassignedAccount.getClient()
        );

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService, times(2))
                .getCurrentOrganizationId();

        verify(clientAccountRepository)
                .findById(accountId);

        verify(conversationRepository)
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        accountId,
                        organizationId
                );

        verify(clientAccountMapper)
                .toDto(unassignedAccount);
    }

    @Test
    void assignClientAccount_clientNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.assignClientAccount(
                                clientId,
                                accountId
                        )
                );

        assertEquals(
                "Client not found: " + clientId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verifyNoInteractions(clientAccountRepository);
        verifyNoInteractions(conversationRepository);
    }

    @Test
    void assignClientAccount_accountNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.empty());

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.assignClientAccount(
                                clientId,
                                accountId
                        )
                );

        assertEquals(
                "Client account not found: " + accountId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientAccountRepository)
                .findById(accountId);

        verifyNoInteractions(conversationRepository);
        verifyNoInteractions(clientAccountMapper);
    }

    @Test
    void assignClientAccount_alreadyAssignedToSameClient() {

        ClientAccountDto accountDto =
                mock(ClientAccountDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientAccountMapper.toDto(account))
                .thenReturn(accountDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientAccountDto result =
                clientService.assignClientAccount(
                        clientId,
                        accountId
                );

        assertSame(accountDto, result);

        assertSame(
                client,
                account.getClient()
        );

        verify(clientAccountMapper)
                .toDto(account);

        verify(conversationRepository, never())
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        any(),
                        any()
                );
    }

    @Test
    void assignClientAccount_alreadyAssignedToAnotherClient() {

        ClientEntity anotherClient =
                new ClientEntity();

        anotherClient.setId(UUID.randomUUID());
        anotherClient.setOrganization(organization);

        account.setClient(anotherClient);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> clientService.assignClientAccount(
                                clientId,
                                accountId
                        )
                );

        assertEquals(
                "Client account is already assigned to another client",
                exception.getMessage()
        );

        verify(clientAccountMapper, never())
                .toDto(any());

        verify(conversationRepository, never())
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        any(),
                        any()
                );
    }

    @Test
    void assignClientAccount_accountNotInCurrentOrganization() {

        ClientAccountEntity unassignedAccount =
                new ClientAccountEntity();

        unassignedAccount.setId(accountId);
        unassignedAccount.setClient(null);
        unassignedAccount.setChannelType(ChannelType.TELEGRAM);
        unassignedAccount.setExternalId("telegram-123");

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(unassignedAccount));

        when(conversationRepository
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        accountId,
                        organizationId
                ))
                .thenReturn(List.of());

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        AccessDeniedException exception =
                assertThrows(
                        AccessDeniedException.class,
                        () -> clientService.assignClientAccount(
                                clientId,
                                accountId
                        )
                );

        assertEquals(
                "Client account is not accessible",
                exception.getMessage()
        );

        assertNull(unassignedAccount.getClient());

        verify(clientAccountMapper, never())
                .toDto(any());
    }

    // =========================================================
    // REASSIGN ACCOUNT
    // =========================================================

    @Test
    void reassignClientAccount_success() {

        UUID newClientId =
                UUID.randomUUID();

        ClientEntity newClient =
                new ClientEntity();

        newClient.setId(newClientId);
        newClient.setOrganization(organization);

        ClientAccountDto expectedDto =
                mock(ClientAccountDto.class);

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientRepository.findById(newClientId))
                .thenReturn(Optional.of(newClient));

        when(conversationRepository
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        accountId,
                        organizationId
                ))
                .thenReturn(List.of(mock(ConversationEntity.class)));

        when(clientAccountMapper.toDto(account))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientAccountDto result =
                clientService.reassignClientAccount(
                        accountId,
                        newClientId
                );

        assertSame(expectedDto, result);

        assertSame(
                newClient,
                account.getClient()
        );

        verify(clientAccountRepository)
                .findById(accountId);

        verify(clientRepository)
                .findById(newClientId);

        verify(currentUserService, times(2))
                .getCurrentOrganizationId();

        verify(conversationRepository)
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        accountId,
                        organizationId
                );

        verify(clientAccountMapper)
                .toDto(account);
    }

    @Test
    void reassignClientAccount_accountNotFound() {

        UUID newClientId =
                UUID.randomUUID();

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.reassignClientAccount(
                                accountId,
                                newClientId
                        )
                );

        assertEquals(
                "Client account not found: " + accountId,
                exception.getMessage()
        );

        verify(clientAccountRepository)
                .findById(accountId);

        verifyNoInteractions(clientRepository);
        verifyNoInteractions(currentUserService);
        verifyNoInteractions(conversationRepository);
    }

    @Test
    void reassignClientAccount_clientNotFound() {

        UUID newClientId =
                UUID.randomUUID();

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientRepository.findById(newClientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.reassignClientAccount(
                                accountId,
                                newClientId
                        )
                );

        assertEquals(
                "Client not found: " + newClientId,
                exception.getMessage()
        );

        verify(clientAccountRepository)
                .findById(accountId);

        verify(clientRepository)
                .findById(newClientId);

        verifyNoInteractions(conversationRepository);
        verify(clientAccountMapper, never())
                .toDto(any());
    }

    @Test
    void reassignClientAccount_accountNotInCurrentOrganization() {

        UUID newClientId =
                UUID.randomUUID();

        ClientEntity newClient =
                new ClientEntity();

        newClient.setId(newClientId);
        newClient.setOrganization(organization);

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientRepository.findById(newClientId))
                .thenReturn(Optional.of(newClient));

        when(conversationRepository
                .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                        accountId,
                        organizationId
                ))
                .thenReturn(List.of());

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        AccessDeniedException exception =
                assertThrows(
                        AccessDeniedException.class,
                        () -> clientService.reassignClientAccount(
                                accountId,
                                newClientId
                        )
                );

        assertEquals(
                "Client account is not accessible",
                exception.getMessage()
        );

        assertSame(client, account.getClient());

        verify(clientAccountMapper, never())
                .toDto(any());
    }

    // =========================================================
    // UNASSIGN ACCOUNT
    // =========================================================

    @Test
    void unassignClientAccount_success() {

        ClientAccountDto expectedDto =
                mock(ClientAccountDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientAccountMapper.toDto(account))
                .thenReturn(expectedDto);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        ClientAccountDto result =
                clientService.unassignClientAccount(
                        clientId,
                        accountId
                );

        assertSame(expectedDto, result);

        assertNull(account.getClient());

        verify(clientRepository)
                .findById(clientId);

        verify(clientAccountRepository)
                .findById(accountId);

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(clientAccountMapper)
                .toDto(account);
    }

    @Test
    void unassignClientAccount_clientNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.unassignClientAccount(
                                clientId,
                                accountId
                        )
                );

        assertEquals(
                "Client not found: " + clientId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verifyNoInteractions(
                clientAccountRepository,
                clientAccountMapper
        );
    }

    @Test
    void unassignClientAccount_accountNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.empty());

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.unassignClientAccount(
                                clientId,
                                accountId
                        )
                );

        assertEquals(
                "Client account not found: " + accountId,
                exception.getMessage()
        );

        verify(clientRepository)
                .findById(clientId);

        verify(clientAccountRepository)
                .findById(accountId);

        verifyNoInteractions(clientAccountMapper);
    }

    @Test
    void unassignClientAccount_accountBelongsToAnotherClient() {

        ClientEntity anotherClient =
                new ClientEntity();

        anotherClient.setId(UUID.randomUUID());
        anotherClient.setOrganization(organization);

        account.setClient(anotherClient);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        AccessDeniedException exception =
                assertThrows(
                        AccessDeniedException.class,
                        () -> clientService.unassignClientAccount(
                                clientId,
                                accountId
                        )
                );

        assertEquals(
                "Client account belongs to another client",
                exception.getMessage()
        );

        assertSame(
                anotherClient,
                account.getClient()
        );

        verify(clientAccountMapper, never())
                .toDto(any());
    }
}