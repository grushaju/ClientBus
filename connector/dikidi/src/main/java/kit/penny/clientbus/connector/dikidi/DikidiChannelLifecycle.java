package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.IChannelAccountLifecycle;
import kit.penny.clientbus.connector.api.host.HostChannelStatus;

import java.util.UUID;

/**
 * Lifecycle DIKIDI-аккаунта (13.5): ожидаемое отключение через host-порт статуса.
 * Сессия общего клиента не рвётся — она принадлежит и CRM. Удаление канала DIKIDI
 * вне проектного цикла хост запрещает, поэтому lifecycle здесь — защита в глубину
 * для реестра жизненного цикла.
 */
public class DikidiChannelLifecycle implements IChannelAccountLifecycle {
    private final HostChannelStatus status;
    public DikidiChannelLifecycle(HostChannelStatus status) { this.status = status; }
    @Override public boolean supports(ChannelType channelType) { return channelType == ChannelType.DIKIDI; }
    @Override public void disconnect(UUID channelAccountId) {
        status.expectDisconnect(channelAccountId, ChannelConnectionStatus.DISCONNECTED);
    }
}
