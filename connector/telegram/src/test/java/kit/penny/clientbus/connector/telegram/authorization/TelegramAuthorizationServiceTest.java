package kit.penny.clientbus.connector.telegram.authorization;

import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.model.ClientAccountAvatarPreview;
import kit.penny.clientbus.connector.api.model.DiscoveredClientAccount;
import kit.penny.clientbus.connector.api.IChannelConnector;
import kit.penny.clientbus.connector.api.IChannelConnectorRegistry;
import kit.penny.clientbus.connector.api.command.DiscoverClientAccountCommand;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private HostChannelAccounts hostChannelAccounts;

    @Mock
    private IChannelConnectorRegistry connectors;

    @Mock
    private IChannelConnector connector;

    private TelegramAuthorizationService service;

    private UUID channelAccountId;

    @BeforeEach
    void setUp() {
        service = new TelegramAuthorizationService(
                lifecycleService,
                channelAccountService,
                hostChannelAccounts,
                connectors
        );

        channelAccountId = UUID.randomUUID();
    }

    private static HostChannelAccountSnapshot snapshot(
            ChannelConnectionStatus channelStatus
    ) {
        return new HostChannelAccountSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ChannelType.TELEGRAM,
                channelStatus,
                "+79990000000",
                "external"
        );
    }

    private void stubAuthorizationManager() {
        when(lifecycleService.require(channelAccountId))
                .thenReturn(clientContext);

        when(clientContext.authorizationManager())
                .thenReturn(authorizationManager);
    }

    private void stubConnectedTelegramChannel() {
        when(hostChannelAccounts.find(channelAccountId))
                .thenReturn(Optional.of(
                        snapshot(ChannelConnectionStatus.CONNECTED)
                ));

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

        when(hostChannelAccounts.findExistingClientAccountId(
                ChannelType.TELEGRAM,
                "123456789"
        )).thenReturn(null);

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

        verify(hostChannelAccounts, times(2))
                .requireWorkspaceAccessForAccount(channelAccountId);

        verify(connectors).getConnector(ChannelType.TELEGRAM);
        verify(connector).handle(command);
        verify(hostChannelAccounts).findExistingClientAccountId(ChannelType.TELEGRAM, "123456789");
        verifyNoMoreInteractions(connector);
        verifyNoInteractions(lifecycleService, clientContext, channelAccountService);
    }

    @Test
    void find_shouldReturnExistingClientAccountId() {
        stubConnectedTelegramChannel();

        UUID existingAccountId = UUID.randomUUID();

        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, null, "pavel");
        when(connector.handle(command)).thenReturn(new DiscoveredClientAccount(
                "123456789", "pavel", null, "Pavel Grushin", null));

        when(hostChannelAccounts.findExistingClientAccountId(
                ChannelType.TELEGRAM,
                "123456789"
        )).thenReturn(existingAccountId);

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
        verify(hostChannelAccounts, times(2)).requireWorkspaceAccessForAccount(channelAccountId);
        verify(connector).handle(command);
        verify(hostChannelAccounts).findExistingClientAccountId(ChannelType.TELEGRAM, "123456789");
        verifyNoMoreInteractions(connector);
        verifyNoInteractions(lifecycleService, clientContext, channelAccountService);
    }

    @Test
    void find_shouldSearchByPhoneThroughConnectorWithoutPersistingPreview() {
        stubConnectedTelegramChannel();
        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, "+491234567890", null);
        when(connector.handle(command)).thenReturn(new DiscoveredClientAccount(
                "123456789", null, "+491234567890", "Pavel", new ClientAccountAvatarPreview("image/png", "BAUG")));
        when(hostChannelAccounts.findExistingClientAccountId(ChannelType.TELEGRAM, "123456789"))
                .thenReturn(null);

        var result = service.find(channelAccountId, "  +491234567890  ", null);

        assertEquals("123456789", result.externalId());
        assertEquals("image/png", result.avatarPreview().contentType());
        assertEquals("BAUG", result.avatarPreview().base64());
        verify(hostChannelAccounts, times(2)).requireWorkspaceAccessForAccount(channelAccountId);
        verify(connector).handle(command);
        verify(hostChannelAccounts).findExistingClientAccountId(ChannelType.TELEGRAM, "123456789");
        verifyNoMoreInteractions(connector);
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
        verifyNoInteractions(connectors, connector, hostChannelAccounts, lifecycleService);
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
        verifyNoInteractions(connectors, connector, hostChannelAccounts, lifecycleService);
    }

    @Test
    void find_shouldRejectDisconnectedChannel() {
        when(hostChannelAccounts.find(channelAccountId))
                .thenReturn(Optional.of(
                        snapshot(ChannelConnectionStatus.DISCONNECTED)
                ));

        assertThrows(
                IllegalStateException.class,
                () -> service.find(
                        channelAccountId,
                        null,
                        "pavel"
                )
        );
        verifyNoInteractions(connectors, connector, lifecycleService);
    }

    @Test
    void find_shouldRejectInaccessibleWorkspaceBeforeContactDiscovery() {
        when(hostChannelAccounts.find(channelAccountId))
                .thenReturn(Optional.of(
                        snapshot(ChannelConnectionStatus.CONNECTED)
                ));
        doThrow(new SecurityException("Workspace denied"))
                .when(hostChannelAccounts).requireWorkspaceAccessForAccount(channelAccountId);

        assertThrows(SecurityException.class, () -> service.find(channelAccountId, null, "pavel"));

        verifyNoInteractions(connectors, connector, lifecycleService);
    }

    @Test
    void find_shouldPropagateConnectorLookupFailureWithoutPersistingAnything() {
        stubConnectedTelegramChannel();
        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, null, "pavel");
        when(connector.handle(command)).thenThrow(new IllegalStateException("Contact unavailable"));

        assertThrows(IllegalStateException.class, () -> service.find(channelAccountId, null, "pavel"));

        verify(connector).handle(command);
        verifyNoMoreInteractions(connector);
        verifyNoInteractions(lifecycleService, clientContext, channelAccountService);
    }

    @Test
    void find_shouldDiscardPreviewWhenWorkspaceAccessIsRevokedDuringLookup() {
        stubConnectedTelegramChannel();
        DiscoverClientAccountCommand command = new DiscoverClientAccountCommand(channelAccountId, null, "pavel");
        when(connector.handle(command)).thenAnswer(invocation -> {
            doThrow(new SecurityException("Workspace revoked"))
                    .when(hostChannelAccounts).requireWorkspaceAccessForAccount(channelAccountId);
            return new DiscoveredClientAccount("123456789", "pavel", null, "Pavel",
                    new ClientAccountAvatarPreview("image/jpeg", "AQID"));
        });

        assertThrows(SecurityException.class, () -> service.find(channelAccountId, null, "pavel"));

        verify(hostChannelAccounts, times(2)).requireWorkspaceAccessForAccount(channelAccountId);
        verify(connector).handle(command);
        verifyNoMoreInteractions(connector);
        verifyNoInteractions(lifecycleService, clientContext, channelAccountService);
    }
}
