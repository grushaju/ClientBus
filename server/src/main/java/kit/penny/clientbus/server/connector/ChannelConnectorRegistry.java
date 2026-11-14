package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.IChannelConnector;
import kit.penny.clientbus.connector.api.IChannelConnectorRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChannelConnectorRegistry
        implements IChannelConnectorRegistry {

    private final List<IChannelConnector> connectors;

    public ChannelConnectorRegistry(
            List<IChannelConnector> connectors
    ) {
        this.connectors = List.copyOf(connectors);
        for (ChannelType channelType : ChannelType.values()) {
            long matches = this.connectors.stream()
                    .filter(connector -> connector.supports(channelType))
                    .count();
            if (matches > 1) {
                throw new IllegalStateException(
                        "Multiple connectors registered for channel type: " + channelType
                );
            }
        }
    }

    @Override
    public IChannelConnector getConnector(
            ChannelType channelType
    ) {

        return connectors.stream()
                .filter(connector ->
                        connector.supports(
                                channelType
                        )
                )
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No connector registered for channel type: "
                                        + channelType
                        )
                );
    }
}
