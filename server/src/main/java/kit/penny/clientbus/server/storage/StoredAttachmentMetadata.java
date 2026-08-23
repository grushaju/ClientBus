package kit.penny.clientbus.server.storage;

public record StoredAttachmentMetadata(

        String storageKey,

        String fileName,

        String contentType,

        long size

) {
}