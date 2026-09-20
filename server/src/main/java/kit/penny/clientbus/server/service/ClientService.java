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
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
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
    private final OrganizationRepository organizationRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final ClientMapper clientMapper;
    private final ClientAccountMapper clientAccountMapper;
    private final CurrentUserService currentUserService;
    private final ConversationRepository conversationRepository;

    public ClientService(
            ClientRepository clientRepository,
            OrganizationRepository organizationRepository,
            ClientAccountRepository clientAccountRepository,
            ClientMapper clientMapper,
            ClientAccountMapper clientAccountMapper,
            CurrentUserService currentUserService,
            ConversationRepository conversationRepository
    ) {
        this.clientRepository = clientRepository;
        this.organizationRepository = organizationRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.clientMapper = clientMapper;
        this.clientAccountMapper = clientAccountMapper;
        this.currentUserService = currentUserService;
        this.conversationRepository = conversationRepository;
    }

    public ClientDto createClient(
            CreateClientRequest request
    ) {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        OrganizationEntity organization =
                organizationRepository.findById(organizationId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Organization not found: "
                                                + organizationId
                                )
                        );

        ClientEntity entity =
                clientMapper.toEntity(
                        request,
                        organization
                );

        ClientEntity saved =
                clientRepository.saveAndFlush(entity);

        return clientMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClients() {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        return clientRepository
                .findAllByOrganizationId(organizationId)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClientDto> searchClients(
            String query
    ) {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        if (query == null || query.isBlank()) {
            return getClients();
        }

        return clientRepository
                .searchClients(
                        organizationId,
                        query.trim()
                )
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

        requireClientOrganizationAccess(entity);

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

        requireClientOrganizationAccess(entity);

        clientMapper.updateEntity(
                entity,
                request
        );

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

        requireClientOrganizationAccess(entity);

        clientRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClientsWithoutAccounts() {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        return clientRepository
                .findClientsWithoutAccounts(organizationId)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    public ClientAccountDto addClientAccount(
            UUID clientId,
            AddClientAccountRequest request
    ) {

        ClientEntity client =
                getAccessibleClient(clientId);

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
                clientAccountRepository.saveAndFlush(
                        account
                );

        return clientAccountMapper.toDto(account);
    }

    public ClientAccountDto assignClientAccount(
            UUID clientId,
            UUID accountId
    ) {

        ClientEntity client =
                getAccessibleClient(clientId);

        ClientAccountEntity account =
                clientAccountRepository.findById(accountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client account not found: "
                                                + accountId
                                )
                        );

        /*
         * Account глобальный.
         *
         * Если он уже связан с Client — переназначение
         * через этот use case запрещено.
         */
        if (account.getClient() != null) {

            if (account.getClient().getId().equals(clientId)) {
                return clientAccountMapper.toDto(account);
            }

            throw new IllegalStateException(
                    "Client account is already assigned to another client"
            );
        }

        /*
         * Для orphan account дополнительно проверяем,
         * что account существует в текущей Organization
         * через Conversation.
         */
        requireAccountInCurrentOrganization(account);

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
                getAccessibleClient(newClientId);

        requireAccountInCurrentOrganization(account);

        account.setClient(newClient);

        return clientAccountMapper.toDto(account);
    }

    public ClientAccountDto unassignClientAccount(
            UUID clientId,
            UUID accountId
    ) {

        ClientEntity client =
                getAccessibleClient(clientId);

        ClientAccountEntity account =
                clientAccountRepository.findById(accountId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client account not found: "
                                                + accountId
                                )
                        );

        if (account.getClient() == null) {
            throw new IllegalStateException(
                    "Client account is not assigned"
            );
        }

        if (!account.getClient()
                .getId()
                .equals(client.getId())) {

            throw new AccessDeniedException(
                    "Client account belongs to another client"
            );
        }

        account.setClient(null);

        return clientAccountMapper.toDto(account);
    }

    @Transactional(readOnly = true)
    public List<ClientAccountDto> getClientAccounts(
            UUID clientId
    ) {

        getAccessibleClient(clientId);

        return clientAccountRepository
                .findAllByClientId(clientId)
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    private ClientEntity getAccessibleClient(
            UUID clientId
    ) {

        ClientEntity client =
                clientRepository.findById(clientId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: "
                                                + clientId
                                )
                        );

        requireClientOrganizationAccess(client);

        return client;
    }

    private void requireClientOrganizationAccess(
            ClientEntity client
    ) {

        UUID currentOrganizationId =
                currentUserService
                        .getCurrentOrganizationId();

        if (!currentOrganizationId.equals(
                client.getOrganization().getId()
        )) {
            throw new AccessDeniedException(
                    "Client is not accessible"
            );
        }
    }

    private void requireAccountInCurrentOrganization(
            ClientAccountEntity account
    ) {

        UUID organizationId =
                currentUserService
                        .getCurrentOrganizationId();

        boolean accessible =
                !conversationRepository
                        .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                                account.getId(),
                                organizationId
                        )
                        .isEmpty();

        if (!accessible) {
            throw new AccessDeniedException(
                    "Client account is not accessible"
            );
        }
    }
}