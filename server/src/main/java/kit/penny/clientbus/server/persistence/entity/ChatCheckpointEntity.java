package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Курсор синхронизации одного DIKIDI-диалога (13.5). Хост хранит состояние
 * initial sync и крайние переданные внешние id сообщений; продвигается только
 * после надёжной передачи, повторы гасятся дедупом сообщений по externalid.
 */
@Entity
@Table(
        name = "chatcheckpoint",
        uniqueConstraints = @UniqueConstraint(
                name = "chatcheckpoint_dialog_uix",
                columnNames = {"channelaccountid", "dialogexternalid"}
        )
)
public class ChatCheckpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "chatcheckpointid", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "channelaccountid", nullable = false)
    private UUID channelAccountId;

    @Column(name = "dialogexternalid", nullable = false, length = 200)
    private String dialogExternalId;

    @Column(name = "newestmessageid", length = 64)
    private String newestMessageId;

    @Column(name = "oldestloadedid", length = 64)
    private String oldestLoadedId;

    @Column(name = "livebeforeid", length = 64)
    private String liveBeforeId;
    @Column(name = "livenewestid", length = 64)
    private String liveNewestId;

    public String getLiveBeforeId() { return liveBeforeId; }
    public void setLiveBeforeId(String value) { liveBeforeId = value; }
    public String getLiveNewestId() { return liveNewestId; }
    public void setLiveNewestId(String value) { liveNewestId = value; }

    @Column(name = "initialsyncdone", nullable = false)
    private boolean initialSyncDone;

    @Column(name = "historystartreached", nullable = false)
    private boolean historyStartReached;

    @Column(name = "lastdialogepoch", nullable = false)
    private long lastDialogEpoch;

    public ChatCheckpointEntity() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getChannelAccountId() {
        return channelAccountId;
    }

    public void setChannelAccountId(UUID channelAccountId) {
        this.channelAccountId = channelAccountId;
    }

    public String getDialogExternalId() {
        return dialogExternalId;
    }

    public void setDialogExternalId(String dialogExternalId) {
        this.dialogExternalId = dialogExternalId;
    }

    public String getNewestMessageId() {
        return newestMessageId;
    }

    public void setNewestMessageId(String newestMessageId) {
        this.newestMessageId = newestMessageId;
    }

    public String getOldestLoadedId() {
        return oldestLoadedId;
    }

    public void setOldestLoadedId(String oldestLoadedId) {
        this.oldestLoadedId = oldestLoadedId;
    }

    public boolean isInitialSyncDone() {
        return initialSyncDone;
    }

    public void setInitialSyncDone(boolean initialSyncDone) {
        this.initialSyncDone = initialSyncDone;
    }

    public boolean isHistoryStartReached() {
        return historyStartReached;
    }

    public void setHistoryStartReached(boolean historyStartReached) {
        this.historyStartReached = historyStartReached;
    }

    public long getLastDialogEpoch() {
        return lastDialogEpoch;
    }

    public void setLastDialogEpoch(long lastDialogEpoch) {
        this.lastDialogEpoch = lastDialogEpoch;
    }
}
