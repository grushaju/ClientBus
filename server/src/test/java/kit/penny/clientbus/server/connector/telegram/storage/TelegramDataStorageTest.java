package kit.penny.clientbus.server.connector.telegram.storage;

import kit.penny.tdlib.properties.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TelegramDataStorageTest {

    @TempDir
    Path tempDirectory;

    private TelegramDataStorage storage;

    @BeforeEach
    void setUp() {
        TelegramProperties properties =
                new TelegramProperties(
                        false,
                        tempDirectory.toString(),
                        tempDirectory.toString(),
                        "",
                        true,
                        true,
                        true,
                        false,
                        0,
                        "",
                        "",
                        "en",
                        "test",
                        "test",
                        "test",
                        0,
                        null
                );

        storage = new TelegramDataStorage(properties);
    }

    @Test
    void deleteShouldRemoveAccountDirectoryRecursively()
            throws IOException {

        UUID channelAccountId =
                UUID.randomUUID();

        Path accountDirectory =
                tempDirectory.resolve(
                        channelAccountId.toString()
                );

        Path nestedDirectory =
                accountDirectory.resolve("nested");

        Path databaseFile =
                accountDirectory.resolve("database.db");

        Path sessionFile =
                nestedDirectory.resolve("session.dat");

        Files.createDirectories(nestedDirectory);

        Files.writeString(
                databaseFile,
                "test"
        );

        Files.writeString(
                sessionFile,
                "test"
        );

        storage.delete(channelAccountId);

        assertFalse(
                Files.exists(accountDirectory)
        );

        assertFalse(
                Files.exists(databaseFile)
        );

        assertFalse(
                Files.exists(sessionFile)
        );
    }

    @Test
    void deleteShouldNotFailWhenAccountDirectoryDoesNotExist() {

        UUID channelAccountId =
                UUID.randomUUID();

        assertDoesNotThrow(
                () -> storage.delete(channelAccountId)
        );
    }
}
