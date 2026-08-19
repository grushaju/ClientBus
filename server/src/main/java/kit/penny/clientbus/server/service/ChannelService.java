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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ChannelAccountRepository channelAccountRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ChannelMapper channelMapper;

    public ChannelService(
            ChannelRepository channelRepository,
            ChannelAccountRepository channelAccountRepository,
            WorkspaceRepository workspaceRepository,
            ChannelMapper channelMapper
    ) {
        this.channelRepository = channelRepository;
        this.channelAccountRepository = channelAccountRepository;
        this.workspaceRepository = workspaceRepository;
        this.channelMapper = channelMapper;
    }

    // =========================================================
    // CHANNEL
    // =========================================================

    @Transactional
    public ChannelDto createChannel(
            CreateChannelRequest request
    ) {

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
                channelRepository.findById(id)
                        .orElseThrow(() ->
                                new EntityNotFoundException(
                                        "Channel not found: " + id
                                )
                        );

        return channelMapper.toDto(channel);
    }

    @Transactional
    public List<ChannelDto> getChannelsByWorkspace(
            UUID workspaceId
    ) {

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

        channelRepository.delete(channel);
    }

    // =========================================================
    // CHANNEL ACCOUNT
    // =========================================================

    @Transactional
    public ChannelAccountDto getChannelAccount(
            UUID channelId
    ) {

        ChannelAccountEntity account =
                findChannelAccount(channelId);

        return channelMapper.toAccountDto(account);
    }

    @Transactional
    public ChannelAccountDto updateChannelAccount(
            UUID channelId,
            UpdateChannelAccountRequest request
    ) {

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

        ChannelAccountEntity account =
                findChannelAccount(channelId);

        channelAccountRepository.delete(account);
    }

    // =========================================================
    // PRIVATE
    // =========================================================

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
}