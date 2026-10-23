package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class TelegramMessageSendListener {
    private static final Logger log = LoggerFactory.getLogger(TelegramMessageSendListener.class);
    private TelegramMessageSendListener() {}

    public static final class Succeeded implements ITdlibUpdateListener<TdApi.UpdateMessageSendSucceeded> {
        private final UUID channelAccountId;
        private final MessageService messageService;

        public Succeeded(UUID channelAccountId, MessageRepository messageRepository, MessageService messageService) {
            this.channelAccountId = channelAccountId;
            this.messageService = messageService;
        }

        @Override
        public void handleNotification(TdApi.UpdateMessageSendSucceeded notification) {
            if (notification == null || notification.message == null || notification.message.id == 0
                    || notification.oldMessageId == 0 || notification.message.chatId == 0) return;
            messageService.recordExternalOutcome(channelAccountId, Long.toString(notification.message.chatId),
                    Long.toString(notification.oldMessageId), Long.toString(notification.message.id), false);
        }

        @Override public Class<TdApi.UpdateMessageSendSucceeded> notificationType() {
            return TdApi.UpdateMessageSendSucceeded.class;
        }
    }

    public static final class Failed implements ITdlibUpdateListener<TdApi.UpdateMessageSendFailed> {
        private final UUID channelAccountId;
        private final MessageService messageService;

        public Failed(UUID channelAccountId, MessageRepository messageRepository, MessageService messageService) {
            this.channelAccountId = channelAccountId;
            this.messageService = messageService;
        }

        @Override
        public void handleNotification(TdApi.UpdateMessageSendFailed notification) {
            if (notification == null || notification.message == null || notification.message.id == 0
                    || notification.oldMessageId == 0 || notification.message.chatId == 0) return;
            messageService.recordExternalOutcome(channelAccountId, Long.toString(notification.message.chatId),
                    Long.toString(notification.oldMessageId), Long.toString(notification.message.id), true);
            log.warn("Telegram send failed: channelAccountId={}, errorCode={}", channelAccountId,
                    notification.error == null ? null : notification.error.code);
        }

        @Override public Class<TdApi.UpdateMessageSendFailed> notificationType() {
            return TdApi.UpdateMessageSendFailed.class;
        }
    }

    /** Runs only after the exact attempt's result commits, including an early callback replay. */
    public static void deleteTemporaryFiles(UUID channelAccountId, UUID messageId, UUID sendAttemptId) {
        String prefix = "clientbus-telegram-" + channelAccountId + "-" + messageId + "-" + sendAttemptId + "-";
        Path directory = Path.of(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, prefix + "*")) {
            for (Path file : files) {
                try { Files.deleteIfExists(file); }
                catch (IOException e) { log.warn("Failed to delete Telegram temporary file: {}", file, e); }
            }
        } catch (IOException e) {
            log.warn("Failed to scan Telegram attempt files: channelAccountId={}, messageId={}, sendAttemptId={}",
                    channelAccountId, messageId, sendAttemptId, e);
        }
    }
}
