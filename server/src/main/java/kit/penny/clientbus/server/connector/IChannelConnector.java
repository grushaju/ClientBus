package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

public interface IChannelConnector {

    boolean supports(
            ChannelType channelType
    );

    ConnectorSendResult send(
            UUID channelAccountId,
            UUID messageId,
            OutboundMessageRequest request
    );
}