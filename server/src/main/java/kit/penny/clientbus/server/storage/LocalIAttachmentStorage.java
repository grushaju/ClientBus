package kit.penny.clientbus.server.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Component
public class LocalIAttachmentStorage implements IAttachmentStorage {

    private final Path rootPath;

    public LocalIAttachmentStorage(
            @Value("${clientbus.storage.local-path}")
            String localPath
    ) {
        if (localPath == null || localPath.isBlank()) {
            throw new IllegalArgumentException(
                    "clientbus.storage.local-path must not be empty"
            );
        }

        this.rootPath =
                Paths.get(localPath)
                        .toAbsolutePath()
                        .normalize();

        try {
            Files.createDirectories(rootPath);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create attachment storage directory: "
                            + rootPath,
                    e
            );
        }
    }

    @Override
    public StoredAttachmentMetadata store(
            InputStream inputStream,
            String fileName,
            long size,
            String contentType
    ) {

        if (inputStream == null) {
            throw new IllegalArgumentException(
                    "inputStream must not be null"
            );
        }

        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(
                    "fileName must not be blank"
            );
        }

        if (size < 0) {
            throw new IllegalArgumentException(
                    "size must not be negative"
            );
        }

        String storageKey =
                generateStorageKey(fileName);

        Path targetPath =
                resolveStoragePath(storageKey);

        try {

            Files.createDirectories(
                    targetPath.getParent()
            );

            Files.copy(
                    inputStream,
                    targetPath
            );

            return new StoredAttachmentMetadata(
                    storageKey,
                    fileName,
                    contentType,
                    size
            );

        } catch (IOException e) {

            /*
             * If storing failed after the target file was
             * partially created, try to remove it.
             */
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {
                // Do not hide the original exception.
            }

            throw new AttachmentStorageException(
                    "Failed to store attachment: "
                            + storageKey,
                    e
            );
        }
    }

    @Override
    public InputStream load(
            String storageKey
    ) {

        Path path =
                resolveStoragePath(storageKey);

        try {

            if (!Files.exists(path)) {
                throw new AttachmentStorageException(
                        "Attachment not found in storage: "
                                + storageKey
                );
            }

            if (!Files.isRegularFile(path)) {
                throw new AttachmentStorageException(
                        "Attachment storage object is not a file: "
                                + storageKey
                );
            }

            return Files.newInputStream(path);

        } catch (IOException e) {

            throw new AttachmentStorageException(
                    "Failed to load attachment: "
                            + storageKey,
                    e
            );
        }
    }

    @Override
    public boolean exists(
            String storageKey
    ) {

        Path path =
                resolveStoragePath(storageKey);

        return Files.isRegularFile(path);
    }

    @Override
    public void delete(
            String storageKey
    ) {

        Path path =
                resolveStoragePath(storageKey);

        try {

            Files.deleteIfExists(path);

        } catch (IOException e) {

            throw new AttachmentStorageException(
                    "Failed to delete attachment: "
                            + storageKey,
                    e
            );
        }
    }

    private String generateStorageKey(
            String fileName
    ) {

        String extension =
                extractExtension(fileName);

        String uuid =
                UUID.randomUUID().toString();

        /*
         * Two-level directory structure prevents having
         * thousands/millions of files in a single directory.
         *
         * Example:
         *
         * 8f/
         *   3a/
         *     8f3a...uuid.jpg
         */
        String firstLevel =
                uuid.substring(0, 2);

        String secondLevel =
                uuid.substring(2, 4);

        return firstLevel
                + "/"
                + secondLevel
                + "/"
                + uuid
                + extension;
    }

    private String extractExtension(
            String fileName
    ) {

        String normalized =
                Paths.get(fileName)
                        .getFileName()
                        .toString();

        int dotIndex =
                normalized.lastIndexOf('.');

        if (dotIndex <= 0
                || dotIndex == normalized.length() - 1) {

            return "";
        }

        String extension =
                normalized.substring(dotIndex);

        /*
         * Prevent path-related characters from entering
         * the generated storage key.
         */
        if (!extension.matches(
                "\\.[a-zA-Z0-9]{1,10}"
        )) {
            return "";
        }

        return extension.toLowerCase();
    }

    private Path resolveStoragePath(
            String storageKey
    ) {

        if (storageKey == null
                || storageKey.isBlank()) {

            throw new IllegalArgumentException(
                    "storageKey must not be blank"
            );
        }

        Path resolved =
                rootPath
                        .resolve(storageKey)
                        .normalize();

        /*
         * Protect against path traversal:
         *
         * ../../some-file
         */
        if (!resolved.startsWith(rootPath)) {
            throw new IllegalArgumentException(
                    "Invalid storage key: "
                            + storageKey
            );
        }

        return resolved;
    }
}