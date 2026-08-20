package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.client.ClientDto;
import kit.penny.clientbus.common.dto.client.CreateClientRequest;
import kit.penny.clientbus.common.dto.client.UpdateClientRequest;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
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

import jakarta.persistence.EntityNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientServiceTest {

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private ClientAccountRepository clientAccountRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ClientMapper clientMapper;

    @Mock
    private ClientAccountMapper clientAccountMapper;

    @InjectMocks
    private ClientService clientService;

    private UUID clientId;
    private UUID workspaceId;
    private UUID accountId;

    private WorkspaceEntity workspace;
    private ClientEntity client;
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
        client.setWorkspace(workspace);
        client.setFirstName("Ivan");
        client.setLastName("Ivanov");
        client.setEnabled(true);

        account = new ClientAccountEntity();
        account.setId(accountId);
        account.setClient(null);
    }

    // =========================================================
    // CREATE
    // =========================================================

    @Test
    void createClient_success() {

        CreateClientRequest request =
                new CreateClientRequest(
                        workspaceId,
                        "Ivan",
                        "Ivanov",
                        List.of("8901","192", "5665")
                );

        ClientDto expectedDto = mock(ClientDto.class);

        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.of(workspace));

        when(clientRepository.save(any(ClientEntity.class)))
                .thenReturn(client);

        when(clientMapper.toDto(client))
                .thenReturn(expectedDto);

        ClientDto result =
                clientService.createClient(request);

        assertSame(expectedDto, result);

        verify(workspaceRepository)
                .findById(workspaceId);

        verify(clientRepository)
                .save(any(ClientEntity.class));

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
                        List.of("8901","192", "5665")
                );

        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> clientService.createClient(request)
        );

        verify(clientRepository, never())
                .save(any());

        verify(clientMapper, never())
                .toDto(any());
    }

    // =========================================================
    // GET
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

        assertThrows(
                EntityNotFoundException.class,
                () -> clientService.getClient(clientId)
        );

        verify(clientMapper, never())
                .toDto(any());
    }

    // =========================================================
    // UPDATE
    // =========================================================

    @Test
    void updateClient_success() {

        UpdateClientRequest request =
                new UpdateClientRequest(
                        "Petr",
                        "Petrov",
                        List.of("8901","192", "5665"),
                        true
                );

        ClientDto expectedDto = mock(ClientDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientRepository.save(client))
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

        verify(clientRepository)
                .save(client);

        verify(clientMapper)
                .toDto(client);

        assertEquals(
                "Petr",
                client.getFirstName()
        );

        assertEquals(
                "Petrov",
                client.getLastName()
        );
    }

    @Test
    void updateClient_notFound() {

        UpdateClientRequest request =
                new UpdateClientRequest(
                        "Petr",
                        "Petrov",
                        List.of("8901","192", "5665"),
                        true
                );

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> clientService.updateClient(
                        clientId,
                        request
                )
        );

        verify(clientRepository, never())
                .save(any());
    }

    // =========================================================
    // DELETE
    // =========================================================

    @Test
    void deleteClient_success() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        clientService.deleteClient(clientId);

        verify(clientRepository)
                .findById(clientId);

        verify(clientRepository)
                .delete(client);
    }

    @Test
    void deleteClient_notFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> clientService.deleteClient(clientId)
        );

        verify(clientRepository, never())
                .delete(any());
    }

    // =========================================================
    // CLIENTS WITHOUT ACCOUNTS
    // =========================================================

    @Test
    void getClientsWithoutAccounts() {

        ClientEntity client2 = new ClientEntity();
        client2.setId(UUID.randomUUID());
        client2.setWorkspace(workspace);

        List<ClientEntity> clients =
                List.of(client, client2);

        ClientDto dto1 = mock(ClientDto.class);
        ClientDto dto2 = mock(ClientDto.class);

        when(clientRepository
                .findClientsWithoutAccounts(workspaceId))
                .thenReturn(clients);

        when(clientMapper.toDto(client))
                .thenReturn(dto1);

        when(clientMapper.toDto(client2))
                .thenReturn(dto2);

        List<ClientDto> result =
                clientService.getClientsWithoutAccounts(
                        workspaceId
                );

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
    // CLIENT ACCOUNTS
    // =========================================================

    @Test
    void getClientAccounts() {

        ClientAccountEntity account2 =
                new ClientAccountEntity();

        account2.setId(UUID.randomUUID());
        account2.setClient(client);

        account.setClient(client);

        List<ClientAccountEntity> accounts =
                List.of(account, account2);

        ClientAccountDto dto1 =
                mock(ClientAccountDto.class);

        ClientAccountDto dto2 =
                mock(ClientAccountDto.class);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository
                .findAllByClientId(clientId))
                .thenReturn(accounts);

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
                .findById(clientId);

        verify(clientAccountRepository)
                .findAllByClientId(clientId);
    }

    @Test
    void getClientAccounts_clientNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> clientService.getClientAccounts(clientId)
        );

        verify(
                clientAccountRepository,
                never()
        ).findAllByClientId(any());
    }

    // =========================================================
    // ASSIGN ACCOUNT
    // =========================================================

    @Test
    void addClientAccount_success() {

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

        verify(clientRepository)
                .findById(clientId);

        verify(clientAccountRepository)
                .findById(accountId);

        verify(clientAccountMapper)
                .toDto(account);

        /*
         * Если метод сервиса делает accountRepository.save(account),
         * этот verify нужно оставить.
         */
        verify(clientAccountRepository)
                .save(account);
    }

    @Test
    void addClientAccount_clientNotFound() {

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> clientService.assignClientAccount(
                        clientId,
                        accountId
                )
        );

        verify(
                clientAccountRepository,
                never()
        ).findById(any());

        verify(
                clientAccountRepository,
                never()
        ).save(any());
    }

    @Test
    void addClientAccount_duplicate() {

        account.setClient(client);

        when(clientRepository.findById(clientId))
                .thenReturn(Optional.of(client));

        when(clientAccountRepository.findById(accountId))
                .thenReturn(Optional.of(account));

        ClientAccountDto result =
                clientService.assignClientAccount(
                        clientId,
                        accountId
                );

        assertNotNull(result);

        verify(clientAccountRepository, never())
                .save(any());

        verify(clientAccountMapper)
                .toDto(account);
    }

    @Test
    void addClientAccount_alreadyAssignedToSameClient() {

        account.setClient(client);

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

        verify(clientAccountRepository, never())
                .save(any());

        verify(clientAccountMapper)
                .toDto(account);
    }

    @Test
    void addClientAccount_alreadyAssignedToAnotherClient() {

        UUID anotherClientId = UUID.randomUUID();

        ClientEntity anotherClient =
                new ClientEntity();

        anotherClient.setId(anotherClientId);

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

        verify(clientAccountRepository, never())
                .save(any());
    }
}