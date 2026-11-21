package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.common.enums.ChannelConnectionStatus;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.host.HostChannelAccountSnapshot;
import kit.penny.clientbus.connector.api.host.HostChannelAccounts;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Host side of the channel account port: entities, workspace authorization and client lookup. */
@Component
public class ChannelAccountsHostAdapter
        implements HostChannelAccounts {

    private final ChannelAccountRepository channelAccountRepository;
    private final ClientAccountRepository clientAccountRepository;
    private final CurrentUserService currentUserService;
    @org.springframework.beans.factory.annotation.Autowired
    private kit.penny.clientbus.server.notification.outbox.UiDomainEvents uiEvents;

    public ChannelAccountsHostAdapter(
            ChannelAccountRepository channelAccountRepository,
            ClientAccountRepository clientAccountRepository,
            CurrentUserService currentUserService
    ) {
        this.channelAccountRepository = channelAccountRepository;
        this.clientAccountRepository = clientAccountRepository;
        this.currentUserService = currentUserService;
    }

    @Override
    public HostChannelAccountSnapshot require(
            UUID channelAccountId
    ) {
        return find(channelAccountId).orElseThrow(() ->
                new IllegalStateException(
                        "Telegram channel account not found: "
                                + channelAccountId
                )
        );
    }

    @Override
    public Optional<HostChannelAccountSnapshot> find(
            UUID channelAccountId
    ) {
        return channelAccountRepository
                .findById(
                        channelAccountId
                )
                .map(ChannelAccountsHostAdapter::toSnapshot);
    }

    @Override
    public List<UUID> findIdsByChannelTypeAndChannelStatuses(
            ChannelType channelType,
            List<ChannelConnectionStatus> channelStatuses
    ) {
        return channelAccountRepository
                .findAllByChannelTypeAndChannelStatusIn(
                        channelType,
                        channelStatuses
                )
                .stream()
                .map(ChannelAccountEntity::getId)
                .toList();
    }

    @Override
    public void updateProfile(
            UUID channelAccountId,
            String externalId,
            String username,
            String phone,
            String displayName,
            String avatarUrl
    ) {
        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Telegram channel account not found: "
                                        + channelAccountId
                        ));

        account.setExternalId(externalId);
        account.setUsername(username);
        account.setPhone(phone);
        account.setDisplayName(displayName);
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            account.setAvatarUrl(avatarUrl);
        }

        channelAccountRepository.save(
                account
        );
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void updateAvatar(
            UUID channelAccountId,
            String avatarStorageKey
    ) {
        if (avatarStorageKey == null || avatarStorageKey.isBlank())
            return; // absence of a photo never clears what is already shown
        ChannelAccountEntity account =
                channelAccountRepository.findById(channelAccountId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Telegram channel account not found: "
                                        + channelAccountId
                        ));
        if (avatarStorageKey.equals(account.getAvatarUrl()))
            return;
        account.setAvatarUrl(avatarStorageKey);
        channelAccountRepository.save(account);
        if (uiEvents != null) uiEvents.workspace(account.getChannel().getWorkspace(), "channels.invalidated");
    }

    @Override
    public void requireWorkspaceAccessForAccount(
            UUID channelAccountId
    ) {
        currentUserService.requireWorkspaceAccess(
                require(channelAccountId).workspaceId()
        );
    }

    @Override
    public UUID findExistingClientAccountId(
            ChannelType channelType,
            String externalId
    ) {
        return clientAccountRepository
                .findByChannelTypeAndExternalId(
                        channelType,
                        externalId
                )
                .map(account -> account.getId())
                .orElse(null);
    }

    private static HostChannelAccountSnapshot toSnapshot(
            ChannelAccountEntity account
    ) {
        return new HostChannelAccountSnapshot(
                account.getId(),
                account.getChannel().getId(),
                account.getChannel().getWorkspace().getId(),
                account.getChannel().getType(),
                account.getChannel().getStatus(),
                account.getPhone(),
                account.getExternalId()
        );
    }
}
