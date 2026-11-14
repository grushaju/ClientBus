package kit.penny.clientbus.connector.api.model;

import kit.penny.clientbus.common.enums.MessageAttachmentType;

/** Neutral connector payload mirroring the host transport attachment field-for-field. */
public record ConnectorMessageAttachment(

        MessageAttachmentType type,

        String storageKey,

        String fileName,

        String contentType,

        long size

) {
}
