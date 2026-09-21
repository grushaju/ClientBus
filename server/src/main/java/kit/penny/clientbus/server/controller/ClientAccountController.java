package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.dto.clientaccount.CreateClientAccountRequest;
import kit.penny.clientbus.common.dto.clientaccount.UpdateClientAccountRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.service.ClientAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/clientaccounts")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Аккаунты клиентов",
        description = "API для управления аккаунтами клиентов"
)
public class ClientAccountController {

    private final ClientAccountService clientAccountService;

    public ClientAccountController(
            ClientAccountService clientAccountService
    ) {
        this.clientAccountService = clientAccountService;
    }

    /**
     * Получить ClientAccount по списку идентификаторов.
     *
     * Доступ:
     * EMPLOYEE или SUPER_ADMIN.
     *
     * Фактический scope проверяется в Service.
     */
    @GetMapping("/by-ids")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ClientAccountDto>> getClientAccountsByIds(
            @RequestParam List<UUID> ids
    ) {

        return ResponseEntity.ok(
                clientAccountService.getClientAccountsByIds(ids)
        );
    }

    /**
     * Создать ClientAccount вручную.
     *
     * Только SUPER_ADMIN.
     *
     * EMPLOYEE не создаёт ClientAccount
     * через самостоятельный CRUD endpoint.
     *
     * Для нового внешнего получателя Employee используется
     * POST /api/conversations/outbound, где ClientAccount
     * при необходимости создаётся вместе с Conversation.
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ClientAccountDto> createClientAccount(
            @Valid
            @RequestBody CreateClientAccountRequest request
    ) {

        ClientAccountDto account =
                clientAccountService.createClientAccount(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(account);
    }

    /**
     * Получить ClientAccount.
     *
     * Доступ:
     * EMPLOYEE или SUPER_ADMIN.
     *
     * Фактический доступ определяется через Conversation.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientAccountDto> getClientAccount(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                clientAccountService.getClientAccount(id)
        );
    }

    /**
     * Получить все ClientAccount конкретного Client.
     *
     * Доступ:
     * EMPLOYEE или SUPER_ADMIN.
     *
     * Service дополнительно проверяет доступ
     * к самому Client.
     */
    @GetMapping("/client/{clientId}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ClientAccountDto>> getClientAccountsByClient(
            @PathVariable UUID clientId
    ) {

        return ResponseEntity.ok(
                clientAccountService.getClientAccountsByClient(
                        clientId
                )
        );
    }

    /**
     * Получить ClientAccount конкретного Client
     * определённого типа канала.
     *
     * Доступ:
     * EMPLOYEE или SUPER_ADMIN.
     */
    @GetMapping("/client/{clientId}/type/{channelType}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ClientAccountDto>> getClientAccountsByType(
            @PathVariable UUID clientId,
            @PathVariable ChannelType channelType
    ) {

        return ResponseEntity.ok(
                clientAccountService.getClientAccountsByClientAndType(
                        clientId,
                        channelType
                )
        );
    }

    /**
     * Получить orphan ClientAccount.
     *
     * Только SUPER_ADMIN.
     *
     * Orphan аккаунты должны иметь хотя бы одну Conversation,
     * через которую определяется их Organization.
     */
    @GetMapping("/unassigned")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<ClientAccountDto>> getUnassignedAccounts(
            @RequestParam(required = false) ChannelType channelType
    ) {

        if (channelType == null) {
            return ResponseEntity.ok(
                    clientAccountService.getUnassignedAccounts()
            );
        }

        return ResponseEntity.ok(
                clientAccountService.getUnassignedAccounts(
                        channelType
                )
        );
    }

    /**
     * Поиск ClientAccount конкретного Client.
     *
     * Доступ:
     * EMPLOYEE или SUPER_ADMIN.
     */
    @GetMapping("/client/{clientId}/search")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<List<ClientAccountDto>> searchClientAccounts(
            @PathVariable UUID clientId,
            @RequestParam String query
    ) {

        return ResponseEntity.ok(
                clientAccountService.searchClientAccounts(
                        clientId,
                        query
                )
        );
    }

    /**
     * Обновить ClientAccount.
     *
     * Доступ:
     * EMPLOYEE или SUPER_ADMIN.
     *
     * Фактический доступ проверяется в Service
     * через Conversation.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'SUPER_ADMIN')")
    public ResponseEntity<ClientAccountDto> updateClientAccount(
            @PathVariable UUID id,
            @Valid
            @RequestBody UpdateClientAccountRequest request
    ) {

        return ResponseEntity.ok(
                clientAccountService.updateClientAccount(
                        id,
                        request
                )
        );
    }

    /**
     * Удалить ClientAccount.
     *
     * Только SUPER_ADMIN.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteClientAccount(
            @PathVariable UUID id
    ) {

        clientAccountService.deleteClientAccount(id);

        return ResponseEntity
                .noContent()
                .build();
    }
}