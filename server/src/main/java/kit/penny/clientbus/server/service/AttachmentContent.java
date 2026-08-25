package kit.penny.clientbus.server.service;

import java.io.InputStream;

/**
 * Binary attachment supplied as part of Message application input.
 *
 * This type is intentionally server-side and transport-neutral.
 *
 * The stream is consumed once while the Message processing
 * pipeline stores the attachment.
 */
public record AttachmentContent(

        String fileName,

        String contentType,

        long size,

        InputStream inputStream

) {

    public AttachmentContent {
        if (size < 0) {
            throw new IllegalArgumentException(
                    "Attachment size must not be negative"
            );
        }

        if (inputStream == null) {
            throw new IllegalArgumentException(
                    "Attachment inputStream must not be null"
            );
        }
    }
}