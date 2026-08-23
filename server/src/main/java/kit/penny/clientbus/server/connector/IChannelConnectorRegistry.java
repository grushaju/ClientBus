package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;

public interface IChannelConnectorRegistry {

    IChannelConnector getConnector(
            ChannelType channelType
    );
}