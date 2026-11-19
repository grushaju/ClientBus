package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity;
import kit.penny.clientbus.server.persistence.repository.ChatCheckpointRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Провизионизация канала DIKIDI (решение пользователя 15.11.2026, CRM_CONNECTORS.md §7):
 * канал создаётся автоматически при назначении DIKIDI-CRM-проекта на workspace,
 * поля channelaccount берутся только из БД (externalId = company id проекта,
 * name = displayname проекта, phone = login подключения, username = displayname
 * подключения). Прямого создания/удаления канала DIKIDI нет: при отвязке проекта
 * канал без переписки удаляется, с перепиской — отключается и остаётся DISABLED
 * (FK диалогов и политика сохранения архивной истории 12.x). Повторное назначение
 * реактивирует существующий канал.
 */
@Service
public class DikidiChannelProvisioning {
    private static final Logger log = LoggerFactory.getLogger(DikidiChannelProvisioning.class);

    private final ChannelRepository channels;
    private final ConversationRepository conversations;
    private final ChatCheckpointRepository checkpoints;
    private final ChannelStatusService status;
    private final UiDomainEvents events;

    public DikidiChannelProvisioning(ChannelRepository channels, ConversationRepository conversations,
                                     ChatCheckpointRepository checkpoints, ChannelStatusService status,
                                     UiDomainEvents events) {
        this.channels = channels; this.conversations = conversations; this.checkpoints = checkpoints;
        this.status = status; this.events = events;
    }

    @Transactional
    public void provisionOnBind(CrmProjectEntity project) {
        if (project.getProvider() != kit.penny.clientbus.common.enums.CrmType.DIKIDI) return;
        var workspace = project.getWorkspace();
        var known = channels.findAllByWorkspaceIdAndType(workspace.getId(), ChannelType.DIKIDI).stream()
                .filter(channel -> channel.getAccount() != null
                        && project.getExternalId().equals(channel.getAccount().getExternalId())).toList();
        ChannelEntity channel;
        if (known.isEmpty()) {
            channel = new ChannelEntity(workspace, ChannelType.DIKIDI, cut(project.getDisplayName(), 100));
            channel.setAccount(new ChannelAccountEntity(channel, cut(project.getExternalId(), 200),
                    cut(project.getConnection().getDisplayName(), 100), cut(project.getConnection().getLogin(), 30),
                    cut(project.getDisplayName(), 200)));
            channels.save(channel);
            log.info("DIKIDI chat channel provisioned: workspace={}, project={}, account={}",
                    workspace.getId(), project.getId(), channel.getAccount().getId());
        } else {
            channel = known.get(0);
            var account = channel.getAccount();
            // Единственный источник истины — CRM: профиль обновляется и при повторном назначении.
            account.setExternalId(cut(project.getExternalId(), 200));
            account.setUsername(cut(project.getConnection().getDisplayName(), 100));
            account.setPhone(cut(project.getConnection().getLogin(), 30));
            account.setDisplayName(cut(project.getDisplayName(), 200));
            channel.setName(cut(project.getDisplayName(), 100));
            channels.save(channel);
            if (channel.getStatus() == ChannelConnectionStatus.DISABLED)
                status.beginConnecting(account.getId()); // намерение «подключить» при возврате проекта
        }
        events.workspace(workspace, "channels.invalidated");
    }

    @Transactional
    public void retireOnDetach(CrmProjectEntity project) {
        if (project.getProvider() != kit.penny.clientbus.common.enums.CrmType.DIKIDI) return;
        var workspace = project.getWorkspace();
        var known = channels.findAllByWorkspaceIdAndType(workspace.getId(), ChannelType.DIKIDI).stream()
                .filter(channel -> channel.getAccount() != null
                        && project.getExternalId().equals(channel.getAccount().getExternalId())).toList();
        if (known.isEmpty()) return;
        var channel = known.get(0);
        var accountId = channel.getAccount().getId();
        if (conversations.existsByChannelAccountId(accountId)) {
            // Переписка архивного проекта сохраняется: канал отключается, но остаётся (DISABLED).
            status.expectDisconnect(accountId, ChannelConnectionStatus.DISABLED);
            log.info("DIKIDI chat channel disabled on project detach (conversations retained): workspace={}, account={}",
                    workspace.getId(), accountId);
        } else {
            status.expectDisconnect(accountId, ChannelConnectionStatus.DISCONNECTED);
            checkpoints.deleteByChannelAccountId(accountId);
            channels.delete(channel);
            log.info("DIKIDI chat channel removed with its project: workspace={}, account={}",
                    workspace.getId(), accountId);
        }
        events.workspace(workspace, "channels.invalidated");
    }

    private static String cut(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
