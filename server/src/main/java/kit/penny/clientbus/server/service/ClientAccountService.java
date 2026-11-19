package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.client.ClientLinkCandidateDto;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ClientAccountService {

    @org.springframework.beans.factory.annotation.Autowired
    private kit.penny.clientbus.server.notification.outbox.UiDomainEvents uiEvents;

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
     * Это низкоуровневая административная операция.
     *
     * Обычный EMPLOYEE не создаёт ClientAccount напрямую.
     * Для EMPLOYEE создание новой внешней identity выполняется
     * атомарно через createOutboundConversation().
     *
     * SUPER_ADMIN может создать аккаунт и при необходимости
     * сразу привязать его к Client текущей Organization.
     */
    @Transactional
    public ClientAccountDto createClientAccount(
            CreateClientAccountRequest request
    ) {

        requireSuperAdmin();

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

        if (uiEvents != null) uiEvents.clientAccount(entity);

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
            String displayName,
            String avatarUrl
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

        Optional<ClientAccountEntity> existing =
                clientAccountRepository.findByChannelTypeAndExternalId(
                        channelType,
                        externalId
                );

        if (existing.isPresent()) {
            ClientAccountEntity entity = existing.get();

            updateProfileFields(
                    entity,
                    username,
                    phone,
                    displayName,
                    avatarUrl
            );

            return entity;
        }

        ClientAccountEntity entity = new ClientAccountEntity();

        entity.setChannelType(channelType);
        entity.setExternalId(externalId);
        entity.setClient(null);

        entity.setUsername(username);
        entity.setPhone(phone);
        entity.setDisplayName(displayName);
        entity.setAvatarUrl(avatarUrl);

        entity.setState(ClientAccountState.ACTIVE);

        try {
            return clientAccountRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            return clientAccountRepository
                    .findByChannelTypeAndExternalId(
                            channelType,
                            externalId
                    )
                    .orElseThrow(() -> e);
        }
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
     * Кандидаты Client для ручной привязки учётной записи чата (Stage 13.9, пункт 3 плана).
     *
     * <p>Подсказка одна — телефон: последние десять цифр телефона учётки против последних десяти
     * цифр телефонов клиента. Ни аватар, ни совпадение имени основанием не считаются: связь ставит
     * и снимает только человек (CRM_SEMANTICS §4). Телефон берётся из самой учётной записи, а не из
     * параметра запроса, — иначе endpoint стал бы инструментом подбора людей по номеру: подсказку
     * получает тот, кто уже видит диалог с этой учёткой (requireAccountAccess).
     *
     * @param query дословный поиск по имени, фамилии или телефону; пусто — только телефонная подсказка
     * @return не больше 50 строк, совпавшие по телефону — первыми
     */
    @Transactional(readOnly = true)
    public List<ClientLinkCandidateDto> clientLinkCandidates(
            UUID accountId,
            String query
    ) {
        ClientAccountEntity account = clientAccountRepository.findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: " + accountId));

        requireAccountAccess(account);

        String search = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        String pattern = likePattern(search);
        String tail = phoneTail(account.getPhone());

        var ids = clientRepository
                .searchLinkCandidateIds(currentUserService.getCurrentOrganizationId(), search, pattern, tail,
                        kit.penny.clientbus.crm.api.CrmPhone.FORMATTING_CHARACTERS);
        if (ids.isEmpty()) return List.of();
        // Один запрос на весь список вместо обращения по каждому идентификатору: поиск
        // вызывается из формы привязки на каждое действие оператора, строк здесь до
        // пятидесяти. Порядок итоговой выдачи задаёт сортировка ниже, а не порядок ids.
        // Телефоны догружаются пакетом (см. @BatchSize у коллекции клиента).
        return clientRepository.findAllById(ids)
                .stream()
                .map(target -> new ClientLinkCandidateDto(target.getId(), target.getFirstName(), target.getLastName(),
                        List.copyOf(target.getPhoneList()),
                        !tail.isEmpty() && target.getPhoneList().stream().anyMatch(value -> tail.equals(phoneTail(value)))))
                .sorted(java.util.Comparator.comparing(ClientLinkCandidateDto::phoneMatch).reversed()
                        .thenComparing(ClientLinkCandidateDto::id))
                .toList();
    }

    /**
     * Хвост для телефонной подсказки: только цифры и только последние десять. Меньше десяти цифр —
     * не подсказка: по «12345» совпал бы пол-базы. Форматирование снимает то же правило, что и SQL
     * (CrmPhone.FORMATTING_CHARACTERS), а номер с непривычными символами не считается номером вовсе.
     */
    private static String phoneTail(String value) {
        var digits = kit.penny.clientbus.crm.api.CrmPhone.normalize(value);
        return digits == null || digits.length() < 10 ? "" : digits.substring(digits.length() - 10);
    }

    /** Поиск дословный: метасимволы LIKE экранируются, иначе запрос пользователя стал бы маской. */
    private static String likePattern(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
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

    /** Аккаунты диалогов текущего Workspace, включая аккаунты без Client. */
    @Transactional(readOnly = true)
    public List<ClientAccountDto> getWorkspaceAccounts(UUID workspaceId) {
        return getWorkspaceAccounts(workspaceId, null);
    }

    @Transactional(readOnly = true)
    public List<ClientAccountDto> getWorkspaceAccounts(UUID workspaceId, List<UUID> channelAccountIds) {
        currentUserService.requireWorkspaceAccess(workspaceId);
        var accounts = channelAccountIds == null || channelAccountIds.isEmpty()
                ? clientAccountRepository.findAllByWorkspaceId(workspaceId)
                : clientAccountRepository.findAllByWorkspaceIdAndChannelAccountIds(workspaceId, channelAccountIds);
        return accounts.stream()
                .map(clientAccountMapper::toDto).toList();
    }

    /** Поиск аккаунтов без Client в пределах доступных пользователю Workspace. */
    @Transactional(readOnly = true)
    public List<ClientAccountDto> searchUnassignedAccounts(
            String query
    ) {

        if (!currentUserService.isSuperAdmin()
                && !currentUserService.isEmployee()) {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can search unassigned accounts"
            );
        }

        UUID organizationId =
                currentUserService.getCurrentOrganizationId();

        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();

        if (normalizedQuery.isBlank()) {

            if (currentUserService.isSuperAdmin()) {

                return clientAccountRepository
                        .findAllUnassignedByOrganizationId(
                                organizationId
                        )
                        .stream()
                        .map(clientAccountMapper::toDto)
                        .toList();
            }

            return clientAccountRepository
                    .findAllUnassignedByOrganizationIdAndEmployeeId(
                            organizationId,
                            currentUserService.getCurrentEmployeeId()
                    )
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        if (currentUserService.isSuperAdmin()) {

            return clientAccountRepository
                    .searchUnassignedByOrganizationId(
                            organizationId,
                            normalizedQuery
                    )
                    .stream()
                    .map(clientAccountMapper::toDto)
                    .toList();
        }

        return clientAccountRepository
                .searchUnassignedByOrganizationIdAndEmployeeId(
                        organizationId,
                        currentUserService.getCurrentEmployeeId(),
                        normalizedQuery
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

        if (uiEvents != null) uiEvents.clientAccount(entity);

        return clientAccountMapper.toDto(entity);
    }

    /**
     * Глобальное удаление ClientAccount.
     *
     * ClientAccount — глобальная identity, поэтому удалять
     * его может только SUPER_ADMIN.
     */
    @Transactional
    public void deleteClientAccount(UUID id) {

        requireSuperAdmin();

        ClientAccountEntity entity =
                clientAccountRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Account not found: " + id
                                )
                        );

        if (uiEvents != null) uiEvents.clientAccount(entity);
        clientAccountRepository.delete(entity);
    }

    /**
     * Обновляет профиль существующего ClientAccount.
     *
     * Новые аккаунты не создаются.
     * Состояние аккаунта и Client не изменяются.
     */
    @Transactional
    public ClientAccountEntity updateExistingProfile(
            UUID id,
            String username,
            String phone,
            String displayName,
            String avatarUrl
    ) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "ClientAccount id must not be null"
            );
        }

        ClientAccountEntity entity =
                clientAccountRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Account not found: " + id
                                )
                        );

        updateProfileFields(
                entity,
                username,
                phone,
                displayName,
                avatarUrl
        );

        return entity;
    }

    private void updateProfileFields(
            ClientAccountEntity entity,
            String username,
            String phone,
            String displayName,
            String avatarUrl
    ) {
        boolean changed = false;
        if (username != null && !username.isBlank() && !java.util.Objects.equals(username, entity.getUsername())) {
            changed = true;
            entity.setUsername(username);
        }

        if (phone != null && !phone.isBlank() && !java.util.Objects.equals(phone, entity.getPhone())) {
            changed = true;
            entity.setPhone(phone);
        }

        if (displayName != null && !displayName.isBlank() && !java.util.Objects.equals(displayName, entity.getDisplayName())) {
            changed = true;
            entity.setDisplayName(displayName);
        }

        if (avatarUrl != null && !avatarUrl.isBlank() && !java.util.Objects.equals(avatarUrl, entity.getAvatarUrl())) {
            changed = true;
            entity.setAvatarUrl(avatarUrl);
        }
        if (changed && uiEvents != null) uiEvents.clientAccount(entity);
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
