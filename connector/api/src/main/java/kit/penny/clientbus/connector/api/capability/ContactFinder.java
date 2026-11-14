package kit.penny.clientbus.connector.api.capability;

import kit.penny.clientbus.connector.api.model.DiscoveredClientAccount;
import kit.penny.clientbus.connector.api.command.DiscoverClientAccountCommand;

/** Optional capability: look up an external client account before a conversation exists. */
public interface ContactFinder {

    default DiscoveredClientAccount handle(
            DiscoverClientAccountCommand command
    ) {
        throw new UnsupportedOperationException("Contact discovery is not supported by this connector");
    }
}
