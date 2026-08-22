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
        this.channelAccountRepository =
                channelAccountRepository;
        this.clientAccountRepository =
                clientAccountRepository;
        this.employeeRepository = employeeRepository;
        this.employeeWorkspaceRepository =
                employeeWorkspaceRepository;
        this.conversationMapper = conversationMapper;
        this.currentUserService = currentUserService;
    }

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

        validateClientAccountOrganization(
                clientAccount,
                workspace
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

        ConversationEntity saved;

        try {

            saved = conversationRepository.saveAndFlush(
                    entity
            );

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

        return conversationMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public ConversationDto getConversation(
            UUID id
    ) {

        ConversationEntity entity =
                getConversationEntity(id);

        currentUserService.requireWorkspaceAccess(
                entity.getWorkspace().getId()
        );

        return conversationMapper.toDto(entity);
    }

    @Transactional(readOnly = true)
    public List<ConversationDto>
    getWorkspaceConversations(
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
    public List<ConversationDto>
    getClientAccountConversations(
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

        return conversationRepository
                .findAllByClientAccountIdOrderByLastMessageAtDesc(
                        clientAccountId
                )
                .stream()
                .filter(conversation ->
                        currentUserService.hasWorkspaceAccess(
                                conversation
                                        .getWorkspace()
                                        .getId()
                        )
                )
                .map(conversationMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConversationDto>
    getChannelAccountConversations(
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

        return conversationRepository
                .findAllByChannelAccountIdOrderByLastMessageAtDesc(
                        channelAccountId
                )
                .stream()
                .filter(conversation ->
                        currentUserService.hasWorkspaceAccess(
                                conversation
                                        .getWorkspace()
                                        .getId()
                        )
                )
                .map(conversationMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDto getByAccounts(
            UUID channelAccountId,
            UUID clientAccountId
    ) {

        ConversationEntity entity =
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

        currentUserService.requireWorkspaceAccess(
                entity.getWorkspace().getId()
        );

        return conversationMapper.toDto(entity);
    }

    /**
     * Внутренний метод для Message Processing.
     *
     * Здесь ACL НЕ выполняется.
     *
     * Причина:
     * Message Processing будет работать не только
     * в контексте HTTP-пользователя, но и из Connector.
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

    public ConversationDto assignEmployee(
            UUID conversationId,
            UUID employeeId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        UUID workspaceId =
                conversation.getWorkspace().getId();

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        EmployeeEntity employee =
                employeeRepository.findById(employeeId)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Employee not found: "
                                                + employeeId
                                )
                        );

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

        conversation.setAssignedEmployee(
                employee
        );

        return conversationMapper.toDto(
                conversation
        );
    }

    public ConversationDto unassignEmployee(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        currentUserService.requireWorkspaceAccess(
                conversation
                        .getWorkspace()
                        .getId()
        );

        conversation.setAssignedEmployee(null);

        return conversationMapper.toDto(
                conversation
        );
    }

    public ConversationDto markAsRead(
            UUID conversationId
    ) {

        ConversationEntity conversation =
                getConversationEntity(conversationId);

        currentUserService.requireWorkspaceAccess(
                conversation
                        .getWorkspace()
                        .getId()
        );

        conversation.setUnreadCount(0);

        return conversationMapper.toDto(
                conversation
        );
    }

    /**
     * Используется Message Processing.
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
     * Используется Message Processing.
     */
    public ConversationEntity updateLastMessage(
            ConversationEntity conversation,
            Instant messageTime,
            String preview
    ) {

        conversation.setLastMessageAt(
                messageTime
        );

        conversation.setLastMessagePreview(
                preview
        );

        return conversation;
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
            UUID id
    ) {

        return conversationRepository
                .findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Conversation not found: " + id
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
                .equals(
                        clientAccount.getChannelType()
                )) {

            throw new IllegalArgumentException(
                    "ChannelAccount and ClientAccount "
                            + "must belong to the same channel type"
            );
        }
    }

    private void validateClientAccountOrganization(
            ClientAccountEntity clientAccount,
            WorkspaceEntity workspace
    ) {

        /*
         * Важно:
         * ClientAccount сам по себе не принадлежит Workspace.
         *
         * ClientAccount может использоваться
         * в нескольких Workspace через разные
         * ChannelAccount.
         *
         * Поэтому здесь НЕ нужно проверять
         * clientAccount.organization.
         *
         * Ограничение Organization обеспечивается
         * через допустимость ChannelAccount
         * внутри Workspace.
         */
    }
}