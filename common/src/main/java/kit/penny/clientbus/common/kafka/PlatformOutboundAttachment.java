package kit.penny.clientbus.common.kafka;

import kit.penny.clientbus.common.enums.MessageAttachmentType;

public record PlatformOutboundAttachment(

        MessageAttachmentType type,

        String storageKey,

        String fileName,

        String contentType,

        long size

) {
}