package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.client.ClientDto;
import kit.penny.clientbus.common.dto.client.CreateClientRequest;
import kit.penny.clientbus.common.dto.client.UpdateClientRequest;
import kit.penny.clientbus.server.mapper.ClientMapper;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ClientService {

    private final ClientRepository clientRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ClientMapper clientMapper;

    public ClientService(
            ClientRepository clientRepository,
            WorkspaceRepository workspaceRepository,
            ClientMapper clientMapper
    ) {
        this.clientRepository = clientRepository;
        this.workspaceRepository = workspaceRepository;
        this.clientMapper = clientMapper;
    }

    public ClientDto createClient(CreateClientRequest request) {

        WorkspaceEntity workspace = workspaceRepository
                .findById(request.workspaceId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Workspace not found: "
                                        + request.workspaceId()
                        )
                );

        ClientEntity entity =
                clientMapper.toEntity(request, workspace);

        ClientEntity saved =
                clientRepository.saveAndFlush(entity);

        return clientMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<ClientDto> getClientsByWorkspace(UUID workspaceId) {

        return clientRepository
                .findAllByWorkspaceId(workspaceId)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClientDto> searchClients(
            UUID workspaceId,
            String query
    ) {

        return clientRepository
                .searchClients(workspaceId, query)
                .stream()
                .map(clientMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDto getClient(UUID id) {

        ClientEntity entity = clientRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Client not found: " + id
                        )
                );

        return clientMapper.toDto(entity);
    }

    @Transactional
    public ClientDto updateClient(
            UUID id,
            UpdateClientRequest request
    ) {

        ClientEntity entity = clientRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Client not found: " + id
                        )
                );

        clientMapper.updateEntity(entity, request);

        ClientEntity saved = clientRepository.save(entity);

        return clientMapper.toDto(saved);
    }

    @Transactional
    public void deleteClient(UUID id) {

        if (!clientRepository.existsById(id)) {
            throw new IllegalArgumentException(
                    "Client not found: " + id
            );
        }

        clientRepository.deleteById(id);
    }
}