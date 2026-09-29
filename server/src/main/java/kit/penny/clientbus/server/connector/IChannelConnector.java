package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.service.ChannelReadRequest;
import kit.penny.clientbus.server.service.ChannelSendRequest;

public interface IChannelConnector {

    boolean supports(
            ChannelType channelType
    );

    ConnectorSendResult send(
            ChannelSendRequest request
    );

    void markRead(
            ChannelReadRequest request
    );
}