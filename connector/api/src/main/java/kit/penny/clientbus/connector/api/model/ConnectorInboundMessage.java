package kit.penny.clientbus.connector.api.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Neutral connector payload mirroring the host transport request field-for-field; the server maps
 * it 1:1 at the SPI boundary. Transitional until connector implementations produce this shape
 * natively (stage 13.3).
 */
public record ConnectorInboundMessage(

        UUID channelAccountId,

        String clientExternalId,

        String clientUsername,

        String clientPhone,

        String clientDisplayName,

        String senderExternalId,

        String externalId,

        kit.penny.clientbus.common.enums.MessageType type,

        String content,

        String metadata,

        Instant sentAt,

        List<ConnectorMessageAttachment> attachments,

        kit.penny.clientbus.common.notification.EventOrigin origin,

        /** External dialog reference (13.2b); nullable, legacy payloads resolve by counterpart. */
        String dialogExternalId,

        /**
         * Платформенный id фактического автора сообщения (13.5, живая приёмка DIKIDI):
         * у внешних каналов на стороне салона может быть несколько сотрудников — по этому
         * id хост резолвит привязанного сотрудника ClientBus (нет привязки — NULL).
         * Nullable; telegram-путь не различает авторов и оставляет null.
         */
        String authorExternalId,

        /**
         * 13.6 (отчёт о доставке): платформа подтвердила прочтение исходящего сообщения
         * собеседником (DIKIDI: out && read>0). null — платформа не различает (telegram)
         * или сообщение входящее; FALSE — не прочитано на момент чтения, понижения
         * статуса не производится.
         */
        Boolean recipientRead

) {
    public ConnectorInboundMessage {
        // Legacy records without provenance must never acquire LIVE alert semantics.
        if (origin == null) origin = kit.penny.clientbus.common.notification.EventOrigin.SYNC;
    }

    public ConnectorInboundMessage(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            kit.penny.clientbus.common.enums.MessageType type, String content, String metadata, Instant sentAt,
            List<ConnectorMessageAttachment> attachments) {
        this(channelAccountId, clientExternalId, clientUsername, clientPhone, clientDisplayName, senderExternalId,
                externalId, type, content, metadata, sentAt, attachments, kit.penny.clientbus.common.notification.EventOrigin.SYNC, null, null, null);
    }

    public ConnectorInboundMessage(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            kit.penny.clientbus.common.enums.MessageType type, String content, String metadata, Instant sentAt,
            List<ConnectorMessageAttachment> attachments, kit.penny.clientbus.common.notification.EventOrigin origin) {
        this(channelAccountId, clientExternalId, clientUsername, clientPhone, clientDisplayName, senderExternalId,
                externalId, type, content, metadata, sentAt, attachments, origin, null, null, null);
    }

    public ConnectorInboundMessage(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            kit.penny.clientbus.common.enums.MessageType type, String content, String metadata, Instant sentAt,
            List<ConnectorMessageAttachment> attachments, kit.penny.clientbus.common.notification.EventOrigin origin,
            String dialogExternalId) {
        this(channelAccountId, clientExternalId, clientUsername, clientPhone, clientDisplayName, senderExternalId,
                externalId, type, content, metadata, sentAt, attachments, origin, dialogExternalId, null, null);
    }

    public ConnectorInboundMessage(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            kit.penny.clientbus.common.enums.MessageType type, String content, String metadata, Instant sentAt,
            List<ConnectorMessageAttachment> attachments, kit.penny.clientbus.common.notification.EventOrigin origin,
            String dialogExternalId, String authorExternalId) {
        this(channelAccountId, clientExternalId, clientUsername, clientPhone, clientDisplayName, senderExternalId,
                externalId, type, content, metadata, sentAt, attachments, origin, dialogExternalId, authorExternalId, null);
    }

    public ConnectorInboundMessage withOrigin(kit.penny.clientbus.common.notification.EventOrigin value) {
        return new ConnectorInboundMessage(channelAccountId, clientExternalId, clientUsername, clientPhone, clientDisplayName,
                senderExternalId, externalId, type, content, metadata, sentAt, attachments, value, dialogExternalId,
                authorExternalId, recipientRead);
    }
}
