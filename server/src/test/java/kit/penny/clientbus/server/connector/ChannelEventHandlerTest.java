package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.common.enums.PlatformMessageEventType;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ChannelEventHandlerTest {

    @Mock
    private IMessageProcessingService messageProcessingService;

    @InjectMocks
    private ChannelEventHandler channelEventHandler;

    @Test
    void handle_delegatesEventToMessageProcessingService() {
        PlatformMessageEvent event =
                new PlatformMessageEvent(
                        UUID.randomUUID(),
                        "external-123",
                        PlatformMessageEventType.READ,
                        Instant.now(),
                        null
                );

        channelEventHandler.handle(event);

        verify(messageProcessingService)
                .processPlatformEvent(event);

        verifyNoMoreInteractions(messageProcessingService);
    }
}