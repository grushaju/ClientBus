package kit.penny.clientbus.server.connector.telegram.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramClientManagerTest {

    private final TelegramContextFactory contextFactory =
            mock(TelegramContextFactory.class);

    private final TelegramClientManager manager =
            new TelegramClientManager(contextFactory);

    @AfterEach
    void tearDown() {
        manager.closeAll();
    }

    @Test
    void create_allowsMultipleTelegramClientsForDifferentChannelAccounts() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        TelegramClientContext contextA = context(accountA);
        TelegramClientContext contextB = context(accountB);

        when(contextFactory.create(accountA, "+10000000001"))
                .thenReturn(contextA);
        when(contextFactory.create(accountB, "+10000000002"))
                .thenReturn(contextB);

        TelegramClientContext resultA =
                manager.create(accountA, "+10000000001");

        TelegramClientContext resultB =
                manager.create(accountB, "+10000000002");

        assertSame(contextA, resultA);
        assertSame(contextB, resultB);

        assertSame(contextA, manager.get(accountA));
        assertSame(contextB, manager.get(accountB));

        assertNotSame(
                manager.get(accountA),
                manager.get(accountB)
        );
    }

    @Test
    void stop_doesNotAffectAnotherTelegramClient() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        TelegramClientContext contextA = context(accountA);
        TelegramClientContext contextB = context(accountB);

        when(contextFactory.create(accountA, "+10000000001"))
                .thenReturn(contextA);
        when(contextFactory.create(accountB, "+10000000002"))
                .thenReturn(contextB);

        manager.create(accountA, "+10000000001");
        manager.create(accountB, "+10000000002");

        manager.stop(accountA);

        assertNull(manager.get(accountA));
        assertSame(contextB, manager.get(accountB));

        verify(contextA.applicationContext()).close();
        verify(contextB.applicationContext(), never()).close();
    }

    @Test
    void create_rejectsDuplicateChannelAccount() {
        UUID accountId = UUID.randomUUID();

        TelegramClientContext context = context(accountId);

        when(contextFactory.create(accountId, "+10000000001"))
                .thenReturn(context);

        manager.create(accountId, "+10000000001");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> manager.create(accountId, "+10000000001")
        );

        assertEquals(
                "Telegram client already exists for channel account: "
                        + accountId,
                exception.getMessage()
        );

        verify(contextFactory, times(1))
                .create(accountId, "+10000000001");
    }

    @Test
    void require_returnsClientForRequestedChannelAccount() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        TelegramClientContext contextA = context(accountA);
        TelegramClientContext contextB = context(accountB);

        when(contextFactory.create(accountA, "+10000000001"))
                .thenReturn(contextA);
        when(contextFactory.create(accountB, "+10000000002"))
                .thenReturn(contextB);

        manager.create(accountA, "+10000000001");
        manager.create(accountB, "+10000000002");

        assertSame(contextA, manager.require(accountA));
        assertSame(contextB, manager.require(accountB));
    }

    private TelegramClientContext context(UUID channelAccountId) {
        ConfigurableApplicationContext applicationContext =
                mock(ConfigurableApplicationContext.class);

        when(applicationContext.isActive()).thenReturn(true);

        return new TelegramClientContext(
                channelAccountId,
                applicationContext,
                null,
                null
        );
    }
}
