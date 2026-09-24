package kit.penny.clientbus.server.connector.telegram.authorization;

import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
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
import kit.penny.tdlib.query.TdlibResponse;
import kit.penny.tdlib.service.TelegramUserService;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private ConfigurableApplicationContext applicationContext;

    @Mock
    private TelegramUserService telegramUserService;

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
                currentUserService
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

        when(clientContext.applicationContext())
                .thenReturn(applicationContext);

        when(applicationContext.getBean(TelegramUserService.class))
                .thenReturn(telegramUserService);
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

        TdApi.User user = new TdApi.User();

        user.id = 123456789L;
        user.firstName = "Pavel";
        user.lastName = "Grushin";
        user.phoneNumber = "+491234567890";

        user.usernames = new TdApi.Usernames(
                new String[]{"pavel"},
                new String[0],
                "pavel",
                new String[0]
        );

        when(telegramUserService.searchUserByUsername("pavel"))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        when(clientAccountRepository.findByChannelTypeAndExternalId(
                ChannelType.TELEGRAM,
                "123456789"
        )).thenReturn(Optional.empty());

        when(lifecycleService.require(channelAccountId))
                .thenReturn(clientContext);

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

        verify(currentUserService)
                .requireWorkspaceAccess(workspace.getId());

        verify(telegramUserService)
                .searchUserByUsername("pavel");
    }

    @Test
    void find_shouldReturnExistingClientAccountId() {
        stubConnectedTelegramChannel();

        UUID existingAccountId = UUID.randomUUID();

        TdApi.User user = new TdApi.User();

        user.id = 123456789L;
        user.firstName = "Pavel";
        user.lastName = "Grushin";
        user.phoneNumber = "";

        user.usernames = new TdApi.Usernames(
                new String[]{"pavel"},
                new String[0],
                "pavel",
                new String[0]
        );

        when(telegramUserService.searchUserByUsername("pavel"))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        ClientAccountEntity existingAccount =
                mock(ClientAccountEntity.class);

        when(existingAccount.getId())
                .thenReturn(existingAccountId);

        when(clientAccountRepository.findByChannelTypeAndExternalId(
                ChannelType.TELEGRAM,
                "123456789"
        )).thenReturn(Optional.of(existingAccount));

        when(lifecycleService.require(channelAccountId))
                .thenReturn(clientContext);

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
    }
}
