package kit.penny.clientbus.server.storage;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public class LocalAttachmentStorage implements AttachmentStorage {

    private final Path rootPath;

    public LocalAttachmentStorage(String localPath) {

        if (!StringUtils.hasText(localPath)) {
            throw new IllegalArgumentException(
                    "clientbus.storage.local-path must be configured"
            );
        }

        this.rootPath = Paths.get(localPath).toAbsolutePath().normalize();

        if (!this.rootPath.isAbsolute()) {
            throw new IllegalArgumentException(
                    "clientbus.storage.local-path must be an absolute path"
            );
        }

        try {
            Files.createDirectories(this.rootPath);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to create local storage directory: "
                            + this.rootPath,
                    e
            );
        }
    }

    @Override
    public StoredAttachment store(
            InputStream inputStream,
            long size,
            String contentType
    ) throws IOException {

        Objects.requireNonNull(
                inputStream,
                "inputStream must not be null"
        );

        String storageKey = generateStorageKey();

        Path target = resolveStoragePath(storageKey);

        Files.createDirectories(
                target.getParent()
        );

        MessageDigest digest = sha256();

        long actualSize = 0;

        try (
                OutputStream output = Files.newOutputStream(target)
        ) {
            byte[] buffer = new byte[8192];

            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {

                output.write(
                        buffer,
                        0,
                        bytesRead
                );

                digest.update(
                        buffer,
                        0,
                        bytesRead
                );

                actualSize += bytesRead;
            }
        } catch (IOException | RuntimeException e) {

            Files.deleteIfExists(target);

            throw e;
        }

        if (size >= 0 && actualSize != size) {

            Files.deleteIfExists(target);

            throw new IOException(
                    "Stored attachment size mismatch. "
                            + "Expected: " + size
                            + ", actual: " + actualSize
            );
        }

        String checksum = HexFormat.of().formatHex(
                digest.digest()
        );

        return new StoredAttachment(
                storageKey,
                actualSize,
                checksum,
                contentType
        );
    }

    @Override
    public InputStream load(
            String storageKey
    ) throws IOException {

        Path path = resolveStoragePath(storageKey);

        return Files.newInputStream(path);
    }

    @Override
    public boolean exists(
            String storageKey
    ) {

        Path path = resolveStoragePath(storageKey);

        return Files.isRegularFile(path);
    }

    @Override
    public void delete(
            String storageKey
    ) throws IOException {

        Path path = resolveStoragePath(storageKey);

        Files.deleteIfExists(path);
    }

    /**
     * Генерирует уникальный storage key.
     *
     * Реальный ключ пока не зависит от Message/Conversation,
     * поскольку attachmentId появляется после сохранения entity.
     */
    private String generateStorageKey() {

        return "attachments/"
                + UUID.randomUUID();
    }

    /**
     * Преобразует storageKey в физический путь.
     *
     * Дополнительно защищает от path traversal:
     *
     * ../../etc/passwd
     */
    private Path resolveStoragePath(
            String storageKey
    ) {

        if (!StringUtils.hasText(storageKey)) {
            throw new IllegalArgumentException(
                    "storageKey must not be empty"
            );
        }

        Path path = rootPath.resolve(storageKey)
                .normalize();

        if (!path.startsWith(rootPath)) {
            throw new IllegalArgumentException(
                    "Invalid storage key: " + storageKey
            );
        }

        return path;
    }

    private MessageDigest sha256() {

        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }
}
