package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.client.*;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.dto.client.ClientListItemDto;
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
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @Mock
    private IAttachmentStorage attachmentStorage;

    @InjectMocks
    private ClientService clientService;

    private UUID organizationId;
    private UUID clientId;
    private UUID accountId;
    private UUID employeeId;

    private OrganizationEntity organization;
    private ClientEntity client;
    private ClientAccountEntity account;

    @BeforeEach
    void setUp() {

        organizationId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        employeeId = UUID.randomUUID();

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

        /*
         * Client access is organization-scoped.
         *
         * By default all tests run as EMPLOYEE.
         * SUPER_ADMIN-only scenarios override this explicitly.
         */
        lenient()
                .when(currentUserService.isEmployee())
                .thenReturn(true);

        lenient()
                .when(currentUserService.isSuperAdmin())
                .thenReturn(false);

        lenient()
                .when(currentUserService.getCurrentEmployeeId())
                .thenReturn(employeeId);
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

        verify(currentUserService)
                .requireSuperAdmin();

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

        verify(currentUserService)
                .requireSuperAdmin();

        verify(clientRepository)
                .findById(clientId);

        verify(clientRepository, never())
                .delete(any());
    }

    @Test
    void deleteClient_employeeDenied() {

        doThrow(new AccessDeniedException("Super admin access required"))
                .when(currentUserService)
                .requireSuperAdmin();

        assertThrows(
                AccessDeniedException.class,
                () -> clientService.deleteClient(clientId)
        );

        verify(currentUserService).requireSuperAdmin();
        verify(clientRepository, never()).findById(any());
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

        when(currentUserService.isSuperAdmin())
                .thenReturn(true);

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
                .isSuperAdmin();

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

        when(clientAccountRepository
                .findAllByClientIdAndEmployeeId(
                        clientId,
                        employeeId
                ))
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

        verify(currentUserService)
                .getCurrentEmployeeId();

        verify(clientAccountRepository)
                .findAllByClientIdAndEmployeeId(
                        clientId,
                        employeeId
                );

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
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                ))
                .thenReturn(
                        List.of(mock(ConversationEntity.class))
                );

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

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(currentUserService)
                .getCurrentEmployeeId();

        verify(clientAccountRepository)
                .findById(accountId);

        verify(conversationRepository)
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
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
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
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
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
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
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
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
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                ))
                .thenReturn(
                        List.of(mock(ConversationEntity.class))
                );

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

        verify(currentUserService)
                .getCurrentOrganizationId();

        verify(currentUserService)
                .getCurrentEmployeeId();

        verify(conversationRepository)
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
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

        UUID newClientId = UUID.randomUUID();

        ClientEntity newClient = new ClientEntity();
        newClient.setId(newClientId);
        newClient.setOrganization(organization);

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(conversationRepository
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                ))
                .thenReturn(List.of(mock(ConversationEntity.class)));

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

        assertSame(client, account.getClient());

        verify(clientAccountRepository).findById(accountId);
        verify(conversationRepository)
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                );
        verify(clientRepository).findById(newClientId);

        verify(clientAccountMapper, never()).toDto(any());
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

        when(conversationRepository
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                ))
                .thenReturn(List.of());

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

        when(conversationRepository
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                ))
                .thenReturn(
                        List.of(mock(ConversationEntity.class))
                );

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

        verify(currentUserService)
                .getCurrentEmployeeId();

        verify(conversationRepository)
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                );

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

    // =========================================================
    // EMPLOYEE CLIENT VISIBILITY
    // =========================================================

    @Test
    void employeeShouldSeeClientWithoutAccounts() {

        UUID testClientId = UUID.randomUUID();

        ClientEntity clientWithoutAccounts =
                new ClientEntity();

        clientWithoutAccounts.setId(testClientId);
        clientWithoutAccounts.setOrganization(organization);
        clientWithoutAccounts.setFirstName("No");
        clientWithoutAccounts.setLastName("Accounts");
        clientWithoutAccounts.setEnabled(true);

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        when(currentUserService.isSuperAdmin())
                .thenReturn(false);

        when(currentUserService.isEmployee())
                .thenReturn(true);

        when(clientRepository.findAllByOrganizationId(organizationId))
                .thenReturn(List.of(clientWithoutAccounts));

        when(clientRepository.findListAggregatesForEmployee(
                organizationId,
                employeeId,
                List.of(testClientId)
        )).thenReturn(List.of());

        List<ClientListItemDto> result =
                clientService.getClientListItems();

        assertThat(result)
                .hasSize(1);

        assertThat(result.getFirst().id())
                .isEqualTo(testClientId);

        assertThat(result.getFirst().accountCount())
                .isZero();

        assertThat(result.getFirst().lastContactAt())
                .isNull();

        verify(clientRepository)
                .findAllByOrganizationId(organizationId);

        verify(clientRepository)
                .findListAggregatesForEmployee(
                        organizationId,
                        employeeId,
                        List.of(testClientId)
                );
    }

    @Test
    void useClientAccountAvatar_success() {

        String accountAvatarKey = "avatars/telegram/user-123.jpg";
        account.setAvatarUrl(accountAvatarKey);

        ClientDto expectedDto = mock(ClientDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        when(conversationRepository
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                ))
                .thenReturn(List.of(mock(ConversationEntity.class)));

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        ClientDto result =
                clientService.useClientAccountAvatar(clientId, accountId);

        assertSame(expectedDto, result);
        assertEquals(accountAvatarKey, client.getAvatarUrl());
        assertEquals(accountAvatarKey, account.getAvatarUrl());

        verify(clientRepository).saveAndFlush(client);
        verify(clientMapper).toDto(client);
        verifyNoInteractions(attachmentStorage);
    }

    @Test
    void useClientAccountAvatar_accountBelongsToAnotherClient() {

        ClientEntity anotherClient = new ClientEntity();
        anotherClient.setId(UUID.randomUUID());
        anotherClient.setOrganization(organization);

        account.setClient(anotherClient);
        account.setAvatarUrl("avatars/telegram/user-123.jpg");

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        assertThrows(
                IllegalArgumentException.class,
                () -> clientService.useClientAccountAvatar(clientId, accountId)
        );

        verify(clientRepository, never()).saveAndFlush(any());
        verifyNoInteractions(clientMapper);
    }

    @Test
    void useClientAccountAvatar_accountHasNoAvatar() {

        client.setAvatarUrl("avatars/client/current.jpg");
        account.setAvatarUrl(null);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        when(conversationRepository
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        accountId,
                        employeeId
                ))
                .thenReturn(List.of(mock(ConversationEntity.class)));

        assertThrows(
                IllegalStateException.class,
                () -> clientService.useClientAccountAvatar(clientId, accountId)
        );

        assertEquals("avatars/client/current.jpg", client.getAvatarUrl());

        verify(clientRepository, never()).saveAndFlush(any());
        verifyNoInteractions(clientMapper);
    }

    @Test
    void clearClientAvatar_success() {

        client.setAvatarUrl("avatars/client/current.jpg");

        ClientDto expectedDto = mock(ClientDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        ClientDto result = clientService.clearClientAvatar(clientId);

        assertSame(expectedDto, result);
        assertNull(client.getAvatarUrl());

        verify(clientRepository).saveAndFlush(client);
        verifyNoInteractions(attachmentStorage);
    }

    @Test
    void uploadClientAvatar_success() throws IOException {

        String storageKey = "avatars/client/" + clientId + ".jpg";

        MultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3}
        );

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        when(attachmentStorage.store(
                any(),
                any(),
                eq(3L),
                eq("image/jpeg")
        )).thenReturn(
                new StoredAttachmentMetadata(
                        storageKey,
                        "avatar.jpg",
                        "image/jpeg",
                        3L
                )
        );

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        ClientDto expectedDto = mock(ClientDto.class);

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        ClientDto result =
                clientService.uploadClientAvatar(clientId, file);

        assertSame(expectedDto, result);
        assertEquals(storageKey, client.getAvatarUrl());

        verify(attachmentStorage).store(
                any(),
                eq("client-avatar-" + clientId + ".jpg"),
                eq(3L),
                eq("image/jpeg")
        );

        verify(clientRepository).saveAndFlush(client);
    }

    @Test
    void uploadClientAvatar_unsupportedContentType() {

        MultipartFile file = new MockMultipartFile(
                "file",
                "avatar.mov",
                "image/mov",
                new byte[]{1, 2, 3}
        );

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        assertThrows(
                IllegalArgumentException.class,
                () -> clientService.uploadClientAvatar(clientId, file)
        );

        verifyNoInteractions(attachmentStorage);
        verify(clientRepository, never()).saveAndFlush(any());
    }

    @Test
    void uploadClientAvatar_gifPreservesOriginalBytes() throws Exception {
        byte[] gifBytes = new byte[]{
                'G', 'I', 'F', '8', '9', 'a',
                1, 0, 1, 0, (byte) 0x80, 0, 0,
                0, 0, 0, (byte) 255, (byte) 255, (byte) 255,
                '!', (byte) 0xF9, 4, 1, 0, 0, 0, 0,
                ',', 0, 0, 0, 0, 1, 0, 1, 0,
                0, 2, 2, 68, 1, 0, ';'
        };

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.gif",
                "image/gif",
                gifBytes
        );

        String storageKey = "clients/" + clientId + "/avatar.gif";

        when(attachmentStorage.store(
                any(),
                anyString(),
                eq((long) gifBytes.length),
                eq("image/gif")
        )).thenReturn(
                new StoredAttachmentMetadata(
                        storageKey,
                        "avatar.gif",
                        "image/gif",
                        (long) gifBytes.length
                )
        );
        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenAnswer(invocation -> new ClientDto(
                        client.getId(),
                        client.getOrganization().getId(),
                        client.getFirstName(),
                        client.getLastName(),
                        client.getPhoneList(),
                        client.isEnabled(),
                        client.getCreatedAt(),
                        client.getUpdatedAt(),
                        client.getAvatarUrl()
                ));
        ClientDto result = clientService.uploadClientAvatar(
                clientId,
                file
        );

        assertEquals(storageKey, result.avatarUrl());
        assertEquals(storageKey, client.getAvatarUrl());

        ArgumentCaptor<InputStream> inputCaptor =
                ArgumentCaptor.forClass(InputStream.class);

        verify(attachmentStorage).store(
                inputCaptor.capture(),
                anyString(),
                eq((long) gifBytes.length),
                eq("image/gif")
        );

        assertArrayEquals(
                gifBytes,
                inputCaptor.getValue().readAllBytes()
        );
    }

    @Test
    void uploadClientAvatar_doesNotChangeClientAccountAvatar() throws Exception {
        String accountAvatar = "accounts/" + accountId + "/avatar.jpg";
        String clientAvatar = "clients/" + clientId + "/avatar.gif";

        account.setAvatarUrl(accountAvatar);
        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenAnswer(invocation -> new ClientDto(
                        client.getId(),
                        client.getOrganization().getId(),
                        client.getFirstName(),
                        client.getLastName(),
                        client.getPhoneList(),
                        client.isEnabled(),
                        client.getCreatedAt(),
                        client.getUpdatedAt(),
                        client.getAvatarUrl()
                ));

        client.setAvatarUrl(null);

        byte[] gifBytes = new byte[]{
                'G', 'I', 'F', '8', '9', 'a'
        };

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.gif",
                "image/gif",
                gifBytes
        );

        when(attachmentStorage.store(
                any(),
                anyString(),
                eq((long) gifBytes.length),
                eq("image/gif")
        )).thenReturn(
                new StoredAttachmentMetadata(
                        clientAvatar,
                        "avatar.gif",
                        "image/gif",
                        (long) gifBytes.length
                )
        );

        ClientDto result = clientService.uploadClientAvatar(
                clientId,
                file
        );

        assertEquals(clientAvatar, result.avatarUrl());
        assertEquals(clientAvatar, client.getAvatarUrl());

        assertEquals(accountAvatar, account.getAvatarUrl());

        verify(clientAccountRepository, never()).saveAndFlush(account);
    }


    @Test
    void useClientAccountAvatar_doesNotChangeAccountAvatar() {
        String accountAvatar = "accounts/" + accountId + "/avatar.gif";
        String oldClientAvatar = "clients/" + clientId + "/avatar.jpg";

        account.setAvatarUrl(accountAvatar);
        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(currentUserService.getCurrentOrganizationId())
                .thenReturn(organizationId);
        client.setAvatarUrl(oldClientAvatar);

        when(conversationRepository
                .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                        eq(accountId),
                        eq(employeeId)
                ))
                .thenReturn(List.of(mock(ConversationEntity.class)));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenAnswer(invocation -> new ClientDto(
                        client.getId(),
                        client.getOrganization().getId(),
                        client.getFirstName(),
                        client.getLastName(),
                        client.getPhoneList(),
                        client.isEnabled(),
                        client.getCreatedAt(),
                        client.getUpdatedAt(),
                        client.getAvatarUrl()
                ));

        ClientDto result = clientService.useClientAccountAvatar(
                clientId,
                accountId
        );

        assertEquals(accountAvatar, result.avatarUrl());
        assertEquals(accountAvatar, client.getAvatarUrl());

        assertEquals(accountAvatar, account.getAvatarUrl());

        verify(clientAccountRepository, never()).saveAndFlush(account);
    }
}