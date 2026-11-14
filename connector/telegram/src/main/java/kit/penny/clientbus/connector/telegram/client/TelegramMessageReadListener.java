package kit.penny.clientbus.connector.telegram.client;

import kit.penny.clientbus.connector.api.host.HostMessageReadMarks;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import org.drinkless.tdlib.TdApi;

import java.util.UUID;

public class TelegramMessageReadListener
        implements ITdlibUpdateListener<TdApi.UpdateChatReadOutbox> {

    private final UUID channelAccountId;
    private final HostMessageReadMarks hostMessageReadMarks;

    public TelegramMessageReadListener(
            UUID channelAccountId,
            HostMessageReadMarks hostMessageReadMarks
    ) {
        this.channelAccountId = channelAccountId;
        this.hostMessageReadMarks = hostMessageReadMarks;
    }

    @Override
    public void handleNotification(
            TdApi.UpdateChatReadOutbox notification
    ) {
        if (notification == null) {
            return;
        }

        long chatId =
                notification.chatId;

        long lastReadOutboxMessageId =
                notification.lastReadOutboxMessageId;

        if (chatId == 0
                || lastReadOutboxMessageId <= 0) {

            return;
        }

        hostMessageReadMarks.markReadUpTo(
                channelAccountId,
                Long.toString(chatId),
                null,
                lastReadOutboxMessageId
        );
    }

    @Override
    public Class<TdApi.UpdateChatReadOutbox>
    notificationType() {
        return TdApi.UpdateChatReadOutbox.class;
    }
}
