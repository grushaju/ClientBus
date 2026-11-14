package kit.penny.clientbus.connector.api;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.capability.AvatarReader;
import kit.penny.clientbus.connector.api.capability.ContactFinder;
import kit.penny.clientbus.connector.api.capability.DialogReader;
import kit.penny.clientbus.connector.api.capability.HistoryReader;
import kit.penny.clientbus.connector.api.capability.MessageSender;
import kit.penny.clientbus.connector.api.capability.ReadMarker;

/**
 * A connector implementation for one or more channel types. Capabilities are declared as small
 * interfaces; the aggregate type keeps the historical single-connector dispatch of the registry
 * until callers migrate to {@link IChannelConnectorRegistry#getCapability} per capability.
 */
public interface IChannelConnector extends
        ContactFinder,
        AvatarReader,
        MessageSender,
        ReadMarker,
        DialogReader,
        HistoryReader {

    boolean supports(
            ChannelType channelType
    );
}
