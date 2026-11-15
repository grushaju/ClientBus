package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.connector.api.host.HostConversationCheckpoints;
import kit.penny.clientbus.server.persistence.entity.ChatCheckpointEntity;
import kit.penny.clientbus.server.persistence.repository.ChatCheckpointRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Курсоры чатовой синхронизации живут в dbo.chatcheckpoint (13.5). */
@Component
public class ChatCheckpointsHostAdapter implements HostConversationCheckpoints {
    private final ChatCheckpointRepository checkpoints;
    public ChatCheckpointsHostAdapter(ChatCheckpointRepository checkpoints) { this.checkpoints = checkpoints; }

    @Override public Optional<ConversationCheckpoint> load(UUID channelAccountId, String dialogExternalId) {
        var row = checkpoints.findByChannelAccountIdAndDialogExternalId(channelAccountId, dialogExternalId);
        return Optional.ofNullable(row).map(value -> new ConversationCheckpoint(value.getNewestMessageId(),
                value.getOldestLoadedId(), value.isInitialSyncDone(), value.isHistoryStartReached(),
                value.getLastDialogEpoch()));
    }

    @Override @Transactional
    public void save(UUID channelAccountId, String dialogExternalId, ConversationCheckpoint value) {
        var row = checkpoints.findByChannelAccountIdAndDialogExternalId(channelAccountId, dialogExternalId);
        if (row == null) {
            row = new ChatCheckpointEntity();
            row.setChannelAccountId(channelAccountId);
            row.setDialogExternalId(dialogExternalId);
        }
        row.setNewestMessageId(value.newestMessageId());
        row.setOldestLoadedId(value.oldestLoadedId());
        row.setInitialSyncDone(value.initialSyncDone());
        row.setHistoryStartReached(value.historyStartReached());
        row.setLastDialogEpoch(value.lastDialogEpoch());
        checkpoints.saveAndFlush(row);
    }
}
