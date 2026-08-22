package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.common.dto.conversation.CreateConversationRequest;
import kit.penny.clientbus.server.service.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Conversations",
        description = "API для работы с Conversation"
)
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(
            ConversationService conversationService
    ) {
        this.conversationService =
                conversationService;
    }

    /**
     * Создать Conversation для существующих
     * ClientAccount + ChannelAccount.
     *
     * SUPER_ADMIN и EMPLOYEE с доступом к Workspace.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> createConversation(
            @Valid
            @RequestBody CreateConversationRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        conversationService.createConversation(
                                request
                        )
                );
    }

    /**
     * Получить Conversation.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> getConversation(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                conversationService.getConversation(id)
        );
    }

    /**
     * Все Conversation Workspace.
     */
    @GetMapping("/workspace/{workspaceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ConversationDto>>
    getWorkspaceConversations(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                conversationService
                        .getWorkspaceConversations(
                                workspaceId
                        )
        );
    }

    /**
     * Все Conversation ClientAccount.
     *
     * Вернутся только те,
     * к которым пользователь имеет доступ.
     */
    @GetMapping("/client-account/{clientAccountId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ConversationDto>>
    getClientAccountConversations(
            @PathVariable UUID clientAccountId
    ) {

        return ResponseEntity.ok(
                conversationService
                        .getClientAccountConversations(
                                clientAccountId
                        )
        );
    }

    /**
     * Все Conversation ChannelAccount.
     */
    @GetMapping("/channel-account/{channelAccountId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ConversationDto>>
    getChannelAccountConversations(
            @PathVariable UUID channelAccountId
    ) {

        return ResponseEntity.ok(
                conversationService
                        .getChannelAccountConversations(
                                channelAccountId
                        )
        );
    }

    /**
     * Найти Conversation по паре аккаунтов.
     */
    @GetMapping("/by-accounts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> getByAccounts(
            @RequestParam UUID channelAccountId,
            @RequestParam UUID clientAccountId
    ) {

        return ResponseEntity.ok(
                conversationService.getByAccounts(
                        channelAccountId,
                        clientAccountId
                )
        );
    }

    /**
     * Назначить Conversation сотруднику.
     */
    @PutMapping("/{conversationId}/assignment/{employeeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> assignEmployee(
            @PathVariable UUID conversationId,
            @PathVariable UUID employeeId
    ) {

        return ResponseEntity.ok(
                conversationService.assignEmployee(
                        conversationId,
                        employeeId
                )
        );
    }

    /**
     * Снять назначение.
     */
    @DeleteMapping("/{conversationId}/assignment")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> unassignEmployee(
            @PathVariable UUID conversationId
    ) {

        return ResponseEntity.ok(
                conversationService.unassignEmployee(
                        conversationId
                )
        );
    }

    /**
     * Пометить Conversation прочитанным.
     */
    @PostMapping("/{conversationId}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> markAsRead(
            @PathVariable UUID conversationId
    ) {

        return ResponseEntity.ok(
                conversationService.markAsRead(
                        conversationId
                )
        );
    }
}