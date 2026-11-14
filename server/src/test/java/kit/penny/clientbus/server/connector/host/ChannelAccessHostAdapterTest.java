package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.CHANNEL_ACCOUNT;
import static kit.penny.clientbus.server.security.policy.AccessPolicy.Resource.CHANNEL_ACCOUNT_MANAGEMENT;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 13.3 (по итогам ревью 14.11.2026): маппинг порта HostChannelAccess на HTTP-политику
 * сервера закрепляет прежние права перенесённых telegram-ручек: management-эндпоинты —
 * CHANNEL_ACCOUNT_MANAGEMENT, find — только CHANNEL_ACCOUNT.
 */
@ExtendWith(MockitoExtension.class)
class ChannelAccessHostAdapterTest {

    @Mock
    private HttpAccessPolicy accessPolicy;

    private ChannelAccessHostAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ChannelAccessHostAdapter(accessPolicy);
    }

    @Test
    void requireManagementAccessMapsToManagementResource() {
        UUID id = UUID.randomUUID();

        adapter.requireManagementAccess(id);

        verify(accessPolicy).require(CHANNEL_ACCOUNT_MANAGEMENT, id);
        verify(accessPolicy, never()).require(CHANNEL_ACCOUNT, id);
    }

    @Test
    void requireReadAccessMapsToReadResourceOnly() {
        UUID id = UUID.randomUUID();

        adapter.requireReadAccess(id);

        verify(accessPolicy).require(CHANNEL_ACCOUNT, id);
        verify(accessPolicy, never()).require(CHANNEL_ACCOUNT_MANAGEMENT, id);
    }
}
