package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.IChannelAccountLifecycle;
import kit.penny.clientbus.server.connector.ChannelAccountLifecycleRegistry;
import kit.penny.clientbus.server.mapper.ChannelMapper;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ChannelServiceTest {

    private ChannelRepository channelRepository;
    private ChannelAccountRepository channelAccountRepository;
    private WorkspaceRepository workspaceRepository;
    private ChannelMapper channelMapper;
    private CurrentUserService currentUserService;
    private ChannelAccountLifecycleRegistry lifecycleRegistry;
    private IChannelAccountLifecycle lifecycle;

    private ChannelService service;

    @BeforeEach
    void setUp() {
        channelRepository =
                mock(ChannelRepository.class);

        channelAccountRepository =
                mock(ChannelAccountRepository.class);

        workspaceRepository =
                mock(WorkspaceRepository.class);

        channelMapper =
                mock(ChannelMapper.class);

        currentUserService =
                mock(CurrentUserService.class);

        lifecycleRegistry =
                mock(ChannelAccountLifecycleRegistry.class);

        lifecycle =
                mock(IChannelAccountLifecycle.class);

        service = new ChannelService(
                channelRepository,
                channelAccountRepository,
                workspaceRepository,
                channelMapper,
                currentUserService,
                lifecycleRegistry
        );
    }

    @Test
    void disconnectChannelAccountShouldDisconnectLifecycleBeforeDelete() {
        UUID channelId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        WorkspaceEntity workspace =
                mock(WorkspaceEntity.class);

        ChannelEntity channel =
                mock(ChannelEntity.class);

        ChannelAccountEntity account =
                mock(ChannelAccountEntity.class);

        when(channel.getId())
                .thenReturn(channelId);

        when(channel.getWorkspace())
                .thenReturn(workspace);

        when(workspace.getId())
                .thenReturn(workspaceId);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(account.getId())
                .thenReturn(accountId);

        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel));

        when(channelAccountRepository.findByChannelId(channelId))
                .thenReturn(Optional.of(account));

        when(lifecycleRegistry.getLifecycle(ChannelType.TELEGRAM))
                .thenReturn(lifecycle);

        service.disconnectChannelAccount(channelId);

        verify(currentUserService)
                .requireWorkspaceAccess(workspaceId);

        verify(lifecycleRegistry)
                .getLifecycle(ChannelType.TELEGRAM);

        InOrder inOrder =
                inOrder(lifecycle, channelAccountRepository);

        inOrder.verify(lifecycle)
                .disconnect(accountId);

        inOrder.verify(channelAccountRepository)
                .delete(account);
    }

    @Test
    void disconnectChannelAccountShouldNotDeleteAccountWhenLifecycleFails() {
        UUID channelId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        WorkspaceEntity workspace =
                mock(WorkspaceEntity.class);

        ChannelEntity channel =
                mock(ChannelEntity.class);

        ChannelAccountEntity account =
                mock(ChannelAccountEntity.class);

        when(channel.getId())
                .thenReturn(channelId);

        when(channel.getWorkspace())
                .thenReturn(workspace);

        when(workspace.getId())
                .thenReturn(workspaceId);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(account.getId())
                .thenReturn(accountId);

        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel));

        when(channelAccountRepository.findByChannelId(channelId))
                .thenReturn(Optional.of(account));

        when(lifecycleRegistry.getLifecycle(ChannelType.TELEGRAM))
                .thenReturn(lifecycle);

        doThrow(new IllegalStateException("Logout failed"))
                .when(lifecycle)
                .disconnect(accountId);

        assertThrows(
                IllegalStateException.class,
                () -> service.disconnectChannelAccount(channelId)
        );

        verify(lifecycle)
                .disconnect(accountId);

        verify(channelAccountRepository, never())
                .delete(any());
    }

    @Test
    void deleteChannelShouldDisconnectLifecycleBeforeDelete() {
        UUID channelId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        WorkspaceEntity workspace =
                mock(WorkspaceEntity.class);

        ChannelEntity channel =
                mock(ChannelEntity.class);

        ChannelAccountEntity account =
                mock(ChannelAccountEntity.class);

        when(channel.getId())
                .thenReturn(channelId);

        when(channel.getWorkspace())
                .thenReturn(workspace);

        when(workspace.getId())
                .thenReturn(workspaceId);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(account.getId())
                .thenReturn(accountId);

        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel));

        when(channelAccountRepository.findByChannelId(channelId))
                .thenReturn(Optional.of(account));

        when(lifecycleRegistry.getLifecycle(ChannelType.TELEGRAM))
                .thenReturn(lifecycle);

        service.deleteChannel(channelId);

        verify(currentUserService)
                .requireWorkspaceAccess(workspaceId);

        verify(lifecycleRegistry)
                .getLifecycle(ChannelType.TELEGRAM);

        InOrder inOrder =
                inOrder(lifecycle, channelRepository);

        inOrder.verify(lifecycle)
                .disconnect(accountId);

        inOrder.verify(channelRepository)
                .delete(channel);
    }

    @Test
    void deleteChannelShouldNotDeleteChannelWhenLifecycleFails() {
        UUID channelId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();

        WorkspaceEntity workspace =
                mock(WorkspaceEntity.class);

        ChannelEntity channel =
                mock(ChannelEntity.class);

        ChannelAccountEntity account =
                mock(ChannelAccountEntity.class);

        when(channel.getId())
                .thenReturn(channelId);

        when(channel.getWorkspace())
                .thenReturn(workspace);

        when(workspace.getId())
                .thenReturn(workspaceId);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(account.getId())
                .thenReturn(accountId);

        when(channelRepository.findById(channelId))
                .thenReturn(Optional.of(channel));

        when(channelAccountRepository.findByChannelId(channelId))
                .thenReturn(Optional.of(account));

        when(lifecycleRegistry.getLifecycle(ChannelType.TELEGRAM))
                .thenReturn(lifecycle);

        doThrow(new IllegalStateException("Logout failed"))
                .when(lifecycle)
                .disconnect(accountId);

        assertThrows(
                IllegalStateException.class,
                () -> service.deleteChannel(channelId)
        );

        verify(lifecycle)
                .disconnect(accountId);

        verify(channelRepository, never())
                .delete(any());
    }
}
