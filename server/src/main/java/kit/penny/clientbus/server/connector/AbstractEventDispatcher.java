package kit.penny.clientbus.server.connector;

import java.util.List;

public abstract class AbstractEventDispatcher {

    private final List<IChannelListener> listeners;

    protected AbstractEventDispatcher(
            List<IChannelListener> listeners
    ) {
        this.listeners = listeners;
    }

    protected void fireChannelChanged(
            ChannelEvent event
    ) {
        for (IChannelListener listener : listeners) {
            listener.channelChanged(event);
        }
    }

}