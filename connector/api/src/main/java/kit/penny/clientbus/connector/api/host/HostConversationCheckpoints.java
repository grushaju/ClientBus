package kit.penny.clientbus.connector.api.host;

import java.util.Optional;
import java.util.UUID;

/**
 * Host port for connector-side per-dialog synchronization cursors (13.5). The host owns
 * persistence and transactionality; the connector treats the payload as its own opaque state.
 * Advancing a checkpoint must happen only after the messages it covers were reliably handed to
 * the host — replays are expected to overlap and are deduplicated downstream by external ids.
 */
public interface HostConversationCheckpoints {

    Optional<ConversationCheckpoint> load(
            UUID channelAccountId,
            String dialogExternalId
    );

    void save(
            UUID channelAccountId,
            String dialogExternalId,
            ConversationCheckpoint value
    );

    /** Called by the host only after the covered page was persisted or acknowledged by its broker. */
    default void acknowledge(Update update) {
        if (java.util.Objects.equals(load(update.channelAccountId(), update.dialogExternalId()).orElse(null), update.expected()))
            save(update.channelAccountId(), update.dialogExternalId(), update.value());
    }

    record Update(UUID channelAccountId, String accountExternalId, String dialogExternalId,
                  ConversationCheckpoint expected, ConversationCheckpoint value) { }

    /**
     * Cursor state of one dialog: newest/oldest external message ids already handed over,
     * whether the initial import finished ({@code initialSyncDone}) and whether the external
     * history start was proven ({@code historyStartReached}). {@code lastDialogEpoch} mirrors
     * the dialog activity stamp the cursor was taken at, for cheap change detection.
     */
    record ConversationCheckpoint(
            String newestMessageId,
            String oldestLoadedId,
            boolean initialSyncDone,
            boolean historyStartReached,
            long lastDialogEpoch,
            String liveBeforeId,
            String liveNewestId
    ) {
        public ConversationCheckpoint(String newestMessageId, String oldestLoadedId, boolean initialSyncDone,
                boolean historyStartReached, long lastDialogEpoch) {
            this(newestMessageId, oldestLoadedId, initialSyncDone, historyStartReached, lastDialogEpoch, null, null);
        }
    }
}
