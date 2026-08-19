package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.account.AccountDto;
import kit.penny.clientbus.common.dto.account.CreateAccountRequest;
import kit.penny.clientbus.common.dto.account.UpdateAccountRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Аккаунты клиентов", description = "API для управления аккаунтами клиентов")
public class AccountController {

    private final AccountService accountService;

    public AccountController(
            AccountService accountService
    ) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountDto> createAccount(
            @Valid
            @RequestBody CreateAccountRequest request
    ) {

        AccountDto account =
                accountService.createAccount(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(account);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountDto> getAccount(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                accountService.getAccount(id)
        );
    }

    @GetMapping("/client/{clientId}")
    public ResponseEntity<List<AccountDto>> getAccountsByClient(
            @PathVariable UUID clientId
    ) {

        return ResponseEntity.ok(
                accountService.getAccountsByClient(
                        clientId
                )
        );
    }

    @GetMapping("/client/{clientId}/type/{channelType}")
    public ResponseEntity<List<AccountDto>> getAccountsByType(
            @PathVariable UUID clientId,
            @PathVariable ChannelType channelType
    ) {

        return ResponseEntity.ok(
                accountService.getAccountsByClientAndType(
                        clientId,
                        channelType
                )
        );
    }

    @GetMapping("/client/{clientId}/search")
    public ResponseEntity<List<AccountDto>> searchAccounts(
            @PathVariable UUID clientId,
            @RequestParam String query
    ) {

        return ResponseEntity.ok(
                accountService.searchAccounts(
                        clientId,
                        query
                )
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountDto> updateAccount(
            @PathVariable UUID id,
            @Valid
            @RequestBody UpdateAccountRequest request
    ) {

        return ResponseEntity.ok(
                accountService.updateAccount(
                        id,
                        request
                )
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(
            @PathVariable UUID id
    ) {

        accountService.deleteAccount(id);

        return ResponseEntity
                .noContent()
                .build();
    }
}