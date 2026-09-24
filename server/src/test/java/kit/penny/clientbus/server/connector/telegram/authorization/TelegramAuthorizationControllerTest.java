package kit.penny.clientbus.server.connector.telegram.authorization;

import kit.penny.clientbus.common.dto.channel.ClientAccountDiscoveryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramAuthorizationControllerTest {

    @Mock
    private TelegramAuthorizationService authorizationService;

    private TelegramAuthorizationController controller;

    private UUID channelAccountId;

    @BeforeEach
    void setUp() {
        controller = new TelegramAuthorizationController(
                authorizationService
        );

        channelAccountId = UUID.randomUUID();
    }

    @Test
    void getStatus_shouldDelegateToAuthorizationService() {

        when(authorizationService.getStatus(channelAccountId))
                .thenReturn(
                        TelegramAuthorizationStatus.WAIT_CODE
                );

        TelegramAuthorizationStatus result =
                controller.getStatus(channelAccountId);

        assertEquals(
                TelegramAuthorizationStatus.WAIT_CODE,
                result
        );

        verify(authorizationService)
                .getStatus(channelAccountId);
    }

    @Test
    void submitCode_shouldDelegateToAuthorizationService() {

        TelegramAuthorizationController.AuthenticationCodeRequest request =
                new TelegramAuthorizationController.AuthenticationCodeRequest(
                        "12345"
                );

        controller.submitCode(
                channelAccountId,
                request
        );

        verify(authorizationService)
                .submitCode(
                        channelAccountId,
                        "12345"
                );
    }

    @Test
    void submitPassword_shouldDelegateToAuthorizationService() {

        TelegramAuthorizationController.AuthenticationPasswordRequest request =
                new TelegramAuthorizationController.AuthenticationPasswordRequest(
                        "password"
                );

        controller.submitPassword(
                channelAccountId,
                request
        );

        verify(authorizationService)
                .submitPassword(
                        channelAccountId,
                        "password"
                );
    }

    @Test
    void submitEmail_shouldDelegateToAuthorizationService() {

        TelegramAuthorizationController.EmailAddressRequest request =
                new TelegramAuthorizationController.EmailAddressRequest(
                        "test@example.com"
                );

        controller.submitEmail(
                channelAccountId,
                request
        );

        verify(authorizationService)
                .submitEmail(
                        channelAccountId,
                        "test@example.com"
                );
    }

    @Test
    void find_shouldDelegateToAuthorizationService() {

        ClientAccountDiscoveryDto expected =
                new ClientAccountDiscoveryDto(
                        "123456789",
                        "pavel",
                        "+491234567890",
                        "Pavel Grushin",
                        null
                );

        when(
                authorizationService.find(
                        channelAccountId,
                        null,
                        "pavel"
                )
        ).thenReturn(expected);

        ClientAccountDiscoveryDto result =
                controller.find(
                        channelAccountId,
                        null,
                        "pavel"
                );

        assertEquals(
                expected,
                result
        );

        verify(
                authorizationService
        ).find(
                channelAccountId,
                null,
                "pavel"
        );
    }
}