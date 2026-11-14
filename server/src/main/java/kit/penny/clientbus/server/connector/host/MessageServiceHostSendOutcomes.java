package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.connector.api.host.HostSendOutcomes;
import kit.penny.clientbus.server.service.MessageService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Host side of the send outcome port: attempt bookkeeping stays transactional in MessageService. */
@Component
public class MessageServiceHostSendOutcomes
        implements HostSendOutcomes {

    private final MessageService messageService;

    public MessageServiceHostSendOutcomes(
            MessageService messageService
    ) {
        this.messageService = messageService;
    }

    @Override
    public void recordExternalOutcome(
            UUID channelAccountId,
            String chatExternalId,
            String oldExternalId,
            String newExternalId,
            boolean failed
    ) {
        messageService.recordExternalOutcome(
                channelAccountId,
                chatExternalId,
                oldExternalId,
                newExternalId,
                failed
        );
    }
}
