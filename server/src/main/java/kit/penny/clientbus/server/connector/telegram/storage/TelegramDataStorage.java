package kit.penny.clientbus.server.connector.telegram.storage;

import kit.penny.tdlib.properties.TelegramProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

@Component
public class TelegramDataStorage {

    private final Path databaseDirectory;

    public TelegramDataStorage(
            TelegramProperties properties
    ) {
        this.databaseDirectory =
                Path.of(properties.databaseDirectory());
    }

    public void delete(UUID channelAccountId) {

        Path accountDirectory =
                databaseDirectory.resolve(
                        channelAccountId.toString()
                );

        if (!Files.exists(accountDirectory)) {
            return;
        }

        try {
            Files.walkFileTree(
                    accountDirectory,
                    new SimpleFileVisitor<>() {

                        @Override
                        public FileVisitResult visitFile(
                                Path file,
                                BasicFileAttributes attrs
                        ) throws IOException {

                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(
                                Path directory,
                                IOException exception
                        ) throws IOException {

                            if (exception != null) {
                                throw exception;
                            }

                            Files.deleteIfExists(directory);
                            return FileVisitResult.CONTINUE;
                        }
                    }
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to delete Telegram data: "
                            + accountDirectory,
                    e
            );
        }
    }
}