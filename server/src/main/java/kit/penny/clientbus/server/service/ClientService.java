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

    public ClientService(
            ClientRepository clientRepository,
            WorkspaceRepository workspaceRepository, ClientAccountRepository clientAccountRepository,
            ClientMapper clientMapper, ClientAccountMapper clientAccountMapper
    ) {
        this.clientRepository = clientRepository;
        this.workspaceRepository = workspaceRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.clientMapper = clientMapper;
        this.clientAccountMapper = clientAccountMapper;
    }

    public ClientDto createClient(CreateClientRequest request) {

        WorkspaceEntity workspace = workspaceRepository
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
    public List<ClientDto> getClientsByWorkspace(UUID workspaceId) {

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

        return clientRepository
                .searchClients(workspaceId, query)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDto getClient(UUID id) {

        ClientEntity entity = clientRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Client not found: " + id
                        )
                );

        return clientMapper.toDto(entity);
    }

    @Transactional
    public ClientDto updateClient(
            UUID id,
            UpdateClientRequest request
    ) {

        ClientEntity entity = clientRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Client not found: " + id
                        )
                );

        clientMapper.updateEntity(entity, request);

        ClientEntity saved = clientRepository.saveAndFlush(entity);

        return clientMapper.toDto(saved);
    }

    @Transactional
    public void deleteClient(UUID id) {

        if (!clientRepository.existsById(id)) {
            throw new EntityNotFoundException(
                    "Client not found: " + id
            );
        }

        clientRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClientsWithoutAccounts(
            UUID workspaceId
    ) {

        return clientRepository
                .findClientsWithoutAccounts(workspaceId)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional
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
        account.setChannelType(
                request.channelType()
        );
        account.setExternalId(
                request.externalId()
        );
        account.setUsername(
                request.username()
        );
        account.setPhone(
                request.phone()
        );
        account.setDisplayName(
                request.displayName()
        );

        account =
                clientAccountRepository.saveAndFlush(account);

        return clientAccountMapper.toDto(account);
    }

    @Transactional
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

        account.setClient(client);

        return clientAccountMapper.toDto(account);
    }

    @Transactional
    public ClientAccountDto reassignClientAccount(
            UUID accountId,
            UUID newClientId
    ) {
        ClientAccountEntity account =
                clientAccountRepository.findById(accountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client account not found: " + accountId
                                )
                        );

        ClientEntity newClient =
                clientRepository.findById(newClientId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: " + newClientId
                                )
                        );

        account.setClient(newClient);

        return clientAccountMapper.toDto(account);
    }

    @Transactional
    public ClientAccountDto unassignClientAccount(
            UUID accountId
    ) {
        ClientAccountEntity account =
                clientAccountRepository.findById(accountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client account not found: " + accountId
                                )
                        );

        account.setClient(null);

        return clientAccountMapper.toDto(account);
    }

    @Transactional(readOnly = true)
    public List<ClientAccountDto> getClientAccounts(
            UUID clientId
    ) {

        if (!clientRepository.existsById(clientId)) {
            throw new EntityNotFoundException(
                    "Client not found: " + clientId
            );
        }

        return clientAccountRepository
                .findAllByClientId(clientId)
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

}