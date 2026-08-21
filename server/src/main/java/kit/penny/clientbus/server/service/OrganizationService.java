package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.organization.CreateOrganizationRequest;
import kit.penny.clientbus.common.dto.organization.OrganizationDto;
import kit.penny.clientbus.common.dto.organization.UpdateOrganizationRequest;
import kit.penny.clientbus.server.mapper.OrganizationMapper;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMapper organizationMapper;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationMapper organizationMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationMapper = organizationMapper;
    }

    public OrganizationDto createOrganization(
            CreateOrganizationRequest request
    ) {

        if (organizationRepository.existsByNameIgnoreCase(
                request.name()
        )) {
            throw new IllegalArgumentException(
                    "Organization with name already exists: "
                            + request.name()
            );
        }

        OrganizationEntity entity =
                organizationMapper.toEntity(request);

        OrganizationEntity saved =
                organizationRepository.saveAndFlush(entity);

        return organizationMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public OrganizationDto getOrganization(UUID id) {

        return organizationMapper.toDto(
                getOrganizationEntity(id)
        );
    }

    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganizations() {

        return organizationRepository.findAll()
                .stream()
                .map(organizationMapper::toDto)
                .toList();
    }

    public OrganizationDto updateOrganization(
            UUID id,
            UpdateOrganizationRequest request
    ) {

        OrganizationEntity entity =
                getOrganizationEntity(id);

        if (request.name() != null &&
                !request.name().equalsIgnoreCase(entity.getName()) &&
                organizationRepository.existsByNameIgnoreCase(
                        request.name()
                )) {

            throw new IllegalArgumentException(
                    "Organization with name already exists: "
                            + request.name()
            );
        }

        organizationMapper.updateEntity(
                entity,
                request
        );

        return organizationMapper.toDto(entity);
    }

    public void deleteOrganization(UUID id) {

        OrganizationEntity entity =
                getOrganizationEntity(id);

        organizationRepository.delete(entity);
    }

    private OrganizationEntity getOrganizationEntity(
            UUID id
    ) {

        return organizationRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Organization not found: " + id
                        )
                );
    }
}