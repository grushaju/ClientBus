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

        kit.penny.clientbus.common.notification.EventOrigin origin

) {
    public PlatformMessageRequest {
        // Legacy records without provenance must never acquire LIVE alert semantics.
        if (origin == null) origin = kit.penny.clientbus.common.notification.EventOrigin.SYNC;
    }

    public PlatformMessageRequest(UUID channelAccountId, String clientExternalId, String clientUsername,
            String clientPhone, String clientDisplayName, String senderExternalId, String externalId,
            MessageType type, String content, String metadata, Instant sentAt, List<PlatformMessageAttachment> attachments) {
        this(channelAccountId,clientExternalId,clientUsername,clientPhone,clientDisplayName,senderExternalId,
                externalId,type,content,metadata,sentAt,attachments,kit.penny.clientbus.common.notification.EventOrigin.SYNC);
    }

    public PlatformMessageRequest withOrigin(kit.penny.clientbus.common.notification.EventOrigin value) {
        return new PlatformMessageRequest(channelAccountId,clientExternalId,clientUsername,clientPhone,clientDisplayName,
                senderExternalId,externalId,type,content,metadata,sentAt,attachments,value);
    }
}
