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
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ClientAccountRepository clientAccountRepository;

    @Mock
    private ClientMapper clientMapper;

    @Mock
    private ClientAccountMapper clientAccountMapper;

    @InjectMocks
    private ClientService clientService;

    private UUID clientId;
    private UUID workspaceId;
    private UUID accountId;

    private ClientEntity client;
    private WorkspaceEntity workspace;
    private ClientAccountEntity account;

    @BeforeEach
    void setUp() {

        clientId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setName("Test Workspace");

        client = new ClientEntity();
        client.setId(clientId);
        client.setFirstName("Ivan");
        client.setLastName("Ivanov");
        client.setWorkspace(workspace);
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
                        workspaceId,
                        "Ivan",
                        "Ivanov",
                        List.of("+79990000000")
                );

        ClientDto expectedDto = mock(ClientDto.class);

        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.of(workspace));

        when(clientMapper.toEntity(request, workspace))
                .thenReturn(client);

        when(clientRepository.saveAndFlush(client))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        ClientDto result =
                clientService.createClient(request);

        assertSame(expectedDto, result);

        verify(workspaceRepository)
                .findById(workspaceId);

        verify(clientMapper)
                .toEntity(request, workspace);

        verify(clientRepository)
                .saveAndFlush(client);

        verify(clientMapper)
                .toDto(client);
    }

    @Test
    void createClient_workspaceNotFound() {

        CreateClientRequest request =
                new CreateClientRequest(
                        workspaceId,
                        "Ivan",
                        "Ivanov",
                        List.of()
                );

        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.createClient(request)
                );

        assertEquals(
                "Workspace not found: " + workspaceId,
                exception.getMessage()
        );

        verify(workspaceRepository)
                .findById(workspaceId);

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

        ClientDto result =
                clientService.getClient(clientId);

        assertSame(expectedDto, result);

        verify(clientRepository)
                .findById(clientId);

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

        verifyNoInteractions(clientMapper);
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

        ClientDto result =
                clientService.updateClient(
                        clientId,
                        request
                );

        assertSame(expectedDto, result);

        verify(clientRepository)
                .findById(clientId);

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

        verifyNoInteractions(clientMapper);
    }

    // =========================================================
    // DELETE CLIENT
    // =========================================================

    @Test
    void deleteClient_success() {

        when(clientRepository.existsById(clientId))
                .thenReturn(true);

        clientService.deleteClient(clientId);

        verify(clientRepository)
                .existsById(clientId);

        verify(clientRepository)
                .deleteById(clientId);
    }

    @Test
    void deleteClient_notFound() {

        when(clientRepository.existsById(clientId))
                .thenReturn(false);

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
                .existsById(clientId);

        verify(clientRepository, never())
                .deleteById(any());
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
        client2.setWorkspace(workspace);

        ClientDto dto1 = mock(ClientDto.class);
        ClientDto dto2 = mock(ClientDto.class);

        when(clientRepository.findClientsWithoutAccounts(workspaceId))
                .thenReturn(List.of(client, client2));

        when(clientMapper.toDto(client))
                .thenReturn(dto1);

        when(clientMapper.toDto(client2))
                .thenReturn(dto2);

        List<ClientDto> result =
                clientService.getClientsWithoutAccounts(workspaceId);

        assertEquals(2, result.size());
        assertSame(dto1, result.get(0));
        assertSame(dto2, result.get(1));

        verify(clientRepository)
                .findClientsWithoutAccounts(workspaceId);

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

        when(clientRepository.existsById(clientId))
                .thenReturn(true);

        when(clientAccountRepository.findAllByClientId(clientId))
                .thenReturn(List.of(account, account2));

        when(clientAccountMapper.toDto(account))
                .thenReturn(dto1);

        when(clientAccountMapper.toDto(account2))
                .thenReturn(dto2);

        List<ClientAccountDto> result =
                clientService.getClientAccounts(clientId);

        assertEquals(2, result.size());

        assertSame(dto1, result.get(0));
        assertSame(dto2, result.get(1));

        verify(clientRepository)
                .existsById(clientId);

        verify(clientAccountRepository)
                .findAllByClientId(clientId);

        verify(clientAccountMapper)
                .toDto(account);

        verify(clientAccountMapper)
                .toDto(account2);
    }

    @Test
    void getClientAccounts_clientNotFound() {

        when(clientRepository.existsById(clientId))
                .thenReturn(false);

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
                .existsById(clientId);

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

        when(clientAccountRepository.saveAndFlush(any(
                ClientAccountEntity.class
        ))).thenReturn(account);

        when(clientAccountMapper.toDto(account))
                .thenReturn(expectedDto);

        ClientAccountDto result =
                clientService.addClientAccount(
                        clientId,
                        request
                );

        assertSame(expectedDto, result);

        verify(clientRepository)
                .findById(clientId);

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

        when(clientAccountMapper.toDto(unassignedAccount))
                .thenReturn(expectedDto);

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

        verify(clientAccountRepository)
                .findById(accountId);

        verify(clientAccountMapper)
                .toDto(unassignedAccount);

        verify(clientAccountRepository, never())
                .save(any());
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
    }

    @Test
    void assignClientAccount_accountNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
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
    void assignClientAccount_alreadyAssignedToSameClient() {

        ClientAccountDto expectedDto =
                mock(ClientAccountDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientAccountMapper.toDto(account))
                .thenReturn(expectedDto);

        ClientAccountDto result =
                clientService.assignClientAccount(
                        clientId,
                        accountId
                );

        assertSame(expectedDto, result);

        assertSame(
                client,
                account.getClient()
        );

        verify(clientAccountMapper)
                .toDto(account);
    }

    @Test
    void assignClientAccount_alreadyAssignedToAnotherClient() {

        ClientEntity anotherClient =
                new ClientEntity();

        anotherClient.setId(
                UUID.randomUUID()
        );

        account.setClient(anotherClient);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

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

        ClientAccountDto expectedDto =
                mock(ClientAccountDto.class);

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientRepository.findById(newClientId))
                .thenReturn(Optional.of(newClient));

        when(clientAccountMapper.toDto(account))
                .thenReturn(expectedDto);

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

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        when(clientAccountMapper.toDto(account))
                .thenReturn(expectedDto);

        ClientAccountDto result =
                clientService.unassignClientAccount(
                        accountId
                );

        assertSame(expectedDto, result);

        assertNull(account.getClient());

        verify(clientAccountRepository)
                .findById(accountId);

        verify(clientAccountMapper)
                .toDto(account);
    }

    @Test
    void unassignClientAccount_accountNotFound() {

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.empty());

        EntityNotFoundException exception =
                assertThrows(
                        EntityNotFoundException.class,
                        () -> clientService.unassignClientAccount(
                                accountId
                        )
                );

        assertEquals(
                "Client account not found: " + accountId,
                exception.getMessage()
        );

        verify(clientAccountRepository)
                .findById(accountId);

        verifyNoInteractions(clientAccountMapper);
    }
}