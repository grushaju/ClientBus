package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import org.springframework.stereotype.Component;

@Component
public class ChannelEventHandler
        implements IChannelEventHandler {

    private final IMessageProcessingService messageProcessingService;

    public ChannelEventHandler(
            IMessageProcessingService messageProcessingService
    ) {
        this.messageProcessingService =
                messageProcessingService;
    }

    @Override
    public void handle(
            PlatformMessageEvent event
    ) {
        messageProcessingService.processPlatformEvent(
                event
        );
    }
}