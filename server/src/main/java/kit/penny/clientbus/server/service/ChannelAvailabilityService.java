
package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.server.exception.ChannelUnavailableException;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ChannelAvailabilityService {

    private final ChannelAccountRepository channelAccountRepository;

    public ChannelAvailabilityService(
            ChannelAccountRepository channelAccountRepository
    ) {
        this.channelAccountRepository = channelAccountRepository;
    }

    @Transactional(readOnly = true)
    public void requireConnected(UUID channelAccountId) {
        if (channelAccountId == null
                || channelAccountRepository
                .findByIdAndChannel_Status(
                        channelAccountId,
                        ChannelConnectionStatus.CONNECTED
                )
                .isEmpty()) {

            throw new ChannelUnavailableException(
                    "Channel is not connected: " + channelAccountId
            );
        }
    }
}
