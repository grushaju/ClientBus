package kit.penny.clientbus.server.service;

import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationDialogIdentityTest {
    @Test void distinctDialogReferenceDoesNotReuseTheSameCounterpartsOtherDialog() {
        var repository = mock(ConversationRepository.class);
        var service = mock(ConversationService.class, CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "conversationRepository", repository);
        var workspace = new WorkspaceEntity(); workspace.setId(UUID.randomUUID());
        var channel = new ChannelEntity(workspace, kit.penny.clientbus.common.enums.ChannelType.DIKIDI, "company");
        var account = new ChannelAccountEntity(channel, "company", null, null, null);
        var client = new ClientAccountEntity(); client.setId(UUID.randomUUID());
        var existing = new ConversationEntity(workspace, account, client);
        existing.setDialogExternalId("dialog-A");
        when(repository.findAllByChannelAccountIdAndClientAccountId(account.getId(), client.getId())).thenReturn(List.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.createConversationInternal(account, client, "dialog-B");
        assertNotSame(existing, result);
        assertEquals("dialog-B", result.getDialogExternalId());
        assertEquals("dialog-A", existing.getDialogExternalId());
    }
}
