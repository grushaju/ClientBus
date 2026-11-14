package kit.penny.clientbus.connector.api.host;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.ChannelConnectionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Host port over channel account persistence. Lookups return neutral snapshots; profile writes
 * are the host's transactional responsibility. Workspace authorization checks never leak the
 * current user service to the connector.
 */
public interface HostChannelAccounts {

    /**
     * @throws IllegalStateException when the account does not exist
     *         (the host keeps the historical message "Telegram channel account not found: ").
     */
    HostChannelAccountSnapshot require(
            UUID channelAccountId
    );

    Optional<HostChannelAccountSnapshot> find(
            UUID channelAccountId
    );

    List<UUID> findIdsByChannelTypeAndChannelStatuses(
            ChannelType channelType,
            List<ChannelConnectionStatus> channelStatuses
    );

    void updateProfile(
            UUID channelAccountId,
            String externalId,
            String username,
            String phone,
            String displayName,
            String avatarUrl
    );

    /** Host-side workspace check for the current user; throws when access is not permitted. */
    void requireWorkspaceAccessForAccount(
            UUID channelAccountId
    );

    /** Existing ClientBus client account for the external id, or null. */
    UUID findExistingClientAccountId(
            ChannelType channelType,
            String externalId
    );
}
