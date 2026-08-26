package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.message.ForwardMessageRequest;
import kit.penny.clientbus.common.dto.message.MessageAttachmentDto;
import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.common.dto.message.OutboundMessageRequest;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.server.service.AttachmentContent;
import kit.penny.clientbus.server.service.IMessageProcessingService;
import kit.penny.clientbus.server.service.MessageAttachmentService;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.server.storage.StoredAttachment;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Сообщения",
        description = "API для работы с сообщениями"
)
public class MessageController {

    private final IMessageProcessingService messageProcessingService;
    private final MessageService messageService;
    private final MessageAttachmentService messageAttachmentService;

    public MessageController(
            IMessageProcessingService messageProcessingService,
            MessageService messageService,
            MessageAttachmentService messageAttachmentService
    ) {
        this.messageProcessingService = messageProcessingService;
        this.messageService = messageService;
        this.messageAttachmentService = messageAttachmentService;
    }

    @GetMapping("/{messageId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MessageDto> getMessage(
            @PathVariable UUID messageId
    ) {
        return ResponseEntity.ok(
                messageService.getMessage(messageId)
        );
    }

    @PostMapping(
            value = "/outbound",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MessageDto> processOutbound(
            @Valid
            @RequestPart("request")
            OutboundMessageRequest request,

            @RequestPart(
                    value = "attachments",
                    required = false
            )
            List<MultipartFile> attachments
    ) {
        return ResponseEntity.ok(
                messageProcessingService.processOutbound(
                        request,
                        toAttachmentContents(attachments)
                )
        );
    }

    @PostMapping("/forward")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MessageDto> forwardMessage(
            @Valid
            @RequestBody ForwardMessageRequest request
    ) {
        return ResponseEntity.ok(
                messageProcessingService.forwardMessage(request)
        );
    }

    @GetMapping("/{messageId}/attachments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MessageAttachmentDto>> getAttachments(
            @PathVariable UUID messageId
    ) {
        return ResponseEntity.ok(
                messageAttachmentService.getAttachments(messageId)
        );
    }

    @GetMapping(
            "/{messageId}/attachments/{attachmentId}"
    )
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InputStreamResource> downloadAttachment(
            @PathVariable UUID messageId,
            @PathVariable UUID attachmentId
    ) {
        StoredAttachment stored =
                messageAttachmentService.downloadAttachment(
                        messageId,
                        attachmentId
                );

        InputStreamResource resource =
                new InputStreamResource(
                        stored.inputStream()
                );

        MediaType mediaType;

        try {
            mediaType = MediaType.parseMediaType(
                    stored.contentType()
            );
        } catch (IllegalArgumentException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(stored.size())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\""
                                + stored.fileName()
                                + "\""
                )
                .body(resource);
    }

    private List<AttachmentContent> toAttachmentContents(
            List<MultipartFile> files
    ) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        return files.stream()
                .map(this::toAttachmentContent)
                .toList();
    }

    private AttachmentContent toAttachmentContent(
            MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "Attachment must not be empty"
            );
        }

        String contentType = file.getContentType();

        MessageAttachmentType type =
                resolveAttachmentType(contentType);

        try {
            return new AttachmentContent(
                    type,
                    file.getOriginalFilename(),
                    contentType,
                    file.getSize(),
                    file.getInputStream()
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read attachment: "
                            + file.getOriginalFilename(),
                    e
            );
        }
    }

    private MessageAttachmentType resolveAttachmentType(
            String contentType
    ) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException(
                    "Attachment content type must not be blank"
            );
        }

        if (contentType.startsWith("image/")) {
            return MessageAttachmentType.IMAGE;
        }

        if (contentType.startsWith("audio/")) {
            return MessageAttachmentType.AUDIO;
        }

        throw new IllegalArgumentException(
                "Unsupported attachment content type: "
                        + contentType
        );
    }
}