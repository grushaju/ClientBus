package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.client.AddClientAccountRequest;
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
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ClientService {

    private final ClientRepository clientRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final ClientMapper clientMapper;
    private final ClientAccountMapper clientAccountMapper;
    private final CurrentUserService currentUserService;

    public ClientService(
            ClientRepository clientRepository,
            WorkspaceRepository workspaceRepository,
            ClientAccountRepository clientAccountRepository,
            ClientMapper clientMapper,
            ClientAccountMapper clientAccountMapper,
            CurrentUserService currentUserService
    ) {
        this.clientRepository = clientRepository;
        this.workspaceRepository = workspaceRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.clientMapper = clientMapper;
        this.clientAccountMapper = clientAccountMapper;
        this.currentUserService = currentUserService;
    }

    public ClientDto createClient(CreateClientRequest request) {

        currentUserService.requireWorkspaceAccess(
                request.workspaceId()
        );

        WorkspaceEntity workspace =
                workspaceRepository
                        .findById(request.workspaceId())
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Workspace not found: "
                                                + request.workspaceId()
                                )
                        );

        ClientEntity entity =
                clientMapper.toEntity(request, workspace);

        ClientEntity saved =
                clientRepository.saveAndFlush(entity);

        return clientMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClientsByWorkspace(
            UUID workspaceId
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return clientRepository
                .findAllByWorkspaceId(workspaceId)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClientDto> searchClients(
            UUID workspaceId,
            String query
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return clientRepository
                .searchClients(workspaceId, query)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDto getClient(UUID id) {

        ClientEntity entity =
                clientRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: " + id
                                )
                        );

        requireClientWorkspaceAccess(entity);

        return clientMapper.toDto(entity);
    }

    public ClientDto updateClient(
            UUID id,
            UpdateClientRequest request
    ) {

        ClientEntity entity =
                clientRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: " + id
                                )
                        );

        requireClientWorkspaceAccess(entity);

        clientMapper.updateEntity(entity, request);

        ClientEntity saved =
                clientRepository.saveAndFlush(entity);

        return clientMapper.toDto(saved);
    }

    public void deleteClient(UUID id) {

        ClientEntity entity =
                clientRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: " + id
                                )
                        );

        requireClientWorkspaceAccess(entity);

        clientRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClientsWithoutAccounts(
            UUID workspaceId
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return clientRepository
                .findClientsWithoutAccounts(workspaceId)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    public ClientAccountDto addClientAccount(
            UUID clientId,
            AddClientAccountRequest request
    ) {

        ClientEntity client =
                clientRepository.findById(clientId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: " + clientId
                                )
                        );

        requireClientWorkspaceAccess(client);

        boolean alreadyExists =
                clientAccountRepository
                        .existsByChannelTypeAndExternalId(
                                request.channelType(),
                                request.externalId()
                        );

        if (alreadyExists) {
            throw new IllegalStateException(
                    "Client account already exists: "
                            + request.channelType()
                            + " / "
                            + request.externalId()
            );
        }

        ClientAccountEntity account =
                new ClientAccountEntity();

        account.setClient(client);
        account.setChannelType(request.channelType());
        account.setExternalId(request.externalId());
        account.setUsername(request.username());
        account.setPhone(request.phone());
        account.setDisplayName(request.displayName());

        account =
                clientAccountRepository.saveAndFlush(account);

        return clientAccountMapper.toDto(account);
    }

    public ClientAccountDto assignClientAccount(
            UUID clientId,
            UUID accountId
    ) {

        ClientEntity client =
                clientRepository.findById(clientId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: " + clientId
                                )
                        );

        requireClientWorkspaceAccess(client);

        ClientAccountEntity account =
                clientAccountRepository.findById(accountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client account not found: "
                                                + accountId
                                )
                        );

        if (account.getClient() != null) {

            if (account.getClient().getId().equals(clientId)) {
                return clientAccountMapper.toDto(account);
            }

            throw new IllegalStateException(
                    "Client account is already assigned to another client"
            );
        }

        /*
         * Unassigned account has no workspace.
         * Access is controlled by the target client's workspace.
         */
        account.setClient(client);

        return clientAccountMapper.toDto(account);
    }

    public ClientAccountDto reassignClientAccount(
            UUID accountId,
            UUID newClientId
    ) {

        ClientAccountEntity account =
                clientAccountRepository.findById(accountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client account not found: "
                                                + accountId
                                )
                        );

        ClientEntity newClient =
                clientRepository.findById(newClientId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: "
                                                + newClientId
                                )
                        );

        requireClientWorkspaceAccess(newClient);

        account.setClient(newClient);

        return clientAccountMapper.toDto(account);
    }

    public ClientAccountDto unassignClientAccount(
            UUID accountId
    ) {

        ClientAccountEntity account =
                clientAccountRepository.findById(accountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client account not found: "
                                                + accountId
                                )
                        );

        if (account.getClient() != null) {
            requireClientWorkspaceAccess(
                    account.getClient()
            );
        } else {
            requireSuperAdmin();
        }

        account.setClient(null);

        return clientAccountMapper.toDto(account);
    }

    @Transactional(readOnly = true)
    public List<ClientAccountDto> getClientAccounts(
            UUID clientId
    ) {

        ClientEntity client =
                clientRepository.findById(clientId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: " + clientId
                                )
                        );

        requireClientWorkspaceAccess(client);

        return clientAccountRepository
                .findAllByClientId(clientId)
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    private void requireClientWorkspaceAccess(
            ClientEntity client
    ) {

        if (client.getWorkspace() == null) {
            throw new IllegalStateException(
                    "Client has no workspace: " + client.getId()
            );
        }

        currentUserService.requireWorkspaceAccess(
                client.getWorkspace().getId()
        );
    }

    private void requireSuperAdmin() {

        if (!currentUserService.isSuperAdmin()) {
            throw new AccessDeniedException(
                    "SUPER_ADMIN role is required"
            );
        }
    }
}