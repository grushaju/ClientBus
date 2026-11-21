package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelAccountAvatarTest {
    @Test void changedAvatarInvalidatesWorkspaceButUnchangedOrMissingPhotoDoesNot() {
        var accounts = mock(ChannelAccountRepository.class);
        var events = mock(UiDomainEvents.class);
        var host = new ChannelAccountsHostAdapter(accounts, mock(ClientAccountRepository.class), mock(CurrentUserService.class));
        ReflectionTestUtils.setField(host, "uiEvents", events);
        var workspace = new WorkspaceEntity();
        var channel = new ChannelEntity();
        channel.setWorkspace(workspace);
        var account = new ChannelAccountEntity();
        account.setChannel(channel);
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(account));
        host.updateAvatar(id, "stored/owner.jpg");
        assertEquals("stored/owner.jpg", account.getAvatarUrl());
        verify(accounts).save(account);
        verify(events).workspace(workspace, "channels.invalidated");
        clearInvocations(accounts, events);
        host.updateAvatar(id, "stored/owner.jpg");
        host.updateAvatar(id, null);
        host.updateAvatar(id, " ");
        verify(accounts, never()).save(any());
        verifyNoInteractions(events);
        assertEquals("stored/owner.jpg", account.getAvatarUrl());
    }
}
