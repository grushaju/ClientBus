package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;

public interface IChannelEventHandler {

    void handle(
            PlatformMessageEvent event
    );
}