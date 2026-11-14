package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.connector.api.host.HostChannelAccess;
import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.CHANNEL_ACCOUNT;
import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.CHANNEL_ACCOUNT_MANAGEMENT;

/**
 * 13.3: bridges the connector web-authorization port onto the server HTTP access policy.
 * The Telegram authorization endpoints moved into connector/telegram but keep the exact
 * access rules they had while living in the server module.
 */
@Component
public class ChannelAccessHostAdapter
        implements HostChannelAccess {

    private final HttpAccessPolicy accessPolicy;

    public ChannelAccessHostAdapter(
            HttpAccessPolicy accessPolicy
    ) {
        this.accessPolicy = accessPolicy;
    }

    @Override
    public void requireManagementAccess(
            UUID channelAccountId
    ) {
        accessPolicy.require(
                CHANNEL_ACCOUNT_MANAGEMENT,
                channelAccountId
        );
    }

    @Override
    public void requireReadAccess(
            UUID channelAccountId
    ) {
        accessPolicy.require(
                CHANNEL_ACCOUNT,
                channelAccountId
        );
    }
}
