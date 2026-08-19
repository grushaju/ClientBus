package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.account.AccountDto;
import kit.penny.clientbus.common.dto.account.CreateAccountRequest;
import kit.penny.clientbus.common.dto.account.UpdateAccountRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.mapper.AccountMapper;
import kit.penny.clientbus.server.persistence.entity.AccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.repository.AccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final ClientRepository clientRepository;
    private final AccountMapper accountMapper;

    public AccountService(
            AccountRepository accountRepository,
            ClientRepository clientRepository,
            AccountMapper accountMapper
    ) {
        this.accountRepository = accountRepository;
        this.clientRepository = clientRepository;
        this.accountMapper = accountMapper;
    }

    @Transactional
    public AccountDto createAccount(
            CreateAccountRequest request
    ) {

        ClientEntity client = clientRepository
                .findById(request.clientId())
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Client not found: "
                                        + request.clientId()
                        )
                );

        if (accountRepository
                .existsByClientIdAndChannelTypeAndExternalId(
                        request.clientId(),
                        request.channelType(),
                        request.externalId()
                )) {

            throw new IllegalStateException(
                    "Account already exists"
            );
        }

        AccountEntity entity =
                accountMapper.toEntity(request);

        entity.setClient(client);

        entity = accountRepository.saveAndFlush(entity);

        return accountMapper.toDto(entity);
    }

    @Transactional
    public AccountDto getAccount(UUID id) {

        return accountRepository
                .findById(id)
                .map(accountMapper::toDto)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Account not found: " + id
                        )
                );
    }

    @Transactional
    public List<AccountDto> getAccountsByClient(
            UUID clientId
    ) {

        return accountRepository
                .findAllByClientId(clientId)
                .stream()
                .map(accountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<AccountDto> getAccountsByClientAndType(
            UUID clientId,
            ChannelType channelType
    ) {

        return accountRepository
                .findAllByClientIdAndChannelType(
                        clientId,
                        channelType
                )
                .stream()
                .map(accountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<AccountDto> searchAccounts(
            UUID clientId,
            String query
    ) {

        if (query == null || query.isBlank()) {
            return getAccountsByClient(clientId);
        }

        return accountRepository
                .searchByClient(
                        clientId,
                        query.trim()
                )
                .stream()
                .map(accountMapper::toDto)
                .toList();
    }

    @Transactional
    public AccountDto updateAccount(
            UUID id,
            UpdateAccountRequest request
    ) {

        AccountEntity entity = accountRepository
                .findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Account not found: " + id
                        )
                );

        accountMapper.updateEntity(
                entity,
                request
        );

        return accountMapper.toDto(entity);
    }

    @Transactional
    public void deleteAccount(UUID id) {

        if (!accountRepository.existsById(id)) {
            throw new EntityNotFoundException(
                    "Account not found: " + id
            );
        }

        accountRepository.deleteById(id);
    }
}