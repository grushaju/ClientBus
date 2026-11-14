package kit.penny.clientbus.connector.api.host;

import java.io.InputStream;

/**
 * Host port for persistent attachment content. The host owns storage location and access checks;
 * platform downloads/uploads and connector temporary files stay with the connector.
 */
public interface HostAttachmentStorage {

    HostStoredAttachment store(
            InputStream content,
            String fileName,
            long size,
            String contentType
    );

    InputStream load(
            String storageKey
    );

    boolean exists(
            String storageKey
    );

    void delete(
            String storageKey
    );
}
