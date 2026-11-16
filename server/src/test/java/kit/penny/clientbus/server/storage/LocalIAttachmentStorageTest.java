package kit.penny.clientbus.server.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 16.11.2026 (сбой оператора): в avatarUrl DIKIDI-клиента лёг сырой CDN-URL, и каждый
 * GET /avatar/content падал InvalidPathException («Illegal char <:>») на Windows.
 * Ключ хранилища, не являющийся локальным путём, — отсутствие объекта, а не 500.
 */
class LocalIAttachmentStorageTest {
    @TempDir Path root;
    LocalIAttachmentStorage storage;

    @BeforeEach void setUp() { storage = new LocalIAttachmentStorage(root.toString()); }

    @Test void remoteOrExoticKeysAreSimplyAbsent() {
        assertFalse(storage.exists("https://f2.dikidi.net/c99/v11111/abc123hash.jpg"));
        assertFalse(storage.exists("C:\\windows\\evil.jpg"));
        assertFalse(storage.exists("../outside.jpg"));
        assertFalse(storage.exists(" "));
    }

    @Test void loadReportsInvalidKeysAsIllegalKeyNotAsPathCrash() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.load("https://f2.dikidi.net/c99/v11111/abc123hash.jpg"));
    }

    @Test void storeSurvivesPlatformNamesThatAreNotPaths() throws Exception {
        var stored = storage.store(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "https://f2.dikidi.net/a/b/img.jpg", 3, "image/jpeg");
        assertTrue(stored.storageKey().endsWith(".jpg"), "расширение берётся из имени, а не падает на URL");
        assertTrue(storage.exists(stored.storageKey()));
        assertEquals(3, storage.load(stored.storageKey()).readAllBytes().length);
    }
}
