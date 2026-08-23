package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChannelConnectorRegistry
        implements IChannelConnectorRegistry {

    private final List<IChannelConnector> connectors;

    public ChannelConnectorRegistry(
            List<IChannelConnector> connectors
    ) {
        this.connectors = connectors;
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