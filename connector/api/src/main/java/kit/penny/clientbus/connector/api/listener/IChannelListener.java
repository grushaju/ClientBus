package kit.penny.clientbus.connector.api.listener;

import kit.penny.clientbus.connector.api.event.ChannelEvent;

import java.util.EventListener;

public interface IChannelListener extends EventListener {

    void channelChanged(ChannelEvent event);
}
