package kit.penny.clientbus.connector.api;

import kit.penny.clientbus.common.enums.ChannelType;

public interface IChannelConnectorRegistry {

    IChannelConnector getConnector(
            ChannelType channelType
    );

    /** Resolves one capability of the connector registered for the channel type. */
    default <C> C getCapability(
            ChannelType channelType,
            Class<C> capability
    ) {
        IChannelConnector connector = getConnector(channelType);
        if (!capability.isInstance(connector)) {
            throw new IllegalStateException(
                    "Connector for channel type " + channelType
                            + " does not provide capability " + capability.getSimpleName());
        }
        return capability.cast(connector);
    }
}
