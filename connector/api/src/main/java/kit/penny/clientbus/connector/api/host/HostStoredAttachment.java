package kit.penny.clientbus.connector.api.host;

/** Metadata of content accepted by {@link HostAttachmentStorage#store}. */
public record HostStoredAttachment(

        String storageKey,

        String fileName,

        String contentType,

        long size

) {
}
