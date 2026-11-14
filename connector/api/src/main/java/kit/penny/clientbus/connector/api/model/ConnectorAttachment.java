package kit.penny.clientbus.connector.api.model;

import kit.penny.clientbus.common.enums.MessageAttachmentType;

import java.io.InputStream;

/**
 * An attachment handed to a connector for one outbound attempt. The connector owns closing the
 * stream of each attempt; reopening for a new attempt is the host's responsibility.
 */
public record ConnectorAttachment(

        MessageAttachmentType type,

        String fileName,

        String contentType,

        long size,

        InputStream content

) {
}
