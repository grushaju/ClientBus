package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.channel.ChannelAccountDto;
import kit.penny.clientbus.common.dto.channel.ChannelDto;
import kit.penny.clientbus.common.dto.channel.CreateChannelRequest;
import kit.penny.clientbus.common.dto.channel.UpdateChannelAccountRequest;
import kit.penny.clientbus.common.dto.channel.UpdateChannelRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.service.ChannelService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Управление каналами", description = "API для управления каналами сообщений")
public class ChannelController {

    private final ChannelService channelService;

    private final IAttachmentStorage attachmentStorage;

    public ChannelController(
            ChannelService channelService, IAttachmentStorage attachmentStorage
    ) {
        this.channelService = channelService;
        this.attachmentStorage = attachmentStorage;
    }

    // =========================================================
    // CHANNEL
    // =========================================================

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ChannelDto> createChannel(
            @Valid
            @RequestBody CreateChannelRequest request
    ) {

        ChannelDto channel =
                channelService.createChannel(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(channel);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ChannelDto> getChannel(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                channelService.getChannel(id)
        );
    }

    @GetMapping("/workspace/{workspaceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ChannelDto>> getChannelsByWorkspace(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                channelService.getChannelsByWorkspace(
                        workspaceId
                )
        );
    }

    @GetMapping(
            "/workspace/{workspaceId}/type/{type}"
    )
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ChannelDto>> getChannelsByType(
            @PathVariable UUID workspaceId,
            @PathVariable ChannelType type
    ) {

        return ResponseEntity.ok(
                channelService.getChannelsByWorkspaceAndType(
                        workspaceId,
                        type
                )
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ChannelDto> updateChannel(
            @PathVariable UUID id,
            @Valid
            @RequestBody UpdateChannelRequest request
    ) {

        return ResponseEntity.ok(
                channelService.updateChannel(
                        id,
                        request
                )
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteChannel(
            @PathVariable UUID id
    ) {
        channelService.disconnectChannelAccount(id);
        channelService.deleteChannel(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    // =========================================================
    // CHANNEL ACCOUNT
    // =========================================================

    @GetMapping("/{channelId}/account")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ChannelAccountDto> getChannelAccount(
            @PathVariable UUID channelId
    ) {

        return ResponseEntity.ok(
                channelService.getChannelAccount(
                        channelId
                )
        );
    }

    @PutMapping("/{channelId}/account")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ChannelAccountDto> updateChannelAccount(
            @PathVariable UUID channelId,
            @Valid
            @RequestBody UpdateChannelAccountRequest request
    ) {

        return ResponseEntity.ok(
                channelService.updateChannelAccount(
                        channelId,
                        request
                )
        );
    }

    @GetMapping("/{channelId}/account/avatar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InputStreamResource> getChannelAccountAvatar(
            @PathVariable UUID channelId
    ) {
        ChannelAccountDto account =
                channelService.getChannelAccount(channelId);

        String storageKey = account.avatarUrl();

        if (storageKey == null
                || storageKey.isBlank()
                || !attachmentStorage.exists(storageKey)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(
                        new InputStreamResource(
                                attachmentStorage.load(storageKey)
                        )
                );
    }

}