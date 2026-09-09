package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramContextFactory;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
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

        TelegramContextFactory factory =
                new TelegramContextFactory(
                        globalProperties,
                        channelAccountRepository
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
}