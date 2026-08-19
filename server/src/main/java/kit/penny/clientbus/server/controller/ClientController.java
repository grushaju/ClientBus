package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import kit.penny.clientbus.common.dto.client.ClientDto;
import kit.penny.clientbus.common.dto.client.CreateClientRequest;
import kit.penny.clientbus.common.dto.client.UpdateClientRequest;
import kit.penny.clientbus.server.service.ClientService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/clients")
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
}