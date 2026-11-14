package kit.penny.clientbus.connector.api;

import kit.penny.clientbus.common.enums.ChannelType;

import java.util.Optional;

public interface IChannelConnectorRegistry {

    IChannelConnector getConnector(
            ChannelType channelType
    );

    /** 13.3: non-throwing lookup so generic schedulers can iterate the registered set. */
    default Optional<IChannelConnector> findConnector(
            ChannelType channelType
    ) {
        try {
            return Optional.of(getConnector(channelType));
        } catch (RuntimeException absent) {
            return Optional.empty();
        }
    }

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
