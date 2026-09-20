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

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ClientDto> createClient(
            @Valid @RequestBody CreateClientRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        clientService.createClient(request)
                );
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ClientDto>> getClients() {

        return ResponseEntity.ok(
                clientService.getClients()
        );
    }

    @GetMapping("/without-accounts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ClientDto>>
    getClientsWithoutAccounts() {

        return ResponseEntity.ok(
                clientService.getClientsWithoutAccounts()
        );
    }

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ClientDto>> searchClients(
            @RequestParam(required = false) String query
    ) {

        return ResponseEntity.ok(
                clientService.searchClients(query)
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ClientDto> getClient(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                clientService.getClient(id)
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
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

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteClient(
            @PathVariable UUID id
    ) {

        clientService.deleteClient(id);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/clientaccounts")
    @PreAuthorize("isAuthenticated()")
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

    @PostMapping("/{clientId}/clientaccounts/{accountId}")
    @PreAuthorize("isAuthenticated()")
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

    @PostMapping(
            "/{clientId}/clientaccounts/{accountId}/reassign"
    )
    @PreAuthorize("isAuthenticated()")
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

    @DeleteMapping(
            "/{clientId}/clientaccounts/{accountId}"
    )
    @PreAuthorize("isAuthenticated()")
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

    @GetMapping("/{clientId}/clientaccounts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ClientAccountDto>>
    getAccounts(
            @PathVariable UUID clientId
    ) {

        return ResponseEntity.ok(
                clientService.getClientAccounts(clientId)
        );
    }
}