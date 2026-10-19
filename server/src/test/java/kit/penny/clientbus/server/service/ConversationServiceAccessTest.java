package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.server.mapper.ConversationMapper;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.*;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceAccessTest {
    @Mock ConversationRepository conversationRepository;
    @Mock ClientAccountRepository clientAccountRepository;
    @Mock ChannelAccountRepository channelAccountRepository;
    @Mock ConversationMapper conversationMapper;
    @Mock CurrentUserService currentUserService;
    @InjectMocks ConversationService service;

    final UUID workspaceId = UUID.randomUUID(), employeeId = UUID.randomUUID();

    private ConversationEntity conversation(UUID owner) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setWorkspace(workspace);
        if (owner != null) {
            EmployeeEntity employee = new EmployeeEntity();
            employee.setId(owner);
            conversation.setAssignedEmployee(employee);
        }
        return conversation;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothDirectLookupPathsRequireFullConversationAccess(boolean byAccounts) {
        ConversationEntity conversation = conversation(UUID.randomUUID());
        UUID channelId = UUID.randomUUID(), accountId = UUID.randomUUID();
        if (byAccounts) when(conversationRepository.findByChannelAccountIdAndClientAccountId(channelId, accountId)).thenReturn(Optional.of(conversation));
        else when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        doThrow(new AccessDeniedException("Assigned to another employee")).when(currentUserService).requireConversationAccess(conversation);
        assertThrows(AccessDeniedException.class, () -> {
            if (byAccounts) service.getByAccounts(channelId, accountId);
            else service.getConversation(conversation.getId());
        });
        verifyNoInteractions(conversationMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "client", "channel"})
    void legacyListsDoNotExposeOtherEmployeesConversations(String source) {
        ConversationEntity free = conversation(null), own = conversation(employeeId), other = conversation(UUID.randomUUID());
        List<ConversationEntity> candidates = List.of(free, own, other);
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);
        ConversationDto dto = mock(ConversationDto.class);
        when(conversationMapper.toDto(free)).thenReturn(dto);
        when(conversationMapper.toDto(own)).thenReturn(dto);
        UUID accountId = UUID.randomUUID();
        List<ConversationDto> result;
        switch (source) {
            case "workspace" -> {
                when(conversationRepository.findAllByWorkspaceIdOrderByLastMessageAtDesc(workspaceId)).thenReturn(candidates);
                result = service.getWorkspaceConversations(workspaceId);
                verify(currentUserService).requireWorkspaceAccess(workspaceId);
            }
            case "client" -> {
                when(clientAccountRepository.findById(accountId)).thenReturn(Optional.of(new ClientAccountEntity()));
                when(conversationRepository.findAllByClientAccountIdAndEmployeeIdOrderByLastMessageAtDesc(accountId, employeeId)).thenReturn(candidates);
                result = service.getClientAccountConversations(accountId);
            }
            default -> {
                when(channelAccountRepository.findById(accountId)).thenReturn(Optional.of(new ChannelAccountEntity()));
                when(conversationRepository.findAllByChannelAccountIdAndEmployeeIdOrderByLastMessageAtDesc(accountId, employeeId)).thenReturn(candidates);
                result = service.getChannelAccountConversations(accountId);
            }
        }
        assertEquals(2, result.size());
        verify(conversationMapper, never()).toDto(other);
    }

    @Test
    void supervisorRetainsAccessToOtherEmployeesConversations() {
        ConversationEntity other = conversation(UUID.randomUUID());
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);
        when(currentUserService.isSupervisor(workspaceId)).thenReturn(true);
        when(conversationRepository.findAllByWorkspaceIdOrderByLastMessageAtDesc(workspaceId)).thenReturn(List.of(other));
        when(conversationMapper.toDto(other)).thenReturn(mock(ConversationDto.class));
        assertEquals(1, service.getWorkspaceConversations(workspaceId).size());
    }

    @Test
    void deniedWorkspaceIsRejectedBeforeReadingConversations() {
        doThrow(new AccessDeniedException("Workspace access denied")).when(currentUserService).requireWorkspaceAccess(workspaceId);
        assertThrows(AccessDeniedException.class, () -> service.getWorkspaceConversations(workspaceId));
        verifyNoInteractions(conversationRepository, conversationMapper);
    }
}
