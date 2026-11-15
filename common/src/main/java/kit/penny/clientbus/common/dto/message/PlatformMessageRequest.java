package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlatformMessageRequest(

        @NotNull
        UUID channelAccountId,

        @NotBlank
        String clientExternalId,

        String clientUsername,

        String clientPhone,

        String clientDisplayName,

        @NotBlank
        String senderExternalId,

        @NotBlank
        String externalId,

        @NotNull
        MessageType type,

        String content,

        String metadata,

        Instant sentAt,

        List<PlatformMessageAttachment> attachments,

        kit.penny.clientbus.common.notification.EventOrigin origin,

        /**
         * 13.2b: внешняя ссылка ДИАЛОГА платформы (например chatId Telegram), когда она
         * отличается от идентификатора участника. Nullable: старые payload резолвятся
         * по паре (аккаунт канала, участник) только при однозначном контексте.
         */
        String dialogExternalId,

        /**
         * 13.5 (живая приёмка DIKIDI): платформенный id фактического автора сообщения.
         * У каналов с несколькими сотрудниками салона на стороне платформы исходящие
         * различаются автором — по нему хост резолвит привязанного сотрудника ClientBus
         * (привязки нет — employeeId остаётся NULL). Nullable для платформ без
         * разделения авторов (telegram) и старых payload.
         */
        String authorExternalId,

        /**
         * 13.6 (отчёт о доставке): платформа подтвердила, что СОБЕСЕДНИК прочитал это
         * исходящее сообщение (DIKIDI: out && read>0 — признак подтверждён кодом веб-клиента).
         * null — платформа не сообщает/сообщение входящее (старые payload читаются как null);
         * FALSE — на момент чтения не прочитано (понижения статуса не делает).
         */
        Boolean recipientRead

) {
    public PlatformMessageRequest {
        // Legacy records without provenance must never acquire LIVE alert semantics.
        if (origin == null) origin = kit.penny.clientbus.common.notification.EventOrigin.SYNC;
    }

    public PlatformMessageRequest(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            MessageType type, String content, String metadata, Instant sentAt, List<PlatformMessageAttachment> attachments) {
        this(channelAccountId,clientExternalId,clientUsername,clientPhone,clientDisplayName,senderExternalId,
                externalId,type,content,metadata,sentAt,attachments,kit.penny.clientbus.common.notification.EventOrigin.SYNC,null,null,null);
    }

    public PlatformMessageRequest(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            MessageType type, String content, String metadata, Instant sentAt,
            List<PlatformMessageAttachment> attachments, kit.penny.clientbus.common.notification.EventOrigin origin) {
        this(channelAccountId,clientExternalId,clientUsername,clientPhone,clientDisplayName,senderExternalId,
                externalId,type,content,metadata,sentAt,attachments,origin,null,null,null);
    }

    public PlatformMessageRequest(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            MessageType type, String content, String metadata, Instant sentAt,
            List<PlatformMessageAttachment> attachments, kit.penny.clientbus.common.notification.EventOrigin origin,
            String dialogExternalId) {
        this(channelAccountId,clientExternalId,clientUsername,clientPhone,clientDisplayName,senderExternalId,
                externalId,type,content,metadata,sentAt,attachments,origin,dialogExternalId,null,null);
    }

    public PlatformMessageRequest(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            MessageType type, String content, String metadata, Instant sentAt,
            List<PlatformMessageAttachment> attachments, kit.penny.clientbus.common.notification.EventOrigin origin,
            String dialogExternalId, String authorExternalId) {
        this(channelAccountId,clientExternalId,clientUsername,clientPhone,clientDisplayName,senderExternalId,
                externalId,type,content,metadata,sentAt,attachments,origin,dialogExternalId,authorExternalId,null);
    }

    public PlatformMessageRequest withOrigin(kit.penny.clientbus.common.notification.EventOrigin value) {
        return new PlatformMessageRequest(channelAccountId,clientExternalId,clientUsername,clientPhone,clientDisplayName,
                senderExternalId,externalId,type,content,metadata,sentAt,attachments,value,dialogExternalId,authorExternalId,
                recipientRead);
    }
}
