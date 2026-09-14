package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;

import java.util.UUID;

public interface IChannelAccountLifecycle {

    boolean supports(ChannelType channelType);

    void disconnect(UUID channelAccountId);
}