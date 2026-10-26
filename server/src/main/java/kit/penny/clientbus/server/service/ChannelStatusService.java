package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.notification.center.PersonalNotificationWriter;
import kit.penny.clientbus.server.notification.contract.NotificationSourceContext;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Serializes effective connector state and the lifetime of its actionable incident. */
@Service
public class ChannelStatusService {

    @org.springframework.beans.factory.annotation.Autowired
    private kit.penny.clientbus.server.notification.outbox.UiDomainEvents uiEvents;
    private final ChannelRepository channels;
    private final PersonalNotificationWriter notifications;
    public ChannelStatusService(ChannelRepository channels, PersonalNotificationWriter notifications) {
        this.channels = channels; this.notifications = notifications;
    }

    @Transactional
    public void beginConnecting(UUID accountId) {
        channels.findLockedByAccountId(accountId).ifPresent(channel -> {
            channel.setNotificationExpectedDisconnect(false);
            boolean changed = channel.getStatus() != ChannelConnectionStatus.CONNECTING;
            channel.setStatus(ChannelConnectionStatus.CONNECTING);
            if (changed && uiEvents != null) uiEvents.workspace(channel.getWorkspace(), "channels.invalidated");
            channels.save(channel);
        });
    }

    /** Commit the user's stop intent before a connector can synchronously invoke its callback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expectDisconnect(UUID accountId, ChannelConnectionStatus status) {
        if (status != ChannelConnectionStatus.DISCONNECTED && status != ChannelConnectionStatus.DISABLED)
            throw new IllegalArgumentException("Expected disconnect must disable or disconnect the channel");
        channels.findLockedByAccountId(accountId).ifPresent(channel -> {
            channel.setNotificationExpectedDisconnect(true);
            closeIncident(accountId, channel);
            boolean changed = channel.getStatus() != status;
            channel.setStatus(status);
            if (changed && uiEvents != null) uiEvents.workspace(channel.getWorkspace(), "channels.invalidated");
            channels.save(channel);
        });
    }

    @Transactional
    public boolean transition(UUID accountId, ChannelConnectionStatus status, String reason) {
        return channels.findLockedByAccountId(accountId).map(channel -> {
            if (channel.isNotificationExpectedDisconnect()) return false;
            ChannelConnectionStatus previous = channel.getStatus();
            if (status == ChannelConnectionStatus.CONNECTED) closeIncident(accountId, channel);
            boolean actionable = status == ChannelConnectionStatus.ERROR
                    || status == ChannelConnectionStatus.DISCONNECTED && previous == ChannelConnectionStatus.CONNECTED
                    || status == ChannelConnectionStatus.CONNECTING && previous == ChannelConnectionStatus.CONNECTED;
            if (actionable && channel.getNotificationIncidentId() == null) {
                channel.setNotificationIncidentId(UUID.randomUUID());
                channel.setNotificationIncidentReason(status == ChannelConnectionStatus.CONNECTING ? "REAUTH_REQUIRED" : reason);
            }
            boolean changed = channel.getStatus() != status;
            channel.setStatus(status);
            if (changed && uiEvents != null) uiEvents.workspace(channel.getWorkspace(), "channels.invalidated");
            // The writer uses the same row lock and transaction; recovery closes even when creation is disabled.
            channels.saveAndFlush(channel);
            if (actionable && channel.getNotificationIncidentStartedAt() != null) notifications.channelIncident(accountId, channel.getNotificationIncidentId(),
                    new NotificationSourceContext(EventOrigin.LIVE, channel.getNotificationIncidentStartedAt(), channel.getNotificationIncidentId(), null));
            return previous != status;
        }).orElse(false);
    }

    private void closeIncident(UUID accountId, ChannelEntity channel) {
        UUID incident = channel.getNotificationIncidentId();
        if (incident != null) notifications.closeChannelIncident(accountId, incident);
        channel.setNotificationIncidentId(null);
        channel.setNotificationIncidentReason(null);
    }
}
