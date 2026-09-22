package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.client.AddClientAccountRequest;
import kit.penny.clientbus.common.dto.client.ClientDto;
import kit.penny.clientbus.common.dto.client.ClientListItemDto;
import kit.penny.clientbus.common.dto.client.CreateClientRequest;
import kit.penny.clientbus.common.dto.client.UpdateClientRequest;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.server.service.ClientService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/clients")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Клиенты",
        description = "API для управления клиентами"
)
public class ClientController {

    private final ClientService clientService;

    public ClientController(
            ClientService clientService
    ) {
        this.clientService = clientService;
    }

    /**
     * Создать Client.
     *
     * EMPLOYEE или SUPER_ADMIN.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientDto> createClient(
            @Valid @RequestBody CreateClientRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        clientService.createClient(request)
                );
    }

    /**
     * Получить список видимых Client.
     *
     * SUPER_ADMIN:
     *   все Client текущей Organization.
     *
     * EMPLOYEE:
     *   только Client, связанные с Account,
     *   имеющими Conversation в доступных Workspace.
     *
     * Для списка используется специальный DTO:
     * ClientListItemDto.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ClientListItemDto>> getClients() {

        return ResponseEntity.ok(
                clientService.getClientListItems()
        );
    }

    /**
     * Получить Conversations Client.
     *
     * Связь:
     *
     * Client
     *   → ClientAccount
     *      → Conversation
     *
     * Никакой прямой связи Conversation → Client нет.
     *
     * SUPER_ADMIN:
     *   все Conversation Client в текущей Organization.
     *
     * EMPLOYEE:
     *   только Conversation Client в доступных Workspace.
     */
    @GetMapping("/{clientId}/conversations")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ConversationDto>>
    getClientConversations(
            @PathVariable UUID clientId
    ) {

        return ResponseEntity.ok(
                clientService.getClientConversations(
                        clientId
                )
        );
    }

    /**
     * Client без Account.
     *
     * Только SUPER_ADMIN.
     */
    @GetMapping("/without-accounts")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<ClientDto>>
    getClientsWithoutAccounts() {

        return ResponseEntity.ok(
                clientService.getClientsWithoutAccounts()
        );
    }

    /**
     * Поиск Client.
     *
     * Результат ограничивается ACL текущего пользователя.
     *
     * Для списка используется ClientListItemDto.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ClientListItemDto>> searchClients(
            @RequestParam(required = false) String query
    ) {

        return ResponseEntity.ok(
                clientService.searchClientListItems(query)
        );
    }

    /**
     * Получить Client.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientDto> getClient(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                clientService.getClient(id)
        );
    }

    /**
     * Изменить Client.
     *
     * EMPLOYEE может изменять только доступный Client.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientDto> updateClient(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateClientRequest request
    ) {

        return ResponseEntity.ok(
                clientService.updateClient(
                        id,
                        request
                )
        );
    }

    /**
     * Удалить Client.
     *
     * Только SUPER_ADMIN.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteClient(
            @PathVariable UUID id
    ) {

        clientService.deleteClient(id);

        return ResponseEntity
                .noContent()
                .build();
    }

    /**
     * Создать Client из текущего Conversation.
     *
     * ClientAccount определяется Backend через Conversation.
     */
    @PostMapping("/from-conversation/{conversationId}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientDto> createClientFromConversation(
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreateClientRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        clientService.createClientFromConversation(
                                conversationId,
                                request
                        )
                );
    }

    /**
     * Создать новый ClientAccount и связать его с Client.
     */
    @PostMapping("/{clientId}/clientaccounts")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientAccountDto>
    addClientAccount(
            @PathVariable UUID clientId,
            @Valid @RequestBody AddClientAccountRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        clientService.addClientAccount(
                                clientId,
                                request
                        )
                );
    }

    /**
     * Связать orphan ClientAccount с Client.
     */
    @PostMapping("/{clientId}/clientaccounts/{accountId}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientAccountDto>
    assignClientAccount(
            @PathVariable UUID clientId,
            @PathVariable UUID accountId
    ) {

        return ResponseEntity.ok(
                clientService.assignClientAccount(
                        clientId,
                        accountId
                )
        );
    }

    /**
     * Переназначить ClientAccount.
     */
    @PostMapping(
            "/{clientId}/clientaccounts/{accountId}/reassign"
    )
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientAccountDto>
    reassignClientAccount(
            @PathVariable UUID clientId,
            @PathVariable UUID accountId
    ) {

        return ResponseEntity.ok(
                clientService.reassignClientAccount(
                        accountId,
                        clientId
                )
        );
    }

    /**
     * Отвязать ClientAccount от Client.
     */
    @DeleteMapping(
            "/{clientId}/clientaccounts/{accountId}"
    )
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientAccountDto>
    unassignClientAccount(
            @PathVariable UUID clientId,
            @PathVariable UUID accountId
    ) {

        return ResponseEntity.ok(
                clientService.unassignClientAccount(
                        clientId,
                        accountId
                )
        );
    }

    /**
     * Получить ClientAccounts Client.
     */
    @GetMapping("/{clientId}/clientaccounts")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ClientAccountDto>>
    getAccounts(
            @PathVariable UUID clientId
    ) {

        return ResponseEntity.ok(
                clientService.getClientAccounts(clientId)
        );
    }
}