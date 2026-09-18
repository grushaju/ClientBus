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
@Tag(name = "Аккаунты клиентов", description = "API для управления аккаунтами клиентов")
public class ClientAccountController {

    private final ClientAccountService clientAccountService;

    public ClientAccountController(
            ClientAccountService clientAccountService
    ) {
        this.clientAccountService = clientAccountService;
    }

    @GetMapping("/by-ids")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ClientAccountDto>> getClientAccountsByIds(
            @RequestParam List<UUID> ids
    ) {

        return ResponseEntity.ok(
                clientAccountService.getClientAccountsByIds(ids)
        );
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
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

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ClientAccountDto> getClientAccount(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                clientAccountService.getClientAccount(id)
        );
    }

    @GetMapping("/client/{clientId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ClientAccountDto>> getClientAccountsByClient(
            @PathVariable UUID clientId
    ) {

        return ResponseEntity.ok(
                clientAccountService.getClientAccountsByClient(
                        clientId
                )
        );
    }

    @GetMapping("/client/{clientId}/type/{channelType}")
    @PreAuthorize("isAuthenticated()")
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

    @GetMapping("/unassigned")
    @PreAuthorize("isAuthenticated()")
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

    @GetMapping("/client/{clientId}/search")
    @PreAuthorize("isAuthenticated()")
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

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
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

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteClientAccount(
            @PathVariable UUID id
    ) {

        clientAccountService.deleteClientAccount(id);

        return ResponseEntity
                .noContent()
                .build();
    }
}