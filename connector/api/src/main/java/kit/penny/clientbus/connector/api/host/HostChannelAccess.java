package kit.penny.clientbus.connector.api.host;

import java.util.UUID;

/**
 * 13.3: host-side HTTP authorization port for connector-owned web endpoints.
 *
 * <p>Connector modules may expose thin platform web APIs (for example the Telegram
 * authorization flow) without importing server security classes. The server adapter
 * maps these calls onto its HTTP access policy, keeping the current authorization
 * semantics of the moved endpoints unchanged.
 */
public interface HostChannelAccess {

    /** Throws the host's standard denial when the current principal may not manage the account. */
    void requireManagementAccess(UUID channelAccountId);

    /** Throws the host's standard denial when the current principal may not read the account. */
    void requireReadAccess(UUID channelAccountId);
}
