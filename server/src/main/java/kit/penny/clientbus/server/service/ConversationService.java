package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.common.dto.conversation.CreateConversationRequest;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    public ConversationService(
            ConversationRepository conversationRepository,
            WorkspaceRepository workspaceRepository,
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            EmployeeRepository employeeRepository,
            EmployeeWorkspaceRepository employeeWorkspaceRepository,
            ConversationMapper conversationMapper,
            CurrentUserService currentUserService
    ) {
        this.conversationRepository = conversationRepository;
        this.workspaceRepository = workspaceRepository;
        this.channelAccountRepository = channelAccountRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.employeeRepository = employeeRepository;
        this.employeeWorkspaceRepository = employeeWorkspaceRepository;
        this.conversationMapper = conversationMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * Создаёт Conversation для пары:
     *
     * ClientAccount + ChannelAccount.
     *
     * Один ClientAccount может иметь несколько Conversation,
     * если используются разные ChannelAccount.
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

            /*
             * Защита от race condition при параллельном
             * создании Conversation для одной пары аккаунтов.
             */
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
     * Внутреннее создание Conversation
     * для Message Processing.
     *
     * ACL намеренно отсутствует.
     *
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

            /*
             * Возможна гонка:
             *
             * webhook #1 ─┐
             *             ├─ create Conversation
             * webhook #2 ─┘
             *
             * DB unique constraint является
             * окончательным арбитром.
             */
            return conversationRepository
                    .findByChannelAccountIdAndClientAccountId(
                            channelAccount.getId(),
                            clientAccount.getId()
                    )
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Получить Conversation.
     */
    @Transactional(readOnly = true)
    public ConversationDto getConversation(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        requireConversationAccess(conversation);

        return conversationMapper.toDto(conversation);
    }

    /**
     * Получить все Conversation Workspace.
     */
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

    /**
     * Получить все Conversation ClientAccount,
     * доступные текущему пользователю.
     */
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

    /**
     * Получить все Conversation ChannelAccount,
     * доступные текущему пользователю.
     */
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

    /**
     * Найти Conversation по:
     *
     * ClientAccount + ChannelAccount.
     */
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

    /**
     * Внутренний поиск Conversation.
     *
     * Используется application layer / Message Processing.
     *
     * ACL намеренно отсутствует:
     * это не HTTP endpoint.
     */
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

    /**
     * Внутренний поиск Conversation по ID.
     *
     * Используется Message Processing.
     *
     * ACL намеренно отсутствует:
     * это не пользовательский application/query use case.
     */
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

    /**
     * Получить Conversation, назначенные Employee.
     *
     * EMPLOYEE может запросить только свои.
     *
     * SUPER_ADMIN может запросить Employee
     * своей Organization.
     */
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

    /**
     * Получить неназначенные Conversation Workspace.
     */
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

    /**
     * Административное назначение Conversation
     * конкретному Employee.
     *
     * Только SUPER_ADMIN.
     */
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

        /*
         * Нельзя назначить Employee другой Organization.
         */
        if (!employee.getOrganization()
                .getId()
                .equals(organizationId)) {

            throw new AccessDeniedException(
                    "Employee belongs to another organization"
            );
        }

        /*
         * Employee должен иметь доступ к Workspace.
         */
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

    /**
     * Административное снятие назначения.
     *
     * Только SUPER_ADMIN.
     */
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

    /**
     * Назначить Conversation на себя.
     *
     * EMPLOYEE и SUPER_ADMIN.
     *
     * ВАЖНО:
     *
     * - свободный Conversation -> можно взять;
     * - Conversation уже назначен себе -> ничего не меняем;
     * - Conversation назначен другому Employee -> нельзя забрать.
     */
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

        /*
         * Если уже назначен на текущего Employee —
         * операция идемпотентна.
         */
        conversation.setAssignedEmployee(
                currentEmployee
        );

        return conversationMapper.toDto(conversation);
    }

    /**
     * Снять Conversation с себя.
     *
     * EMPLOYEE и SUPER_ADMIN.
     *
     * Нельзя снять назначение другого Employee.
     */
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

        /*
         * Уже свободен — операция идемпотентна.
         */
        if (assignedEmployee == null) {

            return conversationMapper.toDto(
                    conversation
            );
        }

        /*
         * Нельзя снять другого Employee.
         */
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

    /**
     * Пометить Conversation прочитанным.
     */
    public ConversationDto markAsRead(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        requireConversationAccess(conversation);

        conversation.setUnreadCount(0);

        return conversationMapper.toDto(conversation);
    }

    /**
     * Количество непрочитанных Conversation Workspace.
     */
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

    /**
     * Количество непрочитанных Conversation Employee.
     */
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

    /**
     * Увеличить unread count.
     *
     * Internal operation.
     *
     * Вызывается MessageService / MessageProcessingService
     * для INBOUND сообщения.
     */
    public ConversationEntity incrementUnreadCount(
            ConversationEntity conversation
    ) {

        conversation.setUnreadCount(
                conversation.getUnreadCount() + 1
        );

        return conversation;
    }

    /**
     * Обновить информацию о последнем сообщении.
     *
     * Internal operation.
     *
     * Вызывается MessageService / MessageProcessingService.
     */
    public ConversationEntity updateLastMessage(
            ConversationEntity conversation,
            Instant messageTime,
            String preview
    ) {

        conversation.setLastMessageAt(messageTime);
        conversation.setLastMessagePreview(preview);

        return conversation;
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