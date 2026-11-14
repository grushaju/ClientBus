package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.connector.api.host.HostAttachmentStorage;
import kit.penny.clientbus.connector.api.host.HostStoredAttachment;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/** Host side of the attachment storage port backed by the configured object storage. */
@Component
public class AttachmentStorageHostAdapter
        implements HostAttachmentStorage {

    private final IAttachmentStorage attachmentStorage;

    public AttachmentStorageHostAdapter(
            IAttachmentStorage attachmentStorage
    ) {
        this.attachmentStorage = attachmentStorage;
    }

    @Override
    public HostStoredAttachment store(
            InputStream content,
            String fileName,
            long size,
            String contentType
    ) {
        var stored = attachmentStorage.store(
                content,
                fileName,
                size,
                contentType
        );
        return stored == null
                ? null
                : new HostStoredAttachment(
                        stored.storageKey(),
                        stored.fileName(),
                        stored.contentType(),
                        stored.size());
    }

    @Override
    public InputStream load(
            String storageKey
    ) {
        return attachmentStorage.load(
                storageKey
        );
    }

    @Override
    public boolean exists(
            String storageKey
    ) {
        return attachmentStorage.exists(
                storageKey
        );
    }

    @Override
    public void delete(
            String storageKey
    ) {
        attachmentStorage.delete(
                storageKey
        );
    }
}
