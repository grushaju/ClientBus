package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import kit.penny.clientbus.common.dto.clientaccount.ClientAccountDto;
import kit.penny.clientbus.common.dto.clientaccount.CreateClientAccountRequest;
import kit.penny.clientbus.common.dto.clientaccount.UpdateClientAccountRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.mapper.ClientAccountMapper;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ClientAccountService {

    private final ClientAccountRepository clientAccountRepository;
    private final ClientRepository clientRepository;
    private final ClientAccountMapper clientAccountMapper;

    public ClientAccountService(
            ClientAccountRepository clientAccountRepository,
            ClientRepository clientRepository,
            ClientAccountMapper clientAccountMapper
    ) {
        this.clientAccountRepository = clientAccountRepository;
        this.clientRepository = clientRepository;
        this.clientAccountMapper = clientAccountMapper;
    }

    @Transactional
    public ClientAccountDto createClientAccount(
            CreateClientAccountRequest request
    ) {
        ClientEntity client = null;
        if (request.clientId() != null) {
            client = clientRepository
                    .findById(request.clientId())
                    .orElseThrow(() ->
                            new EntityNotFoundException(
                                    "Client not found: "
                                            + request.clientId()
                            )
                    );
        }

        if (clientAccountRepository
                .existsByClientIdAndChannelTypeAndExternalId(
                        request.clientId(),
                        request.channelType(),
                        request.externalId()
                )) {

            throw new IllegalStateException(
                    "Account already exists"
            );
        }

        ClientAccountEntity entity =
                clientAccountMapper.toEntity(request);

        entity.setClient(client);

        entity = clientAccountRepository.saveAndFlush(entity);

        return clientAccountMapper.toDto(entity);
    }

    @Transactional
    public ClientAccountDto getClientAccount(UUID id) {

        return clientAccountRepository
                .findById(id)
                .map(clientAccountMapper::toDto)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Account not found: " + id
                        )
                );
    }

    @Transactional
    public List<ClientAccountDto> getClientAccountsByClient(
            UUID clientId
    ) {

        return clientAccountRepository
                .findAllByClientId(clientId)
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> getClientAccountsByClientAndType(
            UUID clientId,
            ChannelType channelType
    ) {

        return clientAccountRepository
                .findAllByClientIdAndChannelType(
                        clientId,
                        channelType
                )
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> getUnassignedAccounts() {

        return clientAccountRepository
                .findAllByClientIsNull()
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> getUnassignedAccounts(
            ChannelType channelType
    ) {

        return clientAccountRepository
                .findAllByClientIsNullAndChannelType(channelType)
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public List<ClientAccountDto> searchClientAccounts(
            UUID clientId,
            String query
    ) {

        if (query == null || query.isBlank()) {
            return getClientAccountsByClient(clientId);
        }

        return clientAccountRepository
                .searchByClient(
                        clientId,
                        query.trim()
                )
                .stream()
                .map(clientAccountMapper::toDto)
                .toList();
    }

    @Transactional
    public ClientAccountDto updateClientAccount(
            UUID id,
            UpdateClientAccountRequest request
    ) {

        ClientAccountEntity entity = clientAccountRepository
                .findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Account not found: " + id
                        )
                );

        clientAccountMapper.updateEntity(
                entity,
                request
        );

        return clientAccountMapper.toDto(entity);
    }

    @Transactional
    public void deleteClientAccount(UUID id) {

        if (!clientAccountRepository.existsById(id)) {
            throw new EntityNotFoundException(
                    "Account not found: " + id
            );
        }

        clientAccountRepository.deleteById(id);
    }
}