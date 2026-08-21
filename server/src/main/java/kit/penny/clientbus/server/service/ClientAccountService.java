package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.dto.clientaccount.CreateClientAccountRequest;
import kit.penny.clientbus.common.dto.clientaccount.UpdateClientAccountRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.mapper.ClientAccountMapper;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ClientAccountService {

    private final ClientAccountRepository clientAccountRepository;
    private final ClientRepository clientRepository;
    private final ClientAccountMapper clientAccountMapper;
    private final CurrentUserService currentUserService;

    public ClientAccountService(
            ClientAccountRepository clientAccountRepository,
            ClientRepository clientRepository,
            ClientAccountMapper clientAccountMapper,
            CurrentUserService currentUserService
    ) {
        this.clientAccountRepository = clientAccountRepository;
        this.clientRepository = clientRepository;
        this.clientAccountMapper = clientAccountMapper;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public ClientAccountDto createClientAccount(
            CreateClientAccountRequest request
    ) {

        ClientEntity client = null;

        if (request.clientId() != null) {

            client = clientRepository
                    .findById(request.clientId())
                    .orElseThrow(() ->
                            new EntityNotFoundException(
                                    "Client not found: "
                                            + request.clientId()
                            )
                    );

            requireClientWorkspaceAccess(client);

        } else {
            requireSuperAdmin();
        }

        if (clientAccountRepository
                .existsByClientIdAndChannelTypeAndExternalId(
                        request.clientId(),
                        request.channelType(),
                        request.externalId()
                )) {

            throw new IllegalStateException(
                    "Account already exists"
            );
        }

        ClientAccountEntity entity =
                clientAccountMapper.toEntity(request);

        entity.setClient(client);

        entity =
                clientAccountRepository.saveAndFlush(entity);

        return clientAccountMapper.toDto(entity);
    }

    @Transactional
    public ClientAccountDto getClientAccount(UUID id) {

        ClientAccountEntity entity =
                clientAccountRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Account not found: " + id
                                )
                        );

        requireAccountAccess(entity);

        return clientAccountMapper.toDto(entity);
    }

    @Transactional
    public List<ClientAccountDto> getClientAccountsByClient(
            UUID clientId
    ) {

        ClientEntity client =
                getClient(clientId);

        requireClientWorkspaceAccess(client);

        return clientAccountRepository
                .findAllByClientId(clientId)
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> getClientAccountsByClientAndType(
            UUID clientId,
            ChannelType channelType
    ) {

        ClientEntity client =
                getClient(clientId);

        requireClientWorkspaceAccess(client);

        return clientAccountRepository
                .findAllByClientIdAndChannelType(
                        clientId,
                        channelType
                )
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> getUnassignedAccounts() {

        requireSuperAdmin();

        return clientAccountRepository
                .findAllByClientIsNull()
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> getUnassignedAccounts(
            ChannelType channelType
    ) {

        requireSuperAdmin();

        return clientAccountRepository
                .findAllByClientIsNullAndChannelType(channelType)
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> searchClientAccounts(
            UUID clientId,
            String query
    ) {

        ClientEntity client =
                getClient(clientId);

        requireClientWorkspaceAccess(client);

        if (query == null || query.isBlank()) {
            return getClientAccountsByClient(clientId);
        }

        return clientAccountRepository
                .searchByClient(
                        clientId,
                        query.trim()
                )
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public ClientAccountDto updateClientAccount(
            UUID id,
            UpdateClientAccountRequest request
    ) {

        ClientAccountEntity entity =
                clientAccountRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Account not found: " + id
                                )
                        );

        requireAccountAccess(entity);

        clientAccountMapper.updateEntity(
                entity,
                request
        );

        return clientAccountMapper.toDto(entity);
    }

    @Transactional
    public void deleteClientAccount(UUID id) {

        ClientAccountEntity entity =
                clientAccountRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Account not found: " + id
                                )
                        );

        requireAccountAccess(entity);

        clientAccountRepository.delete(entity);
    }

    private ClientEntity getClient(UUID clientId) {

        return clientRepository.findById(clientId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Client not found: " + clientId
                        )
                );
    }

    private void requireAccountAccess(
            ClientAccountEntity account
    ) {

        if (account.getClient() == null) {
            requireSuperAdmin();
            return;
        }

        requireClientWorkspaceAccess(
                account.getClient()
        );
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