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
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.common.dto.client.ClientListItemDto;
import kit.penny.clientbus.server.persistence.repository.ClientRepository.ClientListAggregateProjection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ClientService {

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final ConversationRepository conversationRepository;
    private final ClientMapper clientMapper;
    private final ClientAccountMapper clientAccountMapper;
    private final CurrentUserService currentUserService;
    private final ConversationMapper conversationMapper;

    public ClientService(
            ClientRepository clientRepository,
            OrganizationRepository organizationRepository,
            ClientAccountRepository clientAccountRepository,
            ConversationRepository conversationRepository,
            ClientMapper clientMapper,
            ClientAccountMapper clientAccountMapper,
            CurrentUserService currentUserService,
            ConversationMapper conversationMapper
    ) {
        this.clientRepository = clientRepository;
        this.organizationRepository = organizationRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.conversationRepository = conversationRepository;
        this.clientMapper = clientMapper;
        this.clientAccountMapper = clientAccountMapper;
        this.currentUserService = currentUserService;
        this.conversationMapper = conversationMapper;
    }

    /*
     * ---------------------------------------------------------
     * Client CRUD
     * ---------------------------------------------------------
     */

    /**
     * Создать Client в текущей Organization.
     *
     * Используется как общий application use case.
     *
     * ВАЖНО:
     * для EMPLOYEE основной сценарий создания клиента —
     * createClientFromConversation(), который сразу
     * связывает текущий ClientAccount.
     */
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

    /**
     * Создать Client из существующего Conversation.
     * <p>
     * Conversation сам определяет ClientAccount.
     * <p>
     * Поэтому клиентский FE не может подменить accountId.
     * <p>
     * EMPLOYEE:
     *   Conversation должен быть ему доступен.
     * <p>
     * SUPER_ADMIN:
     *   Conversation должен находиться в его Organization.
     * <p>
     * ClientAccount должен быть orphan.
     */
    public ClientDto createClientFromConversation(
            UUID conversationId,
            CreateClientRequest request
    ) {

        ConversationEntity conversation =
                conversationRepository.findById(conversationId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Conversation not found: "
                                                + conversationId
                                )
                        );

        currentUserService.requireConversationAccess(
                conversation
        );

        ClientAccountEntity account =
                conversation.getClientAccount();

        if (account == null) {
            throw new IllegalStateException(
                    "Conversation has no ClientAccount: "
                            + conversationId
            );
        }

        if (account.getClient() != null) {
            throw new IllegalStateException(
                    "Client account is already assigned to a client"
            );
        }

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

        ClientEntity client =
                clientMapper.toEntity(
                        request,
                        organization
                );

        client = clientRepository.saveAndFlush(client);

        account.setClient(client);

        clientAccountRepository.saveAndFlush(account);

        return clientMapper.toDto(client);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClients() {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        List<ClientEntity> clients;

        if (currentUserService.isSuperAdmin()) {

            clients =
                    clientRepository
                            .findAllByOrganizationId(
                                    organizationId
                            );

        } else if (currentUserService.isEmployee()) {

            UUID employeeId =
                    currentUserService.getCurrentEmployeeId();

            clients =
                    clientRepository
                            .findAllVisibleToEmployee(
                                    organizationId,
                                    employeeId
                            );

        } else {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can access clients"
            );
        }

        return clients
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClientListItemDto> getClientListItems() {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        List<ClientEntity> clients;

        if (currentUserService.isSuperAdmin()) {

            clients =
                    clientRepository
                            .findAllByOrganizationId(
                                    organizationId
                            );

        } else if (currentUserService.isEmployee()) {

            UUID employeeId =
                    currentUserService.getCurrentEmployeeId();

            clients =
                    clientRepository
                            .findAllVisibleToEmployee(
                                    organizationId,
                                    employeeId
                            );

        } else {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can access clients"
            );
        }

        return buildClientListItems(
                clients,
                organizationId
        );
    }

    @Transactional(readOnly = true)
    public List<ClientListItemDto> searchClientListItems(
            String query
    ) {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();

        if (normalizedQuery.isBlank()) {
            return getClientListItems();
        }

        List<ClientEntity> clients;

        if (currentUserService.isSuperAdmin()) {

            clients =
                    clientRepository.searchClients(
                            organizationId,
                            normalizedQuery
                    );

        } else if (currentUserService.isEmployee()) {

            clients =
                    clientRepository.searchClientsForEmployee(
                            organizationId,
                            currentUserService
                                    .getCurrentEmployeeId(),
                            normalizedQuery
                    );

        } else {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can search clients"
            );
        }

        return buildClientListItems(
                clients,
                organizationId
        );
    }

    private List<ClientListItemDto> buildClientListItems(
            List<ClientEntity> clients,
            UUID organizationId
    ) {

        if (clients.isEmpty()) {
            return List.of();
        }

        List<UUID> clientIds =
                clients.stream()
                        .map(ClientEntity::getId)
                        .toList();

        List<ClientListAggregateProjection> aggregates;

        if (currentUserService.isSuperAdmin()) {

            aggregates =
                    clientRepository.findListAggregates(
                            organizationId,
                            clientIds
                    );

        } else if (currentUserService.isEmployee()) {

            aggregates =
                    clientRepository.findListAggregatesForEmployee(
                            organizationId,
                            currentUserService
                                    .getCurrentEmployeeId(),
                            clientIds
                    );

        } else {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can access clients"
            );
        }

        var aggregateByClientId =
                aggregates.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ClientListAggregateProjection::getClientId,
                                        aggregate -> aggregate
                                )
                        );

        return clients.stream()
                .map(client -> {

                    ClientListAggregateProjection aggregate =
                            aggregateByClientId.get(client.getId());

                    long accountCount =
                            aggregate == null
                                    ? 0L
                                    : aggregate.getAccountCount();

                    Instant lastContactAt =
                            aggregate == null
                                    ? null
                                    : aggregate.getLastContactAt();

                    return new ClientListItemDto(
                            client.getId(),
                            client.getFirstName(),
                            client.getLastName(),
                            client.getPhoneList(),
                            accountCount,
                            lastContactAt,
                            client.isEnabled()
                    );
                })
                .sorted(
                        java.util.Comparator
                                .comparing(
                                        ClientListItemDto::lastContactAt,
                                        java.util.Comparator
                                                .nullsLast(
                                                        java.util.Comparator
                                                                .reverseOrder()
                                                )
                                )
                                .thenComparing(
                                        ClientListItemDto::lastName,
                                        java.util.Comparator
                                                .nullsLast(
                                                        String.CASE_INSENSITIVE_ORDER
                                                )
                                )
                                .thenComparing(
                                        ClientListItemDto::firstName,
                                        java.util.Comparator
                                                .nullsLast(
                                                        String.CASE_INSENSITIVE_ORDER
                                                )
                                )
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClientDto> searchClients(
            String query
    ) {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();

        if (normalizedQuery.isBlank()) {
            return getClients();
        }

        List<ClientEntity> clients;

        if (currentUserService.isSuperAdmin()) {

            clients =
                    clientRepository.searchClients(
                            organizationId,
                            normalizedQuery
                    );

        } else if (currentUserService.isEmployee()) {

            clients =
                    clientRepository.searchClientsForEmployee(
                            organizationId,
                            currentUserService
                                    .getCurrentEmployeeId(),
                            normalizedQuery
                    );

        } else {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can search clients"
            );
        }

        return clients
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDto getClient(
            UUID id
    ) {

        ClientEntity entity =
                getAccessibleClient(id);

        return clientMapper.toDto(entity);
    }

    public ClientDto updateClient(
            UUID id,
            UpdateClientRequest request
    ) {

        ClientEntity entity =
                getAccessibleClient(id);

        clientMapper.updateEntity(
                entity,
                request
        );

        ClientEntity saved =
                clientRepository.saveAndFlush(entity);

        return clientMapper.toDto(saved);
    }

    /**
     * Удаление Client разрешено только SUPER_ADMIN.
     */
    public void deleteClient(
            UUID id
    ) {

        currentUserService.requireSuperAdmin();

        ClientEntity entity =
                clientRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Client not found: "
                                                + id
                                )
                        );

        requireClientOrganizationAccess(entity);

        clientRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClientsWithoutAccounts() {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        if (!currentUserService.isSuperAdmin()) {
            throw new AccessDeniedException(
                    "Only SUPER_ADMIN can access clients without accounts"
            );
        }

        return clientRepository
                .findClientsWithoutAccounts(organizationId)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    /*
     * ---------------------------------------------------------
     * ClientAccount
     * ---------------------------------------------------------
     */

    /**
     * Добавить новый ClientAccount к Client.
     * <p>
     * EMPLOYEE может выполнять операцию только
     * для доступного Client.
     */
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

    /**
     * Связать orphan ClientAccount с Client.
     */
    public ClientAccountDto assignClientAccount(
            UUID clientId,
            UUID accountId
    ) {

        ClientEntity client =
                getAccessibleClient(clientId);

        ClientAccountEntity account =
                getAccessibleAccount(accountId);

        if (account.getClient() != null) {

            if (account.getClient()
                    .getId()
                    .equals(clientId)) {

                return clientAccountMapper.toDto(account);
            }

            throw new IllegalStateException(
                    "Client account is already assigned to another client"
            );
        }

        requireAccountAccess(account);

        account.setClient(client);

        return clientAccountMapper.toDto(account);
    }

    /**
     * Переназначить ClientAccount на другой Client.
     */
    public ClientAccountDto reassignClientAccount(
            UUID accountId,
            UUID newClientId
    ) {

        ClientAccountEntity account =
                getAccessibleAccount(accountId);

        requireAccountAccess(account);

        ClientEntity newClient =
                getAccessibleClient(newClientId);

        ClientEntity oldClient =
                account.getClient();

        if (oldClient != null
                && oldClient.getId().equals(newClientId)) {

            return clientAccountMapper.toDto(account);
        }

        account.setClient(newClient);

        return clientAccountMapper.toDto(account);
    }

    /**
     * Отвязать ClientAccount от Client.
     */
    public ClientAccountDto unassignClientAccount(
            UUID clientId,
            UUID accountId
    ) {

        ClientEntity client =
                getAccessibleClient(clientId);

        ClientAccountEntity account =
                getAccessibleAccount(accountId);

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

        requireAccountAccess(account);

        account.setClient(null);

        return clientAccountMapper.toDto(account);
    }

    /**
     * Получить ClientAccounts Client.
     * <p>
     * SUPER_ADMIN:
     *   все accounts.
     * <p>
     * EMPLOYEE:
     *   только accounts, у которых есть Conversation
     *   в Workspace, доступном текущему Employee.
     */
    @Transactional(readOnly = true)
    public List<ClientAccountDto> getClientAccounts(
            UUID clientId
    ) {

        getAccessibleClient(clientId);

        if (currentUserService.isSuperAdmin()) {

            return clientAccountRepository
                    .findAllByClientId(clientId)
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        if (currentUserService.isEmployee()) {

            return clientAccountRepository
                    .findAllByClientIdAndEmployeeId(
                            clientId,
                            currentUserService.getCurrentEmployeeId()
                    )
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        throw new AccessDeniedException(
                "Only EMPLOYEE or SUPER_ADMIN can access client accounts"
        );
    }

    @Transactional
    public List<ClientAccountDto> getClientAccountsByClient(
            UUID clientId
    ) {

        ClientEntity client =
                getAccessibleClient(clientId);

        requireClientOrganizationAccess(client);

        if (currentUserService.isSuperAdmin()) {

            return clientAccountRepository
                    .findAllByClientId(clientId)
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        if (currentUserService.isEmployee()) {

            return clientAccountRepository
                    .findAllByClientIdAndEmployeeId(
                            clientId,
                            currentUserService.getCurrentEmployeeId()
                    )
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        throw new AccessDeniedException(
                "Unsupported user role"
        );
    }

    /**
     * Получить Conversations Client.
     * <p>
     * ВАЖНО:
     * прямой связи Conversation -> Client нет.
     * <p>
     * Conversations находятся через:
     * <p>
     * Client
     *   -> ClientAccount
     *       -> Conversation
     * <p>
     * SUPER_ADMIN:
     *   все Conversations Client
     *   в текущей Organization.
     * <p>
     * EMPLOYEE:
     *   только Conversations в доступных Workspace.
     */
    @Transactional(readOnly = true)
    public List<ConversationDto> getClientConversations(
            UUID clientId
    ) {

        ClientEntity client =
                getAccessibleClient(clientId);

        List<ConversationEntity> conversations;

        if (currentUserService.isSuperAdmin()) {

            conversations =
                    conversationRepository
                            .findAllByClientIdAndOrganizationIdOrderByLastMessageAtDesc(
                                    client.getId(),
                                    currentUserService
                                            .getCurrentOrganizationId()
                            );

        } else if (currentUserService.isEmployee()) {

            conversations =
                    conversationRepository
                            .findAllByClientIdAndEmployeeIdOrderByLastMessageAtDesc(
                                    client.getId(),
                                    currentUserService
                                            .getCurrentEmployeeId()
                            );

        } else {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can access client conversations"
            );
        }

        return conversations
                .stream()
                .map(conversationMapper::toDto)
                .toList();
    }

    /*
     * ---------------------------------------------------------
     * Access control
     * ---------------------------------------------------------
     */

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

        requireClientAccess(client);

        return client;
    }

    private ClientAccountEntity getAccessibleAccount(
            UUID accountId
    ) {

        return clientAccountRepository
                .findById(accountId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Client account not found: "
                                        + accountId
                        )
                );
    }

    private void requireClientAccess(
            ClientEntity client
    ) {

        requireClientOrganizationAccess(client);

        if (currentUserService.isSuperAdmin()) {
            return;
        }

        if (!currentUserService.isEmployee()) {
            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can access clients"
            );
        }

        UUID employeeId =
                currentUserService.getCurrentEmployeeId();

        boolean accessible =
                clientRepository.existsVisibleToEmployee(
                        client.getId(),
                        currentUserService
                                .getCurrentOrganizationId(),
                        employeeId
                );

        if (!accessible) {
            throw new AccessDeniedException(
                    "Client is not accessible"
            );
        }
    }

    private void requireClientOrganizationAccess(
            ClientEntity client
    ) {

        UUID currentOrganizationId =
                currentUserService.getCurrentOrganizationId();

        if (!currentOrganizationId.equals(
                client.getOrganization().getId()
        )) {

            throw new AccessDeniedException(
                    "Client is not accessible"
            );
        }
    }

    /**
     * Проверяет доступ текущего пользователя
     * к ClientAccount.
     * <p>
     * SUPER_ADMIN:
     *   Account должен использоваться в текущей Organization.
     * <p>
     * EMPLOYEE:
     *   Account должен иметь Conversation
     *   в доступном Employee Workspace.
     */
    private void requireAccountAccess(
            ClientAccountEntity account
    ) {

        UUID employeeId =
                currentUserService.getCurrentEmployeeId();

        if (currentUserService.isSuperAdmin()) {

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

            return;
        }

        if (currentUserService.isEmployee()) {

            boolean accessible =
                    !conversationRepository
                            .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                                    account.getId(),
                                    employeeId
                            )
                            .isEmpty();

            if (!accessible) {
                throw new AccessDeniedException(
                        "Client account is not accessible"
                );
            }

            return;
        }

        throw new AccessDeniedException(
                "Only EMPLOYEE or SUPER_ADMIN can access client accounts"
        );
    }
}