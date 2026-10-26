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

    @Mock
    private kit.penny.clientbus.server.security.policy.HttpAccessPolicy accessPolicy;

    private TelegramAuthorizationController controller;

    private UUID channelAccountId;

    @BeforeEach
    void setUp() {
        controller = new TelegramAuthorizationController(
                authorizationService, accessPolicy
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
    void everyHttpCommandChecksPolicyBeforeCallingConnectorFlow() {
        var actions = java.util.List.<org.junit.jupiter.api.function.Executable>of(
                () -> controller.startAuthorization(channelAccountId),
                () -> controller.getStatus(channelAccountId),
                () -> controller.submitCode(channelAccountId, new TelegramAuthorizationController.AuthenticationCodeRequest("code")),
                () -> controller.submitPassword(channelAccountId, new TelegramAuthorizationController.AuthenticationPasswordRequest("password")),
                () -> controller.submitEmail(channelAccountId, new TelegramAuthorizationController.EmailAddressRequest("email")),
                () -> controller.disable(channelAccountId), () -> controller.enable(channelAccountId),
                () -> controller.disconnect(channelAccountId), () -> controller.find(channelAccountId,"phone",null));
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
                .when(accessPolicy).require(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(channelAccountId));
        for (var action : actions) org.junit.jupiter.api.Assertions.assertThrows(org.springframework.security.access.AccessDeniedException.class, action);
        org.mockito.Mockito.verifyNoInteractions(authorizationService);
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

        var response =
                controller.find(
                        channelAccountId,
                        null,
                        "pavel"
                );

        assertEquals(
                expected,
                response.getBody()
        );
        assertEquals("no-store", response.getHeaders().getCacheControl());

        verify(
                authorizationService
        ).find(
                channelAccountId,
                null,
                "pavel"
        );
    }
}
