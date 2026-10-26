package kit.penny.clientbus.server.connector.telegram.authorization;

import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.ClientAccountAvatarPreview;
import kit.penny.clientbus.server.connector.DiscoveredClientAccount;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.IChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.command.DiscoverClientAccountCommand;
import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramAuthorizationServiceTest {

    @Mock
    private TelegramClientLifecycleService lifecycleService;

    @Mock
    private TelegramChannelAccountService channelAccountService;

    @Mock
    private TelegramClientContext clientContext;

    @Mock
    private TelegramAuthorizationManager authorizationManager;

    @Mock
    private ChannelAccountRepository channelAccountRepository;

    @Mock
    private ClientAccountRepository clientAccountRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private IChannelConnectorRegistry connectors;

    @Mock
    private IChannelConnector connector;

    @Mock
    private ChannelAccountEntity channelAccount;

    @Mock
    private ChannelEntity channel;

    @Mock
    private WorkspaceEntity workspace;

    private TelegramAuthorizationService service;

    private UUID channelAccountId;

    @BeforeEach
    void setUp() {
        service = new TelegramAuthorizationService(
                lifecycleService,
                channelAccountService,
                channelAccountRepository,
                clientAccountRepository,
                currentUserService,
                connectors
        );

        channelAccountId = UUID.randomUUID();
    }

    private void stubAuthorizationManager() {
        when(lifecycleService.require(channelAccountId))
                .thenReturn(clientContext);

        when(clientContext.authorizationManager())
                .thenReturn(authorizationManager);
    }

    private void stubConnectedTelegramChannel() {
        UUID workspaceId = UUID.randomUUID();

        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(channelAccount));

        when(channelAccount.getChannel())
                .thenReturn(channel);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(channel.getStatus())
                .thenReturn(ChannelConnectionStatus.CONNECTED);

        when(channel.getWorkspace())
                .thenReturn(workspace);

        when(workspace.getId())
                .thenReturn(workspaceId);

        when(connectors.getConnector(ChannelType.TELEGRAM))
                .thenReturn(connector);
    }

    @Test
    void getStatus_shouldMapStatus() {
        stubAuthorizationManager();

        when(authorizationManager.getStatus())
                .thenReturn(
                        kit.penny.tdlib.updates.TelegramAuthorizationStatus.WAIT_CODE
                );

        TelegramAuthorizationStatus result =
                service.getStatus(channelAccountId);

        assertEquals(
                TelegramAuthorizationStatus.WAIT_CODE,
                result
        );

        verify(lifecycleService)
                .require(channelAccountId);

        verify(clientContext)
                .authorizationManager();

        verify(authorizationManager)
                .getStatus();
    }

    @Test
    void getStatus_shouldMapAllStatuses() {
        stubAuthorizationManager();

        Stream.of(
                kit.penny.tdlib.updates.TelegramAuthorizationStatus.values()
        ).forEach(tdlibStatus -> {

            when(authorizationManager.getStatus())
                    .thenReturn(tdlibStatus);

            TelegramAuthorizationStatus result =
                    service.getStatus(channelAccountId);

            assertEquals(
                    tdlibStatus.name(),
                    result.name()
            );
        });
    }

    @Test
    void submitCode_shouldDelegateToAuthorizationManager() {
        stubAuthorizationManager();

        service.submitCode(
                channelAccountId,
                "12345"
        );

        verify(authorizationManager)
                .checkAuthenticationCode("12345");
    }

    @Test
    void submitPassword_shouldDelegateToAuthorizationManager() {
        stubAuthorizationManager();

        service.submitPassword(
                channelAccountId,
                "password"
        );

        verify(authorizationManager)
                .checkAuthenticationPassword("password");
    }

    @Test
    void submitEmail_shouldDelegateToAuthorizationManager() {
        stubAuthorizationManager();

        service.submitEmail(
                channelAccountId,
                "test@example.com"
        );

        verify(authorizationManager)
                .checkEmailAddress("test@example.com");
    }

    @Test
    void find_shouldSearchTelegramUserByUsername() {
        stubConnectedTelegramChannel();
        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, null, "pavel");
        when(connector.handle(command)).thenReturn(new DiscoveredClientAccount(
                "123456789", "pavel", "+491234567890", "Pavel Grushin",
                new ClientAccountAvatarPreview("image/jpeg", "AQID")));

        when(clientAccountRepository.findByChannelTypeAndExternalId(
                ChannelType.TELEGRAM,
                "123456789"
        )).thenReturn(Optional.empty());

        ClientAccountDiscoveryDto result =
                service.find(
                        channelAccountId,
                        null,
                        "@pavel"
                );

        assertEquals(
                "123456789",
                result.externalId()
        );

        assertEquals(
                "pavel",
                result.username()
        );

        assertEquals(
                "+491234567890",
                result.phone()
        );

        assertEquals(
                "Pavel Grushin",
                result.displayName()
        );

        assertNull(
                result.existingClientAccountId()
        );
        assertEquals("image/jpeg", result.avatarPreview().contentType());
        assertEquals("AQID", result.avatarPreview().base64());

        verify(currentUserService, times(2))
                .requireWorkspaceAccess(workspace.getId());

        verify(connectors).getConnector(ChannelType.TELEGRAM);
        verify(connector).handle(command);
        verify(clientAccountRepository).findByChannelTypeAndExternalId(ChannelType.TELEGRAM, "123456789");
        verifyNoMoreInteractions(connector, clientAccountRepository);
        verifyNoInteractions(lifecycleService, clientContext, channelAccountService);
    }

    @Test
    void find_shouldReturnExistingClientAccountId() {
        stubConnectedTelegramChannel();

        UUID existingAccountId = UUID.randomUUID();

        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, null, "pavel");
        when(connector.handle(command)).thenReturn(new DiscoveredClientAccount(
                "123456789", "pavel", null, "Pavel Grushin", null));

        ClientAccountEntity existingAccount =
                mock(ClientAccountEntity.class);

        when(existingAccount.getId())
                .thenReturn(existingAccountId);

        when(clientAccountRepository.findByChannelTypeAndExternalId(
                ChannelType.TELEGRAM,
                "123456789"
        )).thenReturn(Optional.of(existingAccount));

        ClientAccountDiscoveryDto result =
                service.find(
                        channelAccountId,
                        null,
                        "pavel"
                );

        assertEquals(
                existingAccountId,
                result.existingClientAccountId()
        );
        assertNull(result.avatarPreview());
        verify(currentUserService, times(2)).requireWorkspaceAccess(workspace.getId());
        verify(connector).handle(command);
        verify(clientAccountRepository).findByChannelTypeAndExternalId(ChannelType.TELEGRAM, "123456789");
        verifyNoMoreInteractions(connector, clientAccountRepository);
        verifyNoInteractions(lifecycleService, clientContext, channelAccountService);
    }

    @Test
    void find_shouldSearchByPhoneThroughConnectorWithoutPersistingPreview() {
        stubConnectedTelegramChannel();
        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, "+491234567890", null);
        when(connector.handle(command)).thenReturn(new DiscoveredClientAccount(
                "123456789", null, "+491234567890", "Pavel", new ClientAccountAvatarPreview("image/png", "BAUG")));
        when(clientAccountRepository.findByChannelTypeAndExternalId(ChannelType.TELEGRAM, "123456789"))
                .thenReturn(Optional.empty());

        var result = service.find(channelAccountId, "  +491234567890  ", null);

        assertEquals("123456789", result.externalId());
        assertEquals("image/png", result.avatarPreview().contentType());
        assertEquals("BAUG", result.avatarPreview().base64());
        verify(currentUserService, times(2)).requireWorkspaceAccess(workspace.getId());
        verify(connector).handle(command);
        verify(clientAccountRepository).findByChannelTypeAndExternalId(ChannelType.TELEGRAM, "123456789");
        verifyNoMoreInteractions(connector, clientAccountRepository);
        verifyNoInteractions(lifecycleService, clientContext, channelAccountService);
    }

    @Test
    void find_shouldRejectPhoneAndUsernameTogether() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.find(
                        channelAccountId,
                        "+491234567890",
                        "pavel"
                )
        );
        verifyNoInteractions(connectors, connector, clientAccountRepository, lifecycleService);
    }

    @Test
    void find_shouldRejectMissingSearchParameter() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.find(
                        channelAccountId,
                        null,
                        null
                )
        );
        verifyNoInteractions(connectors, connector, clientAccountRepository, lifecycleService);
    }

    @Test
    void find_shouldRejectDisconnectedChannel() {
        when(channelAccountRepository.findById(channelAccountId))
                .thenReturn(Optional.of(channelAccount));

        when(channelAccount.getChannel())
                .thenReturn(channel);

        when(channel.getType())
                .thenReturn(ChannelType.TELEGRAM);

        when(channel.getStatus())
                .thenReturn(ChannelConnectionStatus.DISCONNECTED);

        when(channel.getWorkspace())
                .thenReturn(workspace);

        when(workspace.getId())
                .thenReturn(UUID.randomUUID());

        assertThrows(
                IllegalStateException.class,
                () -> service.find(
                        channelAccountId,
                        null,
                        "pavel"
                )
        );
        verifyNoInteractions(connectors, connector, clientAccountRepository, lifecycleService);
    }

    @Test
    void find_shouldRejectInaccessibleWorkspaceBeforeContactDiscovery() {
        UUID workspaceId = UUID.randomUUID();
        when(channelAccountRepository.findById(channelAccountId)).thenReturn(Optional.of(channelAccount));
        when(channelAccount.getChannel()).thenReturn(channel);
        when(channel.getType()).thenReturn(ChannelType.TELEGRAM);
        when(channel.getWorkspace()).thenReturn(workspace);
        when(workspace.getId()).thenReturn(workspaceId);
        doThrow(new SecurityException("Workspace denied"))
                .when(currentUserService).requireWorkspaceAccess(workspaceId);

        assertThrows(SecurityException.class, () -> service.find(channelAccountId, null, "pavel"));

        verifyNoInteractions(connectors, connector, clientAccountRepository, lifecycleService);
    }

    @Test
    void find_shouldPropagateConnectorLookupFailureWithoutPersistingAnything() {
        stubConnectedTelegramChannel();
        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, null, "pavel");
        when(connector.handle(command)).thenThrow(new IllegalStateException("Contact unavailable"));

        assertThrows(IllegalStateException.class, () -> service.find(channelAccountId, null, "pavel"));

        verify(connector).handle(command);
        verifyNoMoreInteractions(connector);
        verifyNoInteractions(clientAccountRepository, lifecycleService, clientContext, channelAccountService);
    }

    @Test
    void find_shouldDiscardPreviewWhenWorkspaceAccessIsRevokedDuringLookup() {
        stubConnectedTelegramChannel();
        UUID workspaceId = workspace.getId();
        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, null, "pavel");
        when(connector.handle(command)).thenAnswer(invocation -> {
            doThrow(new SecurityException("Workspace revoked"))
                    .when(currentUserService).requireWorkspaceAccess(workspaceId);
            return new DiscoveredClientAccount("123456789", "pavel", null, "Pavel",
                    new ClientAccountAvatarPreview("image/jpeg", "AQID"));
        });

        assertThrows(SecurityException.class, () -> service.find(channelAccountId, null, "pavel"));

        verify(currentUserService, times(2)).requireWorkspaceAccess(workspaceId);
        verify(connector).handle(command);
        verifyNoMoreInteractions(connector);
        verifyNoInteractions(clientAccountRepository, lifecycleService, clientContext, channelAccountService);
    }
}
