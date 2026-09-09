package kit.penny.clientbus.server.connector.telegram.authorization;

import kit.penny.clientbus.server.connector.telegram.account.TelegramChannelAccountService;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientLifecycleService;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private TelegramAuthorizationService service;

    private UUID channelAccountId;

    @BeforeEach
    void setUp() {
        service = new TelegramAuthorizationService(
                lifecycleService,
                channelAccountService
        );

        channelAccountId = UUID.randomUUID();

        when(lifecycleService.require(channelAccountId))
                .thenReturn(clientContext);

        when(clientContext.authorizationManager())
                .thenReturn(authorizationManager);
    }

    @Test
    void getStatus_shouldMapStatus() {

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

        service.submitCode(
                channelAccountId,
                "12345"
        );

        verify(authorizationManager)
                .checkAuthenticationCode("12345");
    }

    @Test
    void submitPassword_shouldDelegateToAuthorizationManager() {

        service.submitPassword(
                channelAccountId,
                "password"
        );

        verify(authorizationManager)
                .checkAuthenticationPassword("password");
    }

    @Test
    void submitEmail_shouldDelegateToAuthorizationManager() {

        service.submitEmail(
                channelAccountId,
                "test@example.com"
        );

        verify(authorizationManager)
                .checkEmailAddress("test@example.com");
    }
}