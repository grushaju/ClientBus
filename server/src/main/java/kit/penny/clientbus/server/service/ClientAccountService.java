package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.dto.clientaccount.CreateClientAccountRequest;
import kit.penny.clientbus.common.dto.clientaccount.UpdateClientAccountRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ClientAccountState;
import kit.penny.clientbus.server.mapper.ClientAccountMapper;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
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
    private final ConversationRepository conversationRepository;

    public ClientAccountService(
            ClientAccountRepository clientAccountRepository,
            ClientRepository clientRepository,
            ClientAccountMapper clientAccountMapper,
            CurrentUserService currentUserService,
            ConversationRepository conversationRepository
    ) {
        this.clientAccountRepository = clientAccountRepository;
        this.clientRepository = clientRepository;
        this.clientAccountMapper = clientAccountMapper;
        this.currentUserService = currentUserService;
        this.conversationRepository = conversationRepository;
    }

    /**
     * Создание ClientAccount.
     *
     * Если clientId указан — аккаунт сразу привязывается
     * к Client текущей Organization.
     *
     * Если clientId не указан — создаётся orphan account.
     * Ручное создание orphan account разрешено только SUPER_ADMIN.
     *
     * Для inbound аккаунты создаются через getOrCreateForInbound().
     */
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

            requireClientOrganizationAccess(client);

        } else {
            requireSuperAdmin();
        }

        /*
         * ClientAccount — глобальная identity.
         *
         * Поэтому комбинация
         * (channelType, externalId)
         * должна быть уникальной независимо от Client.
         */
        if (clientAccountRepository
                .existsByChannelTypeAndExternalId(
                        request.channelType(),
                        request.externalId()
                )) {

            throw new IllegalStateException(
                    "Account already exists: "
                            + request.channelType()
                            + " / "
                            + request.externalId()
            );
        }

        ClientAccountEntity entity =
                clientAccountMapper.toEntity(request);

        entity.setClient(client);

        entity =
                clientAccountRepository.saveAndFlush(entity);

        return clientAccountMapper.toDto(entity);
    }

    /**
     * Inbound identity resolution.
     *
     * ClientAccount глобален для всей системы:
     * один (channelType, externalId) = один реальный аккаунт клиента.
     *
     * Client намеренно не создаётся и не назначается здесь.
     */
    @Transactional
    public ClientAccountEntity getOrCreateForInbound(
            ChannelType channelType,
            String externalId,
            String username,
            String phone,
            String displayName
    ) {

        if (channelType == null) {
            throw new IllegalArgumentException(
                    "channelType must not be null"
            );
        }

        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException(
                    "externalId must not be blank"
            );
        }

        return clientAccountRepository
                .findByChannelTypeAndExternalId(
                        channelType,
                        externalId
                )
                .map(entity -> {

                    entity.setUsername(username);
                    entity.setPhone(phone);
                    entity.setDisplayName(displayName);

                    return entity;
                })
                .orElseGet(() -> {

                    ClientAccountEntity entity =
                            new ClientAccountEntity();

                    entity.setChannelType(channelType);
                    entity.setExternalId(externalId);
                    entity.setUsername(username);
                    entity.setPhone(phone);
                    entity.setDisplayName(displayName);

                    /*
                     * Client создаётся отдельным use case.
                     *
                     * Inbound только создаёт identity аккаунта.
                     */
                    entity.setClient(null);

                    entity.setState(
                            ClientAccountState.ACTIVE
                    );

                    return clientAccountRepository.save(entity);
                });
    }

    /**
     * Получить ClientAccount.
     *
     * Доступ определяется через Conversation.
     */
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

    /**
     * Все аккаунты конкретного Client.
     *
     * Client принадлежит Organization, поэтому
     * Workspace здесь больше не участвует.
     */
    @Transactional
    public List<ClientAccountDto> getClientAccountsByClient(
            UUID clientId
    ) {

        ClientEntity client =
                getClient(clientId);

        requireClientOrganizationAccess(client);

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

        requireClientOrganizationAccess(client);

        return clientAccountRepository
                .findAllByClientIdAndChannelType(
                        clientId,
                        channelType
                )
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    /**
     * Orphan accounts.
     *
     * Orphan ClientAccount всё равно должен иметь
     * хотя бы одну Conversation, иначе он не имеет
     * контекста принадлежности к Organization.
     *
     * Поэтому список определяется через Conversation,
     * а не просто через client IS NULL.
     */
    @Transactional
    public List<ClientAccountDto> getUnassignedAccounts() {

        requireSuperAdmin();

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        return clientAccountRepository
                .findAllUnassignedByOrganizationId(
                        organizationId
                )
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> getUnassignedAccounts(
            ChannelType channelType
    ) {

        requireSuperAdmin();

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        return clientAccountRepository
                .findAllUnassignedByOrganizationIdAndChannelType(
                        organizationId,
                        channelType
                )
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    /**
     * Batch lookup ClientAccounts.
     *
     * SUPER_ADMIN:
     * аккаунт должен иметь Conversation в текущей Organization.
     *
     * EMPLOYEE:
     * аккаунт должен иметь Conversation в Workspace,
     * доступном текущему Employee.
     */
    @Transactional
    public List<ClientAccountDto> getClientAccountsByIds(
            List<UUID> ids
    ) {

        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        if (currentUserService.isSuperAdmin()) {

            return clientAccountRepository
                    .findAllByIdsAndOrganizationId(
                            ids,
                            currentUserService
                                    .getCurrentOrganizationId()
                    )
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        if (currentUserService.isEmployee()) {

            return clientAccountRepository
                    .findAllByIdsAndEmployeeId(
                            ids,
                            currentUserService
                                    .getCurrentEmployeeId()
                    )
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        throw new AccessDeniedException(
                "Unsupported user role"
        );
    }

    @Transactional
    public List<ClientAccountDto> searchClientAccounts(
            UUID clientId,
            String query
    ) {

        ClientEntity client =
                getClient(clientId);

        requireClientOrganizationAccess(client);

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

    /**
     * Доступ к ClientAccount определяется через Conversation.
     *
     * SUPER_ADMIN:
     * любой Conversation аккаунта в текущей Organization.
     *
     * EMPLOYEE:
     * Conversation аккаунта в Workspace,
     * доступном Employee.
     */
    private void requireAccountAccess(
            ClientAccountEntity account
    ) {

        if (currentUserService.isSuperAdmin()) {

            boolean accessible =
                    !conversationRepository
                            .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                                    account.getId(),
                                    currentUserService
                                            .getCurrentOrganizationId()
                            )
                            .isEmpty();

            if (!accessible) {
                throw new AccessDeniedException(
                        "ClientAccount is not accessible"
                );
            }

            return;
        }

        if (currentUserService.isEmployee()) {

            boolean accessible =
                    !conversationRepository
                            .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                                    account.getId(),
                                    currentUserService
                                            .getCurrentEmployeeId()
                            )
                            .isEmpty();

            if (!accessible) {
                throw new AccessDeniedException(
                        "ClientAccount is not accessible"
                );
            }

            return;
        }

        throw new AccessDeniedException(
                "Unsupported user role"
        );
    }

    /**
     * Client принадлежит Organization.
     *
     * Workspace здесь принципиально не проверяется.
     */
    private void requireClientOrganizationAccess(
            ClientEntity client
    ) {

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        if (client.getOrganization() == null) {
            throw new IllegalStateException(
                    "Client has no organization: "
                            + client.getId()
            );
        }

        if (!client.getOrganization()
                .getId()
                .equals(organizationId)) {

            throw new AccessDeniedException(
                    "Client is not accessible"
            );
        }
    }

    private void requireSuperAdmin() {

        if (!currentUserService.isSuperAdmin()) {
            throw new AccessDeniedException(
                    "SUPER_ADMIN role is required"
            );
        }
    }
}
