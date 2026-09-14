package kit.penny.clientbus.server.connector;

import kit.penny.clientbus.common.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChannelAccountLifecycleRegistry {

    private final List<IChannelAccountLifecycle> lifecycles;

    public ChannelAccountLifecycleRegistry(
            List<IChannelAccountLifecycle> lifecycles
    ) {
        this.lifecycles = lifecycles;
    }

    public IChannelAccountLifecycle getLifecycle(
            ChannelType channelType
    ) {
        return lifecycles.stream()
                .filter(lifecycle ->
                        lifecycle.supports(channelType)
                )
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No channel account lifecycle registered for channel type: "
                                        + channelType
                        )
                );
    }
}