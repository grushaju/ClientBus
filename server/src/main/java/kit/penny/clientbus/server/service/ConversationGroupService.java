
package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.common.dto.conversation.ConversationGroupDto;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationGroupEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationGroupMembershipEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationGroupMembershipRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationGroupRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ConversationGroupService {

    private final ConversationGroupRepository groupRepository;
    private final ConversationGroupMembershipRepository membershipRepository;
    private final ConversationRepository conversationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CurrentUserService currentUserService;
    private final ConversationMapper conversationMapper;

    public ConversationGroupService(
            ConversationGroupRepository groupRepository,
            ConversationGroupMembershipRepository membershipRepository,
            ConversationRepository conversationRepository,
            WorkspaceRepository workspaceRepository,
            CurrentUserService currentUserService,
            ConversationMapper conversationMapper
    ) {
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.conversationRepository = conversationRepository;
        this.workspaceRepository = workspaceRepository;
        this.currentUserService = currentUserService;
        this.conversationMapper = conversationMapper;
    }

    @Transactional(readOnly = true)
    public List<ConversationGroupDto> getGroups(UUID workspaceId) {
        currentUserService.requireWorkspaceAccess(workspaceId);

        UUID employeeId = requireEmployee();

        return groupRepository
                .findAllByWorkspaceIdAndOwnerEmployeeIdOrderByNameAsc(
                        workspaceId,
                        employeeId
                )
                .stream()
                .map(this::toDto)
                .toList();
    }

    public ConversationGroupDto createGroup(
            UUID workspaceId,
            String rawName
    ) {
        currentUserService.requireWorkspaceAccess(workspaceId);

        EmployeeEntity employee = currentUserService.getCurrentEmployee();
        String name = normalizeName(rawName);

        if (groupRepository
                .existsByWorkspaceIdAndOwnerEmployeeIdAndNameIgnoreCase(
                        workspaceId,
                        employee.getId(),
                        name
                )) {
            throw new IllegalArgumentException(
                    "A group with this name already exists"
            );
        }

        WorkspaceEntity workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Workspace not found: " + workspaceId
                ));

        ConversationGroupEntity group = new ConversationGroupEntity();
        group.setWorkspace(workspace);
        group.setOwnerEmployee(employee);
        group.setName(name);

        try {
            return toDto(groupRepository.saveAndFlush(group));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(
                    "A group with this name already exists",
                    ex
            );
        }
    }

    public ConversationGroupDto renameGroup(
            UUID workspaceId,
            UUID groupId,
            String rawName
    ) {
        ConversationGroupEntity group =
                requireOwnedGroup(workspaceId, groupId);

        String name = normalizeName(rawName);

        if (!group.getName().equalsIgnoreCase(name)
                && groupRepository
                .existsByWorkspaceIdAndOwnerEmployeeIdAndNameIgnoreCase(
                        workspaceId,
                        group.getOwnerEmployee().getId(),
                        name
                )) {
            throw new IllegalArgumentException(
                    "A group with this name already exists"
            );
        }

        group.setName(name);

        try {
            return toDto(groupRepository.saveAndFlush(group));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(
                    "A group with this name already exists",
                    ex
            );
        }
    }

    public void deleteGroup(UUID workspaceId, UUID groupId) {
        ConversationGroupEntity group =
                requireOwnedGroup(workspaceId, groupId);

        membershipRepository.deleteAllByGroupId(group.getId());
        groupRepository.delete(group);
    }

    public void addConversation(
            UUID workspaceId,
            UUID groupId,
            UUID conversationId
    ) {
        ConversationGroupEntity group =
                requireOwnedGroup(workspaceId, groupId);

        ConversationEntity conversation = conversationRepository
                .findById(conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Conversation not found: " + conversationId
                ));

        if (!conversation.getWorkspace().getId().equals(workspaceId)) {
            throw new EntityNotFoundException(
                    "Conversation not found in workspace: " + conversationId
            );
        }

        currentUserService.requireConversationAccess(conversation);

        UUID employeeId = group.getOwnerEmployee().getId();

        if (conversation.getAssignedEmployee() == null
                || !conversation.getAssignedEmployee()
                .getId()
                .equals(employeeId)) {
            throw new AccessDeniedException(
                    "Only conversations assigned to the group owner "
                            + "can be added to a personal group"
            );
        }

        if (membershipRepository
                .existsByGroupIdAndConversationId(
                        groupId,
                        conversationId
                )) {
            return;
        }

        ConversationGroupMembershipEntity membership =
                new ConversationGroupMembershipEntity();
        membership.setGroup(group);
        membership.setConversation(conversation);

        membershipRepository.save(membership);
    }

    public void removeConversation(
            UUID workspaceId,
            UUID groupId,
            UUID conversationId
    ) {
        ConversationGroupEntity group =
                requireOwnedGroup(workspaceId, groupId);

        membershipRepository
                .findByGroupIdAndConversationId(
                        group.getId(),
                        conversationId
                )
                .ifPresent(membershipRepository::delete);
    }


    @Transactional(readOnly = true)
    public List<ConversationDto> getGroupConversations(
            UUID workspaceId,
            UUID groupId
    ) {
        ConversationGroupEntity group =
                requireOwnedGroup(workspaceId, groupId);

        UUID employeeId = group.getOwnerEmployee().getId();

        return membershipRepository.findAccessibleMemberships(
                        group.getId(),
                        workspaceId,
                        employeeId
                )
                .stream()
                .map(membership ->
                        conversationMapper.toDto(
                                membership.getConversation()
                        )
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UUID> getConversationGroupIds(
            UUID workspaceId,
            UUID conversationId
    ) {
        currentUserService.requireWorkspaceAccess(workspaceId);

        UUID employeeId = requireEmployee();

        ConversationEntity conversation = conversationRepository
                .findById(conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Conversation not found: " + conversationId
                ));

        if (!conversation.getWorkspace().getId().equals(workspaceId)) {
            throw new EntityNotFoundException(
                    "Conversation not found in workspace: " + conversationId
            );
        }

        currentUserService.requireConversationAccess(conversation);

        if (conversation.getAssignedEmployee() == null
                || !conversation.getAssignedEmployee().getId().equals(employeeId)) {
            throw new AccessDeniedException(
                    "Only the assigned employee can manage personal group membership"
            );
        }

        return membershipRepository.findGroupIdsForAssignedConversation(
                conversationId,
                workspaceId,
                employeeId
        );
    }


    private ConversationGroupEntity requireOwnedGroup(
            UUID workspaceId,
            UUID groupId
    ) {
        currentUserService.requireWorkspaceAccess(workspaceId);

        UUID employeeId = requireEmployee();

        return groupRepository
                .findByIdAndWorkspaceIdAndOwnerEmployeeId(
                        groupId,
                        workspaceId,
                        employeeId
                )
                .orElseThrow(() -> new EntityNotFoundException(
                        "Conversation group not found: " + groupId
                ));
    }

    private UUID requireEmployee() {
        if (!currentUserService.isEmployee()) {
            throw new AccessDeniedException(
                    "Personal conversation groups are available to employees only"
            );
        }

        return currentUserService.getCurrentEmployeeId();
    }

    private String normalizeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException(
                    "Group name must not be blank"
            );
        }

        String name = rawName.trim();

        if (name.length() > 100) {
            throw new IllegalArgumentException(
                    "Group name must not exceed 100 characters"
            );
        }

        return name;
    }

    private ConversationGroupDto toDto(
            ConversationGroupEntity group
    ) {
        return new ConversationGroupDto(
                group.getId(),
                group.getWorkspace().getId(),
                group.getOwnerEmployee().getId(),
                group.getName(),
                group.getCreatedAt(),
                group.getUpdatedAt()
        );
    }
}
