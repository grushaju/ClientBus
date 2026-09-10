package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.properties.TelegramProperties;
import kit.penny.tdlib.updates.TelegramAuthorizationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelegramContextFactoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldCreateIndependentSpringContextsForMultipleAccounts() {

        TelegramProperties globalProperties =
                new TelegramProperties(
                        false,
                        tempDirectory
                                .resolve("telegram")
                                .toString(),
                        tempDirectory
                                .resolve("files")
                                .toString(),
                        "TEST_DATABASE_SECRET",
                        true,
                        true,
                        true,
                        true,
                        94575,
                        "a3406de8d171bb422bb6ddf3bbd800e2e",
                        null,
                        "en",
                        "ClientBus-Test",
                        "1.0",
                        "1.0",
                        2,
                        null
                );

        ChannelAccountRepository channelAccountRepository =
                Mockito.mock(ChannelAccountRepository.class);
        ChannelRepository channelRepository =
                Mockito.mock(ChannelRepository.class);

        TelegramContextFactory factory =
                new TelegramContextFactory(
                        globalProperties,
                        channelAccountRepository,
                        channelRepository
                );

        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        TelegramClientContext contextA = null;
        TelegramClientContext contextB = null;

        try {
            contextA = factory.create(
                    accountA,
                    "+79990000001"
            );

            contextB = factory.create(
                    accountB,
                    "+79990000002"
            );

            assertNotNull(contextA);
            assertNotNull(contextB);

            assertEquals(
                    accountA,
                    contextA.channelAccountId()
            );

            assertEquals(
                    accountB,
                    contextB.channelAccountId()
            );

            assertNotSame(
                    contextA.applicationContext(),
                    contextB.applicationContext()
            );

            assertNotSame(
                    contextA.telegramClient(),
                    contextB.telegramClient()
            );

            assertNotSame(
                    contextA.authorizationManager(),
                    contextB.authorizationManager()
            );

            assertTrue(
                    contextA.applicationContext().isActive()
            );

            assertTrue(
                    contextB.applicationContext().isActive()
            );

            TelegramClient clientA =
                    contextA.telegramClient();

            TelegramClient clientB =
                    contextB.telegramClient();

            TelegramAuthorizationManager authA =
                    contextA.authorizationManager();

            TelegramAuthorizationManager authB =
                    contextB.authorizationManager();

            assertNotSame(clientA, clientB);
            assertNotSame(authA, authB);

        } finally {
            if (contextA != null) {
                contextA.applicationContext().close();
            }

            if (contextB != null) {
                contextB.applicationContext().close();
            }
        }

        assertFalse(
                contextA.applicationContext().isActive()
        );

        assertFalse(
                contextB.applicationContext().isActive()
        );
    }

    @Test
    void shouldCreatePerAccountTelegramProperties() {
        TelegramProperties globalProperties =
                new TelegramProperties(
                        false,
                        tempDirectory.resolve("telegram").toString(),
                        tempDirectory.resolve("files").toString(),
                        "TEST_DATABASE_SECRET",
                        true,
                        true,
                        true,
                        true,
                        94575,
                        "a3406de8d171bb422bb6ddf3bbd800e2e",
                        null,
                        "en",
                        "ClientBus-Test",
                        "1.0",
                        "1.0",
                        2,
                        null
                );

        ChannelAccountRepository channelAccountRepository =
                Mockito.mock(ChannelAccountRepository.class);
        ChannelRepository channelRepository =
                Mockito.mock(ChannelRepository.class);

        TelegramContextFactory factory =
                new TelegramContextFactory(
                        globalProperties,
                        channelAccountRepository,
                        channelRepository
                );

        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        TelegramClientContext contextA = null;
        TelegramClientContext contextB = null;

        try {
            contextA = factory.create(
                    accountA,
                    "+79990000001"
            );

            contextB = factory.create(
                    accountB,
                    "+79990000002"
            );

            TelegramProperties propertiesA =
                    contextA.applicationContext()
                            .getBean(TelegramProperties.class);

            TelegramProperties propertiesB =
                    contextB.applicationContext()
                            .getBean(TelegramProperties.class);

            assertEquals(
                    "+79990000001",
                    propertiesA.phone()
            );

            assertEquals(
                    "+79990000002",
                    propertiesB.phone()
            );

            assertEquals(
                    tempDirectory
                            .resolve("telegram")
                            .resolve(accountA.toString())
                            .resolve("database")
                            .toString(),
                    propertiesA.databaseDirectory()
            );

            assertEquals(
                    tempDirectory
                            .resolve("telegram")
                            .resolve(accountB.toString())
                            .resolve("database")
                            .toString(),
                    propertiesB.databaseDirectory()
            );

            assertEquals(
                    tempDirectory
                            .resolve("telegram")
                            .resolve(accountA.toString())
                            .resolve("files")
                            .toString(),
                    propertiesA.filesDirectory()
            );

            assertEquals(
                    tempDirectory
                            .resolve("telegram")
                            .resolve(accountB.toString())
                            .resolve("files")
                            .toString(),
                    propertiesB.filesDirectory()
            );

            assertNotEquals(
                    propertiesA.databaseDirectory(),
                    propertiesB.databaseDirectory()
            );

            assertNotEquals(
                    propertiesA.filesDirectory(),
                    propertiesB.filesDirectory()
            );

            assertEquals(
                    globalProperties.apiId(),
                    propertiesA.apiId()
            );

            assertEquals(
                    globalProperties.apiId(),
                    propertiesB.apiId()
            );

            assertEquals(
                    globalProperties.apiHash(),
                    propertiesA.apiHash()
            );

            assertEquals(
                    globalProperties.apiHash(),
                    propertiesB.apiHash()
            );

            assertEquals(
                    globalProperties.systemLanguageCode(),
                    propertiesA.systemLanguageCode()
            );

            assertEquals(
                    globalProperties.systemLanguageCode(),
                    propertiesB.systemLanguageCode()
            );

            assertEquals(
                    globalProperties.deviceModel(),
                    propertiesA.deviceModel()
            );

            assertEquals(
                    globalProperties.deviceModel(),
                    propertiesB.deviceModel()
            );

            assertEquals(
                    globalProperties.systemVersion(),
                    propertiesA.systemVersion()
            );

            assertEquals(
                    globalProperties.systemVersion(),
                    propertiesB.systemVersion()
            );

            assertEquals(
                    globalProperties.applicationVersion(),
                    propertiesA.applicationVersion()
            );

            assertEquals(
                    globalProperties.applicationVersion(),
                    propertiesB.applicationVersion()
            );

            assertEquals(
                    globalProperties.logVerbosityLevel(),
                    propertiesA.logVerbosityLevel()
            );

            assertEquals(
                    globalProperties.logVerbosityLevel(),
                    propertiesB.logVerbosityLevel()
            );

            assertNull(propertiesA.proxy());
            assertNull(propertiesB.proxy());

        } finally {
            if (contextA != null) {
                contextA.applicationContext().close();
            }

            if (contextB != null) {
                contextB.applicationContext().close();
            }
        }
    }
}