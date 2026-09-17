package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.persistence.entity.MessageEntity;
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

    private static final String TEMP_FILE_PREFIX =
            "clientbus-telegram-";

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramMessageSendListener.class
            );

    private TelegramMessageSendListener() {
    }

    public static final class Succeeded
            implements ITdlibUpdateListener<
            TdApi.UpdateMessageSendSucceeded> {

        private static final Logger log =
                LoggerFactory.getLogger(Succeeded.class);

        private final UUID channelAccountId;
        private final MessageRepository messageRepository;
        private final MessageService messageService;

        public Succeeded(
                UUID channelAccountId,
                MessageRepository messageRepository,
                MessageService messageService
        ) {
            this.channelAccountId = channelAccountId;
            this.messageRepository = messageRepository;
            this.messageService = messageService;
        }

        @Override
        public void handleNotification(
                TdApi.UpdateMessageSendSucceeded notification
        ) {
            if (notification == null
                    || notification.message == null
                    || notification.message.id <= 0
                    || notification.oldMessageId <= 0) {

                return;
            }

            long telegramMessageId =
                    notification.message.id;

            long oldMessageId =
                    notification.oldMessageId;

            /*
             * oldMessageId is the local Telegram message ID
             * returned by SendMessage().
             *
             * It is the correlation key stored temporarily
             * in ClientBus Message.externalId.
             */
            String pendingExternalId =
                    Long.toString(oldMessageId);

            String externalId =
                    Long.toString(telegramMessageId);

            MessageEntity message =
                    messageRepository
                            .findByConversationChannelAccountIdAndExternalId(
                                    channelAccountId,
                                    pendingExternalId
                            )
                            .orElse(null);

            if (message == null) {

                /*
                 * This can happen if the Telegram update arrives
                 * before KafkaOutboundMessageConsumer persists
                 * the pending externalId.
                 *
                 * Do not try to correlate by telegramMessageId:
                 * it belongs to the completed Telegram message,
                 * while oldMessageId identifies the send attempt.
                 */
                log.debug(
                        "Ignoring Telegram send succeeded update: "
                                + "pending message not found, "
                                + "channelAccountId={}, oldMessageId={}, "
                                + "telegramMessageId={}",
                        channelAccountId,
                        oldMessageId,
                        telegramMessageId
                );

                return;
            }

            /*
             * markSent() performs the final transition:
             *
             * QUEUED + PENDING
             *        ->
             * PROCESSED + SENT
             *
             * and replaces the temporary externalId
             * with the final Telegram message ID.
             */
            messageService.markSent(
                    message.getId(),
                    externalId
            );

            /*
             * Temporary attachment files belong to this
             * ClientBus Message/send attempt.
             *
             * They are deleted only after Telegram confirms
             * successful sending.
             */
            deleteTemporaryFiles(
                    channelAccountId,
                    message.getId()
            );

            log.debug(
                    "Telegram message send succeeded: "
                            + "channelAccountId={}, messageId={}, "
                            + "oldMessageId={}, telegramMessageId={}",
                    channelAccountId,
                    message.getId(),
                    oldMessageId,
                    telegramMessageId
            );
        }

        @Override
        public Class<TdApi.UpdateMessageSendSucceeded>
        notificationType() {

            return TdApi.UpdateMessageSendSucceeded.class;
        }
    }

    public static final class Failed
            implements ITdlibUpdateListener<
            TdApi.UpdateMessageSendFailed> {

        private static final Logger log =
                LoggerFactory.getLogger(Failed.class);

        private final UUID channelAccountId;
        private final MessageRepository messageRepository;
        private final MessageService messageService;

        public Failed(
                UUID channelAccountId,
                MessageRepository messageRepository,
                MessageService messageService
        ) {
            this.channelAccountId = channelAccountId;
            this.messageRepository = messageRepository;
            this.messageService = messageService;
        }

        @Override
        public void handleNotification(
                TdApi.UpdateMessageSendFailed notification
        ) {
            if (notification == null
                    || notification.message == null
                    || notification.message.id <= 0
                    || notification.oldMessageId <= 0) {

                return;
            }

            long telegramMessageId =
                    notification.message.id;

            long oldMessageId =
                    notification.oldMessageId;

            /*
             * For failed sends, oldMessageId remains the
             * correlation key of the send attempt.
             */
            String pendingExternalId =
                    Long.toString(oldMessageId);

            MessageEntity message =
                    messageRepository
                            .findByConversationChannelAccountIdAndExternalId(
                                    channelAccountId,
                                    pendingExternalId
                            )
                            .orElse(null);

            if (message == null) {

                /*
                 * Do not attempt correlation using
                 * notification.message.id.
                 *
                 * A failed Telegram send must not affect
                 * another ClientBus Message or another retry.
                 */
                log.debug(
                        "Ignoring Telegram send failed update: "
                                + "pending message not found, "
                                + "channelAccountId={}, oldMessageId={}, "
                                + "telegramMessageId={}",
                        channelAccountId,
                        oldMessageId,
                        telegramMessageId
                );

                return;
            }

            /*
             * Delivery becomes FAILED, while the persistent
             * MessageAttachment and Storage remain untouched.
             */
            messageService.markDeliveryFailed(
                    message.getId()
            );

            /*
             * Temporary attempt-specific files can be removed
             * after Telegram reports the terminal failure.
             */
            deleteTemporaryFiles(
                    channelAccountId,
                    message.getId()
            );

            TdApi.Error error = notification.error;

            log.warn(
                    "Telegram message send failed: "
                            + "channelAccountId={}, messageId={}, "
                            + "oldMessageId={}, telegramMessageId={}, "
                            + "errorCode={}, errorMessage={}",
                    channelAccountId,
                    message.getId(),
                    oldMessageId,
                    telegramMessageId,
                    error == null ? null : error.code,
                    error == null ? null : error.message
            );
        }

        @Override
        public Class<TdApi.UpdateMessageSendFailed>
        notificationType() {

            return TdApi.UpdateMessageSendFailed.class;
        }
    }

    private static void deleteTemporaryFiles(
            UUID channelAccountId,
            UUID messageId
    ) {
        String prefix =
                TEMP_FILE_PREFIX
                        + channelAccountId
                        + "-"
                        + messageId
                        + "-";

        Path tempDirectory =
                Path.of(
                        System.getProperty(
                                "java.io.tmpdir"
                        )
                );

        try (DirectoryStream<Path> files =
                     Files.newDirectoryStream(
                             tempDirectory,
                             prefix + "*"
                     )) {

            for (Path file : files) {
                deleteTemporaryFile(file);
            }

        } catch (IOException e) {

            log.warn(
                    "Failed to scan Telegram temporary files: "
                            + "channelAccountId={}, messageId={}, "
                            + "prefix={}",
                    channelAccountId,
                    messageId,
                    prefix,
                    e
            );
        }
    }

    private static void deleteTemporaryFile(
            Path temporaryFile
    ) {
        try {
            Files.deleteIfExists(
                    temporaryFile
            );

        } catch (IOException e) {

            log.warn(
                    "Failed to delete Telegram temporary file: {}",
                    temporaryFile,
                    e
            );
        }
    }
}
