package kit.penny.clientbus.connector.dikidi;

import kit.penny.clientbus.connector.api.host.HostChannelAccess;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Управление каналом DIKIDI (13.5): подключить и отключить. Создание и удаление
 * канала не управляются отсюда и из общего канала API — канал DIKIDI провизионится
 * автоматически назначением DIKIDI-проекта на workspace и удаляется вместе с
 * проектом (решение пользователя 15.11.2026, CRM_CONNECTORS.md §7).
 */
@RestController
@RequestMapping("/api/channels/{channelAccountId}/dikidi")
public class DikidiChatManagementController {
    private final DikidiChatChannel channel;
    private final HostChannelAccess hostChannelAccess;
    public DikidiChatManagementController(DikidiChatChannel channel, HostChannelAccess hostChannelAccess) {
        this.channel = channel; this.hostChannelAccess = hostChannelAccess;
    }
    @PostMapping("/connect")
    public DikidiChatAccess.Result connect(@PathVariable UUID channelAccountId) {
        hostChannelAccess.requireManagementAccess(channelAccountId);
        return channel.connect(channelAccountId);
    }
    @PostMapping("/disconnect")
    public void disconnect(@PathVariable UUID channelAccountId) {
        hostChannelAccess.requireManagementAccess(channelAccountId);
        channel.disconnect(channelAccountId);
    }
}
