package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @GetMapping("/conversation/{conversationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MessageDto>> getConversationMessages(
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "Page must be greater than or equal to 0"
            );
        }

        if (size <= 0) {
            throw new IllegalArgumentException(
                    "Size must be greater than 0"
            );
        }

        int pageSize = Math.min(size, 100);

        return ResponseEntity.ok(
                messageService.getConversationMessages(
                        conversationId,
                        PageRequest.of(page, pageSize)
                )
        );
    }

    @PostMapping(
            value = "/outbound",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<MessageDto> processOutbound(
            @Parameter(
                    name = "request",
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(
                                    implementation = OutboundMessageRequest.class
                            )
                    )
            )
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
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<MessageDto> forwardMessage(
            @Valid
            @RequestBody ForwardMessageRequest request
    ) {
        return ResponseEntity.ok(
                messageProcessingService.forwardMessage(request)
        );
    }

    @PostMapping("/{messageId}/retry")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ResponseEntity<MessageDto> retryOutbound(
            @PathVariable UUID messageId
    ) {
        return ResponseEntity.ok(
                messageProcessingService.retryOutbound(
                        messageId
                )
        );
    }

    @GetMapping("/{messageId}/attachments")
    @PreAuthorize("hasRole('EMPLOYEE')")
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

        ContentDisposition contentDisposition =
                ContentDisposition.attachment()
                        .filename(
                                stored.fileName(),
                                StandardCharsets.UTF_8
                        )
                        .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(stored.size())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition.toString()
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