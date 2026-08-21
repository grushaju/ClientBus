package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.organization.CreateOrganizationRequest;
import kit.penny.clientbus.common.dto.organization.OrganizationDto;
import kit.penny.clientbus.common.dto.organization.UpdateOrganizationRequest;
import kit.penny.clientbus.server.mapper.OrganizationMapper;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMapper organizationMapper;
    private final CurrentUserService currentUserService;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationMapper organizationMapper,
            CurrentUserService currentUserService
    ) {
        this.organizationRepository = organizationRepository;
        this.organizationMapper = organizationMapper;
        this.currentUserService = currentUserService;
    }

    /**
     * Создание Organization является
     * административной операцией.
     */
    public OrganizationDto createOrganization(
            CreateOrganizationRequest request
    ) {

        currentUserService.requireSuperAdmin();

        /*
         * В модели "одна инсталляция = одна Organization"
         * создание второй Organization через API
         * не должно быть обычной пользовательской операцией.
         */
        if (organizationRepository.count() > 0) {

            throw new IllegalArgumentException(
                    "Organization already exists"
            );
        }

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

    /**
     * SUPER_ADMIN + EMPLOYEE:
     * только собственная Organization.
     */
    @Transactional(readOnly = true)
    public OrganizationDto getOrganization(UUID id) {

        currentUserService.requireSuperAdminOrganization(
                id
        );

        return organizationMapper.toDto(
                getOrganizationEntity(id)
        );
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    @Transactional(readOnly = true)
    public List<OrganizationDto> getAllOrganizations() {

        currentUserService.requireSuperAdmin();

        return organizationRepository.findAll()
                .stream()
                .map(organizationMapper::toDto)
                .toList();
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public OrganizationDto updateOrganization(
            UUID id,
            UpdateOrganizationRequest request
    ) {

        currentUserService.requireSuperAdminOrganization(
                id
        );

        OrganizationEntity entity =
                getOrganizationEntity(id);

        if (request.name() != null &&
                !request.name().equalsIgnoreCase(
                        entity.getName()
                ) &&
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

    /**
     * SUPER_ADMIN ONLY.
     */
//    public void deleteOrganization(UUID id) {
//
//        currentUserService.requireSuperAdminOrganization(
//                id
//        );
//
//        /*
//         * В production я бы вообще не разрешал
//         * удаление Organization через обычный API.
//         */
//        throw new UnsupportedOperationException(
//                "Organization deletion is not supported"
//        );
//    }

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