package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.conversation.*;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ClientAccountState;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.kafka.ChannelReadKafkaCommand;
import kit.penny.clientbus.server.exception.ChannelUnavailableException;
import kit.penny.clientbus.server.kafka.producer.IChannelReadPublisher;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.*;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ChannelAccountRepository channelAccountRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeWorkspaceRepository employeeWorkspaceRepository;
    private final ConversationMapper conversationMapper;
    private final CurrentUserService currentUserService;
    private final MessageRepository messageRepository;
    private final IChannelReadPublisher channelReadPublisher;
    private final ConversationGroupRepository conversationGroupRepository;
    private final ConversationGroupMembershipRepository conversationGroupMembershipRepository;

    public ConversationService(
            ConversationRepository conversationRepository,
            WorkspaceRepository workspaceRepository,
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            EmployeeRepository employeeRepository,
            EmployeeWorkspaceRepository employeeWorkspaceRepository,
            ConversationMapper conversationMapper,
            CurrentUserService currentUserService,
            MessageRepository messageRepository,
            IChannelReadPublisher channelReadPublisher,
            ConversationGroupRepository conversationGroupRepository,
            ConversationGroupMembershipRepository conversationGroupMembershipRepository
    ) {
        this.conversationRepository = conversationRepository;
        this.workspaceRepository = workspaceRepository;
        this.channelAccountRepository = channelAccountRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.employeeRepository = employeeRepository;
        this.employeeWorkspaceRepository = employeeWorkspaceRepository;
        this.conversationMapper = conversationMapper;
        this.currentUserService = currentUserService;
        this.messageRepository = messageRepository;
        this.channelReadPublisher = channelReadPublisher;
        this.conversationGroupRepository = conversationGroupRepository;
        this.conversationGroupMembershipRepository = conversationGroupMembershipRepository;
    }

    /**
     * Создаёт Conversation для пары:
     * <p/>
     * ClientAccount + ChannelAccount.
     */
    public ConversationDto createConversation(
            CreateConversationRequest request
    ) {

        WorkspaceEntity workspace =
                getWorkspace(request.workspaceId());

        currentUserService.requireWorkspaceAccess(
                workspace.getId()
        );

        ChannelAccountEntity channelAccount =
                getChannelAccount(
                        request.channelAccountId()
                );

        ClientAccountEntity clientAccount =
                getClientAccount(
                        request.clientAccountId()
                );

        validateChannelAccountWorkspace(
                channelAccount,
                workspace
        );

        validateChannelType(
                channelAccount,
                clientAccount
        );

        if (conversationRepository
                .existsByChannelAccountIdAndClientAccountId(
                        channelAccount.getId(),
                        clientAccount.getId()
                )) {

            throw new IllegalArgumentException(
                    "Conversation already exists for "
                            + "channelAccountId="
                            + channelAccount.getId()
                            + " and clientAccountId="
                            + clientAccount.getId()
            );
        }

        ConversationEntity entity =
                new ConversationEntity(
                        workspace,
                        channelAccount,
                        clientAccount
                );

        try {

            ConversationEntity saved =
                    conversationRepository.saveAndFlush(entity);

            return conversationMapper.toDto(saved);

        } catch (DataIntegrityViolationException e) {

            throw new IllegalArgumentException(
                    "Conversation already exists for "
                            + "channelAccountId="
                            + channelAccount.getId()
                            + " and clientAccountId="
                            + clientAccount.getId(),
                    e
            );
        }
    }

    /**
     * Создание нового outbound Conversation
     * после выбора внешнего получателя.
     * <p>
     * EMPLOYEE-only.
     * <p>
     * ClientAccount определяется по:
     * <p>
     *     ChannelType + ExternalId
     * <p>
     * а не по clientAccountId из frontend.
     * <p>
     * Если ClientAccount ещё не существует —
     * он создаётся как orphan.
     * <p>
     * ClientAccount всегда создаётся одновременно
     * с Conversation, поэтому отдельного standalone
     * ClientAccount для Employee не возникает.
     */
    public ConversationDto createOutboundConversation(
            CreateOutboundConversationRequest request
    ) {

        if (!currentUserService.isEmployee()) {

            throw new AccessDeniedException(
                    "Only EMPLOYEE can create outbound conversations"
            );
        }

        EmployeeEntity currentEmployee =
                currentUserService.getCurrentEmployee();

        WorkspaceEntity workspace =
                getWorkspace(request.workspaceId());

        currentUserService.requireWorkspaceAccess(
                workspace.getId()
        );

        ChannelAccountEntity channelAccount =
                getChannelAccount(
                        request.channelAccountId()
                );

        validateChannelAccountWorkspace(
                channelAccount,
                workspace
        );

        if (!channelAccount
                .getChannel()
                .getType()
                .equals(request.channelType())) {

            throw new IllegalArgumentException(
                    "ChannelAccount and requested channel type must match"
            );
        }

        ClientAccountEntity clientAccount =
                clientAccountRepository
                        .findByChannelTypeAndExternalId(
                                request.channelType(),
                                request.externalId()
                        )
                        .orElse(null);

        if (clientAccount == null) {

            clientAccount =
                    new ClientAccountEntity(
                            null,
                            request.channelType(),
                            request.externalId(),
                            request.username(),
                            request.phone(),
                            request.displayName()
                    );

            clientAccount.setState(
                    ClientAccountState.ACTIVE
            );

            try {

                clientAccount =
                        clientAccountRepository.saveAndFlush(
                                clientAccount
                        );

            } catch (DataIntegrityViolationException e) {

                clientAccount =
                        clientAccountRepository
                                .findByChannelTypeAndExternalId(
                                        request.channelType(),
                                        request.externalId()
                                )
                                .orElseThrow(() -> e);
            }

        } else {

            if (request.username() != null) {
                clientAccount.setUsername(
                        request.username()
                );
            }

            if (request.phone() != null) {
                clientAccount.setPhone(
                        request.phone()
                );
            }

            if (request.displayName() != null) {
                clientAccount.setDisplayName(
                        request.displayName()
                );
            }
        }

        ConversationEntity existing =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccount.getId(),
                                clientAccount.getId()
                        )
                        .orElse(null);

        if (existing != null) {

            requireConversationAccess(existing);

            return conversationMapper.toDto(existing);
        }

        ConversationEntity conversation =
                new ConversationEntity(
                        workspace,
                        channelAccount,
                        clientAccount
                );

        conversation.setAssignedEmployee(
                currentEmployee
        );

        try {

            ConversationEntity saved =
                    conversationRepository.saveAndFlush(
                            conversation
                    );

            return conversationMapper.toDto(saved);

        } catch (DataIntegrityViolationException e) {

            ConversationEntity concurrent =
                    conversationRepository
                            .findByChannelAccountIdAndClientAccountId(
                                    channelAccount.getId(),
                                    clientAccount.getId()
                            )
                            .orElseThrow(() -> e);

            requireConversationAccess(concurrent);

            return conversationMapper.toDto(
                    concurrent
            );
        }
    }

    /**
     * Внутреннее создание Conversation
     * для Message Processing.
     * <p>
     * ACL отсутствует намеренно.
     * <p>
     * Workspace определяется из ChannelAccount.
     */
    @Transactional
    public ConversationEntity createConversationInternal(
            ChannelAccountEntity channelAccount,
            ClientAccountEntity clientAccount
    ) {

        ConversationEntity existing =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccount.getId(),
                                clientAccount.getId()
                        )
                        .orElse(null);

        if (existing != null) {
            return existing;
        }

        WorkspaceEntity workspace =
                channelAccount
                        .getChannel()
                        .getWorkspace();

        if (workspace == null) {
            throw new IllegalStateException(
                    "ChannelAccount has no Workspace: "
                            + channelAccount.getId()
            );
        }

        ConversationEntity conversation =
                new ConversationEntity(
                        workspace,
                        channelAccount,
                        clientAccount
                );

        try {

            return conversationRepository.saveAndFlush(
                    conversation
            );

        } catch (DataIntegrityViolationException e) {

            return conversationRepository
                    .findByChannelAccountIdAndClientAccountId(
                            channelAccount.getId(),
                            clientAccount.getId()
                    )
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public ConversationDto getConversation(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        requireConversationAccess(conversation);

        return conversationMapper.toDto(conversation);
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getWorkspaceConversations(
            UUID workspaceId
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return conversationRepository
                .findAllByWorkspaceIdOrderByLastMessageAtDesc(
                        workspaceId
                )
                .stream()
                .map(conversationMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getWorkspaceEmployeeConversations(
            UUID workspaceId,
            UUID employeeId
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        if (currentUserService.isEmployee()) {

            currentUserService.requireSelf(employeeId);

        } else {

            currentUserService.requireSuperAdmin();

            currentUserService.requireEmployeeInCurrentOrganization(
                    employeeId
            );
        }

        return conversationRepository
                .findAllByWorkspaceIdAndAssignedEmployeeIdAndEmployeeAccessOrderByLastMessageAtDesc(
                        workspaceId,
                        employeeId
                )
                .stream()
                .map(conversationMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getWorkspaceOtherEmployeeConversations(
            UUID workspaceId,
            UUID employeeId
    ) {
        currentUserService.requireWorkspaceAccess(workspaceId);

        if (!currentUserService.isSupervisor(workspaceId)) {
            throw new AccessDeniedException(
                    "Workspace supervisor role required"
            );
        }

        if (employeeId != null) {
            if (!employeeWorkspaceRepository
                    .existsByEmployeeIdAndWorkspaceId(
                            employeeId,
                            workspaceId
                    )) {
                throw new EntityNotFoundException(
                        "Employee not found in workspace: " + employeeId
                );
            }

            if (employeeId.equals(
                    currentUserService.getCurrentEmployeeId())) {
                throw new IllegalArgumentException(
                        "Use the personal conversations view for yourself"
                );
            }

            return conversationRepository
                    .findAllByWorkspaceIdAndAssignedEmployeeIdAndEmployeeAccessOrderByLastMessageAtDesc(
                            workspaceId,
                            employeeId
                    )
                    .stream()
                    .map(conversationMapper::toDto)
                    .toList();
        }

        return conversationRepository
                .findAllByWorkspaceIdOrderByLastMessageAtDesc(workspaceId)
                .stream()
                .filter(conversation ->
                        conversation.getAssignedEmployee() != null
                                && !conversation.getAssignedEmployee()
                                .getId()
                                .equals(currentUserService.getCurrentEmployeeId())
                )
                .map(conversationMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getClientAccountConversations(
            UUID clientAccountId
    ) {

        clientAccountRepository
                .findById(clientAccountId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "ClientAccount not found: "
                                        + clientAccountId
                        )
                );

        List<ConversationEntity> conversations;

        if (currentUserService.isSuperAdmin()) {

            conversations =
                    conversationRepository
                            .findAllByClientAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                                    clientAccountId,
                                    currentUserService
                                            .getCurrentOrganizationId()
                            );

        } else {

            conversations =
                    conversationRepository
                            .findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                                    clientAccountId,
                                    currentUserService
                                            .getCurrentEmployeeId()
                            );
        }

        return conversations
                .stream()
                .map(conversationMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getChannelAccountConversations(
            UUID channelAccountId
    ) {

        channelAccountRepository
                .findById(channelAccountId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "ChannelAccount not found: "
                                        + channelAccountId
                        )
                );

        List<ConversationEntity> conversations;

        if (currentUserService.isSuperAdmin()) {

            conversations =
                    conversationRepository
                            .findAllByChannelAccountIdAndOrganizationIdOrderByLastMessageAtDesc(
                                    channelAccountId,
                                    currentUserService
                                            .getCurrentOrganizationId()
                            );

        } else {

            conversations =
                    conversationRepository
                            .findAllByChannelAccountIdAndEmployeeIdOrderByLastMessageAtDesc(
                                    channelAccountId,
                                    currentUserService
                                            .getCurrentEmployeeId()
                            );
        }

        return conversations
                .stream()
                .map(conversationMapper::toDto)
                .toList();
    }


    @Transactional(readOnly = true)
    public ConversationSearchResponse searchInbox(
            UUID workspaceId,
            ConversationSearchRequest request
    ) {
        currentUserService.requireWorkspaceAccess(workspaceId);

        if (request == null) {
            throw new IllegalArgumentException("Search request is required");
        }

        boolean superAdmin = currentUserService.isSuperAdmin();
        boolean supervisor = !superAdmin
                && currentUserService.isSupervisor(workspaceId);

        ConversationView view = request.view() == null
                ? ConversationView.MINE
                : request.view();

        UUID employeeId = null;

        if (!superAdmin) {
            employeeId = currentUserService.getCurrentEmployeeId();
        }

        // Проверяем права на представление до получения данных.
        switch (view) {
            case MINE -> {
                if (superAdmin) {
                    throw new AccessDeniedException(
                            "SUPER_ADMIN cannot use MINE view"
                    );
                }
            }
            case GROUP -> {
                if (superAdmin) {
                    throw new AccessDeniedException(
                            "SUPER_ADMIN cannot use GROUP view"
                    );
                }
                if (request.groupId() == null) {
                    throw new IllegalArgumentException(
                            "groupId is required for GROUP view"
                    );
                }
                boolean ownsGroup =
                        conversationGroupRepository
                                .findByIdAndWorkspaceIdAndOwnerEmployeeId(
                                        request.groupId(),
                                        workspaceId,
                                        employeeId
                                )
                                .isPresent();

                if (!ownsGroup) {
                    throw new AccessDeniedException(
                            "Group does not belong to the current employee"
                    );
                }
            }
            case ALL -> {
                if (!superAdmin && !supervisor) {
                    throw new AccessDeniedException(
                            "ALL view requires supervisor or SUPER_ADMIN role"
                    );
                }
            }
            case UNASSIGNED -> {
                // Доступно сотруднику, супервизору и SUPER_ADMIN.
            }
        }

        /*
         * Получаем только ID диалогов, доступных через личную группу.
         * Репозиторий дополнительно проверяет владельца группы,
         * Workspace и текущее назначение диалога.
         */
        Set<UUID> groupConversationIds = Set.of();

        if (view == ConversationView.GROUP) {
            groupConversationIds =
                    conversationGroupMembershipRepository
                            .findAccessibleMemberships(
                                    request.groupId(),
                                    workspaceId,
                                    employeeId
                            )
                            .stream()
                            .map(m -> m.getConversation().getId())
                            .collect(Collectors.toSet());
        }

        String search = request.search() == null
                ? ""
                : request.search().trim().toLowerCase(Locale.ROOT);

        Set<UUID> channelAccountIds =
                request.channelAccountIds() == null
                        ? Set.of()
                        : Set.copyOf(request.channelAccountIds());

        Set<UUID> clientAccountIds =
                request.clientAccountIds() == null
                        ? Set.of()
                        : Set.copyOf(request.clientAccountIds());

        /*
         * В этой первой версии используется существующий метод репозитория.
         * Workspace уже проверен через requireWorkspaceAccess.
         */
        List<ConversationEntity> conversations =
                conversationRepository
                        .findAllByWorkspaceIdOrderByLastMessageAtDesc(
                                workspaceId
                        );

        final UUID currentEmployeeId = employeeId;
        final Set<UUID> accessibleGroupConversationIds = groupConversationIds;

        List<ConversationEntity> filtered = conversations.stream()
                // ACL представления.
                .filter(c -> switch (view) {
                    case MINE -> c.getAssignedEmployee() != null
                            && c.getAssignedEmployee().getId()
                            .equals(currentEmployeeId);

                    case UNASSIGNED -> c.getAssignedEmployee() == null;

                    case GROUP -> accessibleGroupConversationIds.contains(c.getId());

                    case ALL -> true;
                })

                // Фильтр по каналам.
                .filter(c -> channelAccountIds.isEmpty()
                        || channelAccountIds.contains(
                        c.getChannelAccount().getId()
                ))

                // Фильтр по аккаунтам клиента.
                .filter(c -> clientAccountIds.isEmpty()
                        || clientAccountIds.contains(
                        c.getClientAccount().getId()
                ))

                // Наличие связи ClientAccount -> Client.
                .filter(c -> {
                    ConversationClientLinkFilter link = request.clientLink();

                    if (link == null
                            || link == ConversationClientLinkFilter.ANY) {
                        return true;
                    }

                    boolean linked = c.getClientAccount().getClient() != null;

                    return link == ConversationClientLinkFilter.WITH_CLIENT
                            ? linked
                            : !linked;
                })

                // Непрочитанные сообщения.
                .filter(c -> {
                    ConversationUnreadFilter unread = request.unread();

                    if (unread == null
                            || unread == ConversationUnreadFilter.ANY) {
                        return true;
                    }

                    return unread == ConversationUnreadFilter.HAS_UNREAD
                            ? c.getUnreadCount() > 0
                            : c.getUnreadCount() == 0;
                })

                // Диапазон активности по lastMessageAt.
                .filter(c -> request.activityFrom() == null
                        || (c.getLastMessageAt() != null
                        && !c.getLastMessageAt()
                        .isBefore(request.activityFrom())))

                .filter(c -> request.activityTo() == null
                        || (c.getLastMessageAt() != null
                        && !c.getLastMessageAt()
                        .isAfter(request.activityTo())))

                // Поиск по данным аккаунта клиента и последнему сообщению.
                .filter(c -> {
                    if (search.isEmpty()) {
                        return true;
                    }

                    var account = c.getClientAccount();

                    return containsIgnoreCase(account.getDisplayName(), search)
                            || containsIgnoreCase(account.getUsername(), search)
                            || containsIgnoreCase(account.getExternalId(), search)
                            || containsIgnoreCase(
                            c.getLastMessagePreview(), search
                    );
                })
                .collect(Collectors.toList());

        // Сортировка.
        ConversationSort sort = request.sort() == null
                ? ConversationSort.LAST_MESSAGE_DESC
                : request.sort();

        Comparator<Instant> instantComparator =
                Comparator.nullsFirst(Comparator.naturalOrder());

        Comparator<ConversationEntity> comparator = switch (sort) {
            case LAST_MESSAGE_ASC ->
                    Comparator.comparing(
                            ConversationEntity::getLastMessageAt,
                            instantComparator
                    );

            case OLDEST_ACTIVITY_FIRST ->
                    Comparator.comparing(
                            ConversationEntity::getLastMessageAt,
                            instantComparator
                    );

            case LAST_MESSAGE_DESC ->
                    Comparator.comparing(
                            ConversationEntity::getLastMessageAt,
                            Comparator.nullsLast(Comparator.reverseOrder())
                    );
        };

        filtered.sort(comparator.thenComparing(ConversationEntity::getId));

        // Пагинация с ограничением размера страницы.
        int page = Math.max(0, request.page() == null ? 0 : request.page());
        int size = request.size() == null ? 20 : request.size();

        if (size < 1) {
            throw new IllegalArgumentException("Page size must be positive");
        }

        size = Math.min(size, 100);

        long totalElements = filtered.size();
        int totalPages = totalElements == 0
                ? 0
                : (int) ((totalElements + size - 1) / size);

        long offset = (long) page * size;

        List<ConversationDto> content;

        if (offset >= totalElements) {
            content = List.of();
        } else {
            content = filtered.subList(
                            (int) offset,
                            (int) Math.min(offset + size, totalElements)
                    )
                    .stream()
                    .map(conversationMapper::toDto)
                    .toList();
        }

        return new ConversationSearchResponse(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 < totalPages
        );
    }

    private boolean containsIgnoreCase(String value, String search) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(search);
    }


    @Transactional(readOnly = true)
    public ConversationDto getByAccounts(
            UUID channelAccountId,
            UUID clientAccountId
    ) {

        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountId(
                                channelAccountId,
                                clientAccountId
                        )
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Conversation not found"
                                )
                        );

        requireConversationAccess(conversation);

        return conversationMapper.toDto(conversation);
    }

    @Transactional(readOnly = true)
    public ConversationEntity findEntityByAccounts(
            UUID channelAccountId,
            UUID clientAccountId
    ) {

        return conversationRepository
                .findByChannelAccountIdAndClientAccountId(
                        channelAccountId,
                        clientAccountId
                )
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public ConversationEntity findEntityForProcessing(
            UUID conversationId
    ) {

        return conversationRepository
                .findById(conversationId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Conversation not found: "
                                        + conversationId
                        )
                );
    }

    public ConversationEntity findOrCreateForForward(
            ChannelAccountEntity channelAccount,
            ClientAccountEntity clientAccount
    ) {

        ConversationEntity conversation =
                findEntityByAccounts(
                        channelAccount.getId(),
                        clientAccount.getId()
                );

        if (conversation != null) {

            requireForwardTargetAccess(
                    conversation
            );

            return conversation;
        }

        WorkspaceEntity workspace =
                channelAccount
                        .getChannel()
                        .getWorkspace();

        if (workspace == null) {

            throw new IllegalStateException(
                    "ChannelAccount has no Workspace: "
                            + channelAccount.getId()
            );
        }

        currentUserService.requireWorkspaceAccess(
                workspace.getId()
        );

        ConversationEntity created =
                createConversationInternal(
                        channelAccount,
                        clientAccount
                );

        if (currentUserService.isEmployee()) {

            created.setAssignedEmployee(
                    currentUserService.getCurrentEmployee()
            );
        }

        return created;
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getEmployeeConversations(
            UUID employeeId
    ) {

        List<ConversationEntity> conversations;

        if (currentUserService.isEmployee()) {

            currentUserService.requireSelf(employeeId);

            conversations =
                    conversationRepository
                            .findAllByAssignedEmployeeIdAndEmployeeAccessOrderByLastMessageAtDesc(
                                    employeeId
                            );

        } else {

            currentUserService.requireSuperAdmin();

            currentUserService
                    .requireEmployeeInCurrentOrganization(
                            employeeId
                    );

            conversations =
                    conversationRepository
                            .findAllByAssignedEmployeeIdAndOrganizationIdOrderByLastMessageAtDesc(
                                    employeeId,
                                    currentUserService
                                            .getCurrentOrganizationId()
                            );
        }

        return conversations
                .stream()
                .map(conversationMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> getUnassignedConversations(
            UUID workspaceId
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return conversationRepository
                .findAllByWorkspaceIdAndAssignedEmployeeIsNullOrderByLastMessageAtDesc(
                        workspaceId
                )
                .stream()
                .map(conversationMapper::toDto)
                .toList();
    }

    public ConversationDto assignEmployee(
            UUID conversationId,
            UUID employeeId
    ) {

        currentUserService.requireSuperAdmin();

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        requireConversationAccess(conversation);

        EmployeeEntity employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Employee not found: "
                                                + employeeId
                                )
                        );

        UUID workspaceId =
                conversation
                        .getWorkspace()
                        .getId();

        UUID organizationId =
                conversation
                        .getWorkspace()
                        .getOrganization()
                        .getId();

        if (!employee.getOrganization()
                .getId()
                .equals(organizationId)) {

            throw new AccessDeniedException(
                    "Employee belongs to another organization"
            );
        }

        if (!employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employeeId,
                        workspaceId
                )) {

            throw new IllegalArgumentException(
                    "Employee does not have access to workspace"
            );
        }

        conversation.setAssignedEmployee(employee);

        return conversationMapper.toDto(conversation);
    }

    public ConversationDto unassignEmployee(
            UUID conversationId
    ) {

        currentUserService.requireSuperAdmin();

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        requireConversationAccess(conversation);

        conversation.setAssignedEmployee(null);

        return conversationMapper.toDto(conversation);
    }

    public ConversationDto assignmentToMe(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        requireConversationAccess(conversation);

        EmployeeEntity currentEmployee =
                currentUserService.getCurrentEmployee();

        EmployeeEntity assignedEmployee =
                conversation.getAssignedEmployee();

        if (assignedEmployee != null
                && !assignedEmployee.getId()
                .equals(currentEmployee.getId())) {

            throw new AccessDeniedException(
                    "Conversation is already assigned "
                            + "to another employee"
            );
        }

        conversation.setAssignedEmployee(
                currentEmployee
        );

        return conversationMapper.toDto(conversation);
    }

    public ConversationDto unassignmentFromMe(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        requireConversationAccess(conversation);

        EmployeeEntity currentEmployee =
                currentUserService.getCurrentEmployee();

        EmployeeEntity assignedEmployee =
                conversation.getAssignedEmployee();

        if (assignedEmployee == null) {

            return conversationMapper.toDto(
                    conversation
            );
        }

        if (!assignedEmployee.getId()
                .equals(currentEmployee.getId())) {

            throw new AccessDeniedException(
                    "Conversation is assigned "
                            + "to another employee"
            );
        }

        conversation.setAssignedEmployee(null);

        return conversationMapper.toDto(conversation);
    }

    public ConversationDto markAsRead(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(
                        conversationId
                );

        currentUserService.requireConversationAccess(
                conversation
        );

        requireConversationAccess(
                conversation
        );

        MessageEntity lastInboundMessage =
                messageRepository
                        .findFirstByConversationIdAndDirectionOrderBySentAtDescCreatedAtDesc(
                                conversation.getId(),
                                MessageDirection.INBOUND
                        )
                        .orElse(null);

        if (conversation.getAssignedEmployee() != null
                && lastInboundMessage != null
                && lastInboundMessage.getExternalId() != null
                && !lastInboundMessage.getExternalId().isBlank()) {


            ChannelAccountEntity channelAccount =
                    conversation.getChannelAccount();

            if (channelAccount.getChannel().getStatus()
                    == ChannelConnectionStatus.CONNECTED) {

                ChannelType channelType =
                        channelAccount.getChannel().getType();

                channelReadPublisher.publish(
                        channelType,
                        new ChannelReadKafkaCommand(
                                channelAccount.getId(),
                                conversation.getClientAccount().getExternalId(),
                                lastInboundMessage.getExternalId()
                        )
                );
            }

            conversation.setUnreadCount(0);

        }

        return conversationMapper.toDto(
                conversation
        );
    }

    public void requireForwardTargetAccess(
            ConversationEntity conversation
    ) {

        if (currentUserService.isSuperAdmin()) {

            requireConversationAccess(
                    conversation
            );

            return;
        }

        if (!currentUserService.isEmployee()) {

            throw new AccessDeniedException(
                    "Only EMPLOYEE or SUPER_ADMIN can forward messages"
            );
        }

        EmployeeEntity currentEmployee =
                currentUserService.getCurrentEmployee();

        EmployeeEntity assignedEmployee =
                conversation.getAssignedEmployee();

        if (assignedEmployee == null) {

            throw new AccessDeniedException(
                    "EMPLOYEE cannot forward messages "
                            + "to an unassigned Conversation"
            );
        }

        if (!assignedEmployee
                .getId()
                .equals(currentEmployee.getId())) {

            throw new AccessDeniedException(
                    "EMPLOYEE can forward messages only "
                            + "to own Conversations"
            );
        }

        currentUserService.requireWorkspaceAccess(
                conversation
                        .getWorkspace()
                        .getId()
        );
    }

    @Transactional(readOnly = true)
    public long getWorkspaceUnreadCount(
            UUID workspaceId
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return conversationRepository
                .countByWorkspaceIdAndUnreadCountGreaterThan(
                        workspaceId,
                        0
                );
    }

    @Transactional(readOnly = true)
    public long getEmployeeUnreadCount(
            UUID employeeId
    ) {

        if (currentUserService.isEmployee()) {

            currentUserService.requireSelf(employeeId);

            return conversationRepository
                    .countAssignedUnreadByEmployeeWithWorkspaceAccess(
                            employeeId,
                            0
                    );

        }

        currentUserService.requireSuperAdmin();

        currentUserService
                .requireEmployeeInCurrentOrganization(
                        employeeId
                );

        return conversationRepository
                .countAssignedUnreadByEmployeeAndOrganization(
                        employeeId,
                        currentUserService
                                .getCurrentOrganizationId(),
                        0
                );
    }

    @Transactional
    public void markHistoryStartReached(
            UUID channelAccountId,
            String clientAccountExternalId
    ) {
        ConversationEntity conversation =
                conversationRepository
                        .findByChannelAccountIdAndClientAccountExternalId(
                                channelAccountId,
                                clientAccountExternalId
                        )
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Conversation not found for " +
                                                "channelAccountId="
                                                + channelAccountId
                                                + " and clientAccountExternalId="
                                                + clientAccountExternalId
                                )
                        );

        conversation.setHistoryStartReached(true);

        conversationRepository.save(conversation);
    }

    public void updateFirstMessage(
            ConversationEntity conversation,
            Instant messageTime
    ) {
        if (conversation.getFirstMessageAt() == null
                || messageTime.isBefore(
                conversation.getFirstMessageAt()
        )) {

            conversation.setFirstMessageAt(
                    messageTime
            );
        }
    }

    public ConversationEntity incrementUnreadCount(
            ConversationEntity conversation
    ) {

        conversation.setUnreadCount(
                conversation.getUnreadCount() + 1
        );

        return conversation;
    }

    public ConversationEntity updateLastMessage(
            ConversationEntity conversation,
            Instant messageTime,
            String preview
    ) {

        Instant currentLastMessageAt =
                conversation.getLastMessageAt();

        if (currentLastMessageAt != null
                && messageTime != null
                && messageTime.isBefore(currentLastMessageAt)) {

            return conversation;
        }

        conversation.setLastMessageAt(messageTime);
        conversation.setLastMessagePreview(preview);

        return conversation;
    }

    public void requireExternalOperationsAvailable(
            ConversationEntity conversation
    ) {
        if (conversation == null
                || conversation.getChannelAccount() == null
                || conversation.getChannelAccount().getChannel() == null
                || conversation.getChannelAccount()
                .getChannel().getStatus()
                != ChannelConnectionStatus.CONNECTED) {

            throw new ChannelUnavailableException(
                    "External operations are unavailable for this conversation"
            );
        }
    }

    /* ==============================================
                          HANDLERS
    ================================================= */

    private boolean canViewAll(UUID workspaceId) {
        return currentUserService.isSuperAdmin()
                || currentUserService.isSupervisor(workspaceId);
    }


    private void requireConversationAccess(
            ConversationEntity conversation
    ) {

        currentUserService.requireWorkspaceAccess(
                conversation
                        .getWorkspace()
                        .getId()
        );
    }

    private WorkspaceEntity getWorkspace(
            UUID workspaceId
    ) {

        return workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Workspace not found: "
                                        + workspaceId
                        )
                );
    }

    private ChannelAccountEntity getChannelAccount(
            UUID channelAccountId
    ) {

        return channelAccountRepository
                .findById(channelAccountId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "ChannelAccount not found: "
                                        + channelAccountId
                        )
                );
    }

    private ClientAccountEntity getClientAccount(
            UUID clientAccountId
    ) {

        return clientAccountRepository
                .findById(clientAccountId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "ClientAccount not found: "
                                        + clientAccountId
                        )
                );
    }

    private ConversationEntity getConversationEntity(
            UUID conversationId
    ) {

        return conversationRepository
                .findById(conversationId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Conversation not found: "
                                        + conversationId
                        )
                );
    }

    private void validateChannelAccountWorkspace(
            ChannelAccountEntity channelAccount,
            WorkspaceEntity workspace
    ) {

        WorkspaceEntity channelWorkspace =
                channelAccount
                        .getChannel()
                        .getWorkspace();

        if (!channelWorkspace
                .getId()
                .equals(workspace.getId())) {

            throw new IllegalArgumentException(
                    "ChannelAccount does not belong "
                            + "to workspace"
            );
        }
    }

    private void validateChannelType(
            ChannelAccountEntity channelAccount,
            ClientAccountEntity clientAccount
    ) {

        if (!channelAccount
                .getChannel()
                .getType()
                .equals(clientAccount.getChannelType())) {

            throw new IllegalArgumentException(
                    "ChannelAccount and ClientAccount "
                            + "must belong to the same channel type"
            );
        }
    }
}