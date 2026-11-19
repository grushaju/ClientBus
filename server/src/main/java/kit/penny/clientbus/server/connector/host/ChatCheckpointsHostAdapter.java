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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ChatCheckpointRepository checkpoints;
    public ChatCheckpointsHostAdapter(ChatCheckpointRepository checkpoints) { this.checkpoints = checkpoints; }

    @Override @Transactional
    public void acknowledge(Update update) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                "chat-checkpoint/" + update.channelAccountId() + "/" + update.dialogExternalId());
        Boolean current = jdbc.queryForObject("""
                select exists(select 1 from dbo.channelaccount ca join dbo.channel ch on ch.channelid=ca.channelid
                  where ca.channelaccountid=? and ca.externalid=? and ch.status='CONNECTED')
                """, Boolean.class, update.channelAccountId(), update.accountExternalId());
        if (!Boolean.TRUE.equals(current)) throw new IllegalStateException("Chat binding changed before page acknowledgement");
        HostConversationCheckpoints.super.acknowledge(update);
    }

    @Override public Optional<ConversationCheckpoint> load(UUID channelAccountId, String dialogExternalId) {
        var row = checkpoints.findByChannelAccountIdAndDialogExternalId(channelAccountId, dialogExternalId);
        return Optional.ofNullable(row).map(value -> new ConversationCheckpoint(value.getNewestMessageId(),
                value.getOldestLoadedId(), value.isInitialSyncDone(), value.isHistoryStartReached(),
                value.getLastDialogEpoch(), value.getLiveBeforeId(), value.getLiveNewestId()));
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
        row.setLiveBeforeId(value.liveBeforeId());
        row.setLiveNewestId(value.liveNewestId());
        row.setInitialSyncDone(value.initialSyncDone());
        row.setHistoryStartReached(value.historyStartReached());
        row.setLastDialogEpoch(value.lastDialogEpoch());
        checkpoints.saveAndFlush(row);
    }
}
