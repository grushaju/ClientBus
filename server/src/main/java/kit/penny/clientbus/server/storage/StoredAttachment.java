package kit.penny.clientbus.server.storage;

public record StoredAttachment(
        String storageKey,
        long size,
        String checksum,
        String mimeType
) {
}