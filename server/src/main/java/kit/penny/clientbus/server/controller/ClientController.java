package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.client.AddClientAccountRequest;
import kit.penny.clientbus.common.dto.client.ClientDto;
import kit.penny.clientbus.common.dto.client.CreateClientRequest;
import kit.penny.clientbus.common.dto.client.UpdateClientRequest;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.server.service.ClientService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/clients")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Клиенты", description = "API для управления клиентами")
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @PostMapping
    public ResponseEntity<ClientDto> createClient(
            @RequestBody CreateClientRequest request
    ) {

        ClientDto created =
                clientService.createClient(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(created);
    }

    @GetMapping("/workspace/{workspaceId}")
    public ResponseEntity<List<ClientDto>> getClientsByWorkspace(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                clientService.getClientsByWorkspace(workspaceId)
        );
    }

    @GetMapping("/workspace/{workspaceId}/without-accounts")
    public ResponseEntity<List<ClientDto>> getClientsWithoutAccounts(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                clientService.getClientsWithoutAccounts(
                        workspaceId
                )
        );
    }

    @GetMapping("/workspace/{workspaceId}/search")
    public ResponseEntity<List<ClientDto>> searchClients(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) String query
    ) {

        return ResponseEntity.ok(
                clientService.searchClients(
                        workspaceId,
                        query
                )
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClientDto> getClient(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                clientService.getClient(id)
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<ClientDto> updateClient(
            @PathVariable UUID id,
            @RequestBody UpdateClientRequest request
    ) {
        return ResponseEntity.ok(
                clientService.updateClient(id, request)
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteClient(
            @PathVariable UUID id
    ) {
        clientService.deleteClient(id);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/clientaccounts")
    public ResponseEntity<ClientAccountDto> addClientAccount(
            @PathVariable UUID clientId,
            @Valid @RequestBody AddClientAccountRequest request
    ) {

        ClientAccountDto account =
                clientService.addClientAccount(
                        clientId,
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(account);
    }

    @PostMapping("/{clientId}/clientaccounts/{accountId}")
    public ResponseEntity<ClientAccountDto> assignClientAccount(
            @PathVariable UUID clientId,
            @PathVariable UUID accountId
    ) {

        ClientAccountDto account =
                clientService.assignClientAccount(
                        clientId,
                        accountId
                );

        return ResponseEntity.ok(account);
    }

    @PostMapping("/{clientId}/clientaccounts/{accountId}/reassign")
    public ResponseEntity<ClientAccountDto> reassignClientAccount(
            @PathVariable UUID accountId,
            @PathVariable UUID clientId
    ) {

        ClientAccountDto account =
                clientService.reassignClientAccount(
                        accountId,
                        clientId
                );

        return ResponseEntity.ok(account);
    }

    @DeleteMapping("/clientaccounts/{accountId}")
    public ResponseEntity<ClientAccountDto> unassignClientAccount(
            @PathVariable UUID accountId
    ) {

        ClientAccountDto account =
                clientService.unassignClientAccount(
                        accountId
                );

        return ResponseEntity.ok(account);
    }

    @GetMapping("/{clientId}/clientaccounts")
    public ResponseEntity<List<ClientAccountDto>> getAccounts(
            @PathVariable UUID clientId
    ) {

        return ResponseEntity.ok(
                clientService.getClientAccounts(clientId)
        );
    }
}