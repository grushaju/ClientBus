package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.common.dto.conversation.CreateConversationRequest;
import kit.penny.clientbus.common.dto.conversation.CreateOutboundConversationRequest;
import kit.penny.clientbus.common.enums.ClientAccountState;
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
     *
     * EMPLOYEE-only.
     *
     * ClientAccount определяется по:
     *
     *     ChannelType + ExternalId
     *
     * а не по clientAccountId из frontend.
     *
     * Если ClientAccount ещё не существует —
     * он создаётся как orphan.
     *
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
     *
     * ACL отсутствует намеренно.
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
                getConversationEntity(conversationId);

        currentUserService.requireConversationAccess(
                conversation
        );

        requireConversationAccess(conversation);

        conversation.setUnreadCount(0);

        return conversationMapper.toDto(conversation);
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