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
        name = "Диалоги",
        description = "API для работы с диалогами"
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
     * Создать Conversation.
     *
     * SUPER_ADMIN или EMPLOYEE с доступом к Workspace.
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
     * Найти Conversation по:
     *
     * ClientAccount + ChannelAccount.
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
     * Conversation Employee.
     *
     * EMPLOYEE может получить только свои.
     */
    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ConversationDto>>
    getEmployeeConversations(
            @PathVariable UUID employeeId
    ) {

        return ResponseEntity.ok(
                conversationService
                        .getEmployeeConversations(
                                employeeId
                        )
        );
    }

    /**
     * Неназначенные Conversation Workspace.
     */
    @GetMapping("/workspace/{workspaceId}/unassigned")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ConversationDto>>
    getUnassignedConversations(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                conversationService
                        .getUnassignedConversations(
                                workspaceId
                        )
        );
    }

    /**
     * Количество непрочитанных Conversation Workspace.
     */
    @GetMapping("/workspace/{workspaceId}/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Long> getWorkspaceUnreadCount(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                conversationService
                        .getWorkspaceUnreadCount(
                                workspaceId
                        )
        );
    }

    /**
     * Количество непрочитанных Conversation Employee.
     */
    @GetMapping("/employee/{employeeId}/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Long> getEmployeeUnreadCount(
            @PathVariable UUID employeeId
    ) {

        return ResponseEntity.ok(
                conversationService
                        .getEmployeeUnreadCount(
                                employeeId
                        )
        );
    }

    /**
     * Административное назначение Conversation
     * конкретному Employee.
     *
     * Только SUPER_ADMIN.
     */
    @PutMapping("/{conversationId}/assignment/{employeeId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
     * Административное снятие назначения.
     *
     * Только SUPER_ADMIN.
     */
    @DeleteMapping("/{conversationId}/assignment")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
     * Взять Conversation на себя.
     *
     * EMPLOYEE / SUPER_ADMIN.
     *
     * Нельзя забрать Conversation,
     * назначенный другому Employee.
     */
    @PostMapping("/{conversationId}/assignment/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> assignmentToMe(
            @PathVariable UUID conversationId
    ) {

        return ResponseEntity.ok(
                conversationService.assignmentToMe(
                        conversationId
                )
        );
    }

    /**
     * Снять с себя назначение.
     *
     * EMPLOYEE / SUPER_ADMIN.
     *
     * Нельзя снять назначение другого Employee.
     */
    @DeleteMapping("/{conversationId}/assignment/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ConversationDto> unassignmentFromMe(
            @PathVariable UUID conversationId
    ) {

        return ResponseEntity.ok(
                conversationService.unassignmentFromMe(
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