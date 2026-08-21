package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.channel.ChannelAccountDto;
import kit.penny.clientbus.common.dto.channel.ChannelDto;
import kit.penny.clientbus.common.dto.channel.CreateChannelRequest;
import kit.penny.clientbus.common.dto.channel.UpdateChannelAccountRequest;
import kit.penny.clientbus.common.dto.channel.UpdateChannelRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.mapper.ChannelMapper;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ChannelAccountRepository channelAccountRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ChannelMapper channelMapper;
    private final CurrentUserService currentUserService;

    public ChannelService(
            ChannelRepository channelRepository,
            ChannelAccountRepository channelAccountRepository,
            WorkspaceRepository workspaceRepository,
            ChannelMapper channelMapper,
            CurrentUserService currentUserService
    ) {
        this.channelRepository = channelRepository;
        this.channelAccountRepository = channelAccountRepository;
        this.workspaceRepository = workspaceRepository;
        this.channelMapper = channelMapper;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public ChannelDto createChannel(
            CreateChannelRequest request
    ) {

        currentUserService.requireWorkspaceAccess(
                request.workspaceId()
        );

        WorkspaceEntity workspace =
                workspaceRepository.findById(
                        request.workspaceId()
                ).orElseThrow(() ->
                        new EntityNotFoundException(
                                "Workspace not found: "
                                        + request.workspaceId()
                        )
                );

        ChannelEntity channel =
                new ChannelEntity(
                        workspace,
                        request.type(),
                        request.name()
                );

        ChannelAccountEntity account =
                new ChannelAccountEntity(
                        channel,
                        request.account().externalId(),
                        request.account().username(),
                        request.account().phone(),
                        request.account().displayName()
                );

        channel.setAccount(account);

        channel = channelRepository.save(channel);

        return channelMapper.toDto(channel);
    }

    @Transactional
    public ChannelDto getChannel(UUID id) {

        ChannelEntity channel =
                findChannel(id);

        requireChannelWorkspaceAccess(channel);

        return channelMapper.toDto(channel);
    }

    @Transactional
    public List<ChannelDto> getChannelsByWorkspace(
            UUID workspaceId
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return channelRepository
                .findAllByWorkspaceId(workspaceId)
                .stream()
                .map(channelMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ChannelDto> getChannelsByWorkspaceAndType(
            UUID workspaceId,
            ChannelType type
    ) {

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        return channelRepository
                .findAllByWorkspaceIdAndType(
                        workspaceId,
                        type
                )
                .stream()
                .map(channelMapper::toDto)
                .toList();
    }

    @Transactional
    public ChannelDto updateChannel(
            UUID id,
            UpdateChannelRequest request
    ) {

        ChannelEntity channel =
                findChannel(id);

        requireChannelWorkspaceAccess(channel);

        channelMapper.updateEntity(
                channel,
                request
        );

        return channelMapper.toDto(channel);
    }

    @Transactional
    public void deleteChannel(UUID id) {

        ChannelEntity channel =
                findChannel(id);

        requireChannelWorkspaceAccess(channel);

        channelRepository.delete(channel);
    }

    @Transactional
    public ChannelAccountDto getChannelAccount(
            UUID channelId
    ) {

        ChannelEntity channel =
                findChannel(channelId);

        requireChannelWorkspaceAccess(channel);

        ChannelAccountEntity account =
                findChannelAccount(channelId);

        return channelMapper.toAccountDto(account);
    }

    @Transactional
    public ChannelAccountDto updateChannelAccount(
            UUID channelId,
            UpdateChannelAccountRequest request
    ) {

        ChannelEntity channel =
                findChannel(channelId);

        requireChannelWorkspaceAccess(channel);

        ChannelAccountEntity account =
                findChannelAccount(channelId);

        channelMapper.updateAccountEntity(
                account,
                request
        );

        return channelMapper.toAccountDto(account);
    }

    @Transactional
    public void disconnectChannelAccount(
            UUID channelId
    ) {

        ChannelEntity channel =
                findChannel(channelId);

        requireChannelWorkspaceAccess(channel);

        ChannelAccountEntity account =
                findChannelAccount(channelId);

        channelAccountRepository.delete(account);
    }

    private ChannelEntity findChannel(UUID id) {

        return channelRepository
                .findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Channel not found: " + id
                        )
                );
    }

    private ChannelAccountEntity findChannelAccount(
            UUID channelId
    ) {

        return channelAccountRepository
                .findByChannelId(channelId)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Channel account not found for channel: "
                                        + channelId
                        )
                );
    }

    private void requireChannelWorkspaceAccess(
            ChannelEntity channel
    ) {

        if (channel.getWorkspace() == null) {
            throw new IllegalStateException(
                    "Channel has no workspace: "
                            + channel.getId()
            );
        }

        currentUserService.requireWorkspaceAccess(
                channel.getWorkspace().getId()
        );
    }
}