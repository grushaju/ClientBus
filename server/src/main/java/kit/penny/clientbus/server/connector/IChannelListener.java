package kit.penny.clientbus.server.connector;

import java.util.EventListener;

public interface IChannelListener extends EventListener {

    void channelChanged(ChannelEvent event);
}