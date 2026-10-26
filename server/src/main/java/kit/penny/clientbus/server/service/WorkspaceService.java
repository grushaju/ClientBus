package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.mapper.WorkspaceMapper;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class WorkspaceService {

    @org.springframework.beans.factory.annotation.Autowired
    private kit.penny.clientbus.server.notification.outbox.UiDomainEvents uiEvents;

    private final WorkspaceRepository workspaceRepository;
    private final OrganizationRepository organizationRepository;
    private final WorkspaceMapper workspaceMapper;
    private final CurrentUserService currentUserService;
    private final IAttachmentStorage attachmentStorage;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            WorkspaceMapper workspaceMapper,
            CurrentUserService currentUserService, IAttachmentStorage attachmentStorage
    ) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceMapper = workspaceMapper;
        this.currentUserService = currentUserService;
        this.attachmentStorage = attachmentStorage;
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public WorkspaceDto createWorkspace(
            CreateWorkspaceRequest request
    ) {

        currentUserService.requireSuperAdminOrganization(
                request.organizationId()
        );

        OrganizationEntity organization =
                organizationRepository.findById(
                        request.organizationId()
                ).orElseThrow(() ->
                        new EntityNotFoundException(
                                "Organization not found: "
                                        + request.organizationId()
                        )
                );

        if (workspaceRepository
                .existsByOrganizationIdAndNameIgnoreCase(
                        request.organizationId(),
                        request.name()
                )) {

            throw new IllegalArgumentException(
                    "Workspace with name already exists in organization: "
                            + request.name()
            );
        }

        WorkspaceEntity entity =
                workspaceMapper.toEntity(
                        request,
                        organization
                );

        WorkspaceEntity saved =
                workspaceRepository.saveAndFlush(entity);

        if (uiEvents != null) uiEvents.organization(saved.getOrganization().getId(), "workspace.invalidated");

        return workspaceMapper.toDto(saved);
    }

    /**
     * SUPER_ADMIN + EMPLOYEE.
     *
     * EMPLOYEE получает только Workspace,
     * назначенный ему.
     */
    @Transactional(readOnly = true)
    public WorkspaceDto getWorkspace(UUID id) {

        currentUserService.requireWorkspaceAccess(id);

        return workspaceMapper.toDto(
                getWorkspaceEntity(id)
        );
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceDto> getAllWorkspaces() {

        currentUserService.requireSuperAdmin();

        return workspaceRepository.findAll()
                .stream()
                .filter(workspace ->
                        workspace.getOrganization()
                                .getId()
                                .equals(
                                        currentUserService
                                                .getCurrentOrganizationId()
                                )
                )
                .map(workspaceMapper::toDto)
                .toList();
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceDto> getWorkspacesByOrganization(
            UUID organizationId
    ) {

        currentUserService.requireSuperAdminOrganization(
                organizationId
        );

        if (!organizationRepository.existsById(
                organizationId
        )) {

            throw new EntityNotFoundException(
                    "Organization not found: "
                            + organizationId
            );
        }

        return workspaceRepository
                .findAllByOrganizationId(organizationId)
                .stream()
                .map(workspaceMapper::toDto)
                .toList();
    }

    /**
     * SELF + SUPER_ADMIN.
     *
     * Возвращает только Workspace,
     * доступные текущему пользователю.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceDto>
    getCurrentUserWorkspaces() {

        UUID organizationId =
                currentUserService
                        .getCurrentOrganizationId();

        if (currentUserService.isSuperAdmin()) {

            return workspaceRepository
                    .findAllByOrganizationId(organizationId)
                    .stream()
                    .map(workspaceMapper::toDto)
                    .toList();
        }

        UUID employeeId =
                currentUserService
                        .getCurrentEmployeeId();

        return workspaceRepository
                .findAllWorkspacesByEmployeeId(employeeId)
                .stream()
                .map(workspaceMapper::toDto)
                .toList();
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public WorkspaceDto updateWorkspace(
            UUID id,
            UpdateWorkspaceRequest request
    ) {

        currentUserService.requireSuperAdmin();

        WorkspaceEntity entity =
                getWorkspaceEntity(id);

        currentUserService.requireWorkspaceAccess(id);

        if (request.name() != null &&
                !request.name().equalsIgnoreCase(
                        entity.getName()
                ) &&
                workspaceRepository
                        .existsByOrganizationIdAndNameIgnoreCase(
                                entity.getOrganization().getId(),
                                request.name()
                        )) {

            throw new IllegalArgumentException(
                    "Workspace with name already exists in organization: "
                            + request.name()
            );
        }

        workspaceMapper.updateEntity(
                entity,
                request
        );

        if (uiEvents != null) uiEvents.organization(entity.getOrganization().getId(), "workspace.invalidated");

        return workspaceMapper.toDto(entity);
    }

    /**
     * SUPER_ADMIN ONLY.
     */
    public void deleteWorkspace(UUID id) {

        currentUserService.requireSuperAdmin();

        WorkspaceEntity entity =
                getWorkspaceEntity(id);

        currentUserService.requireWorkspaceAccess(id);

        if (uiEvents != null) uiEvents.workspaceRemoved(entity);
        workspaceRepository.delete(entity);
    }

    /**
     * SUPER_ADMIN ONLY.
     * Загружает или заменяет аватар рабочего пространства.
     */
    public WorkspaceDto uploadWorkspaceAvatar(
            UUID workspaceId,
            MultipartFile file
    ) {
        currentUserService.requireSuperAdmin();

        WorkspaceEntity entity =
                getWorkspaceEntity(workspaceId);

        currentUserService.requireWorkspaceAccess(workspaceId);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "Avatar file is empty"
            );
        }

        String contentType = file.getContentType();

        if (contentType == null) {
            throw new IllegalArgumentException(
                    "Avatar content type is missing"
            );
        }

        contentType = contentType.toLowerCase(Locale.ROOT);

        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> throw new IllegalArgumentException(
                    "Avatar must be a JPEG, PNG, WebP or GIF image"
            );
        };

        try {
            StoredAttachmentMetadata stored =
                    attachmentStorage.store(
                            file.getInputStream(),
                            "workspace-avatar-" + workspaceId + extension,
                            file.getSize(),
                            contentType
                    );

            String previousKey = entity.getAvatarUrl();

            entity.setAvatarUrl(stored.storageKey());

            WorkspaceDto result =
                    workspaceMapper.toDto(
                            workspaceRepository.saveAndFlush(entity)
                    );

            if (previousKey != null
                    && !previousKey.isBlank()
                    && !previousKey.equals(stored.storageKey())) {
                attachmentStorage.delete(previousKey);
            }

            if (uiEvents != null) uiEvents.organization(entity.getOrganization().getId(), "workspace.invalidated");

            return result;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read workspace avatar",
                    e
            );
        }
    }

    /**
     * SUPER_ADMIN ONLY.
     * Удаляет аватар рабочего пространства.
     */
    public WorkspaceDto clearWorkspaceAvatar(
            UUID workspaceId
    ) {
        currentUserService.requireSuperAdmin();

        WorkspaceEntity entity =
                getWorkspaceEntity(workspaceId);

        currentUserService.requireWorkspaceAccess(workspaceId);

        String previousKey = entity.getAvatarUrl();

        entity.setAvatarUrl(null);

        WorkspaceDto result =
                workspaceMapper.toDto(
                        workspaceRepository.saveAndFlush(entity)
                );

        if (previousKey != null && !previousKey.isBlank()) {
            attachmentStorage.delete(previousKey);
        }

        if (uiEvents != null) uiEvents.organization(entity.getOrganization().getId(), "workspace.invalidated");

        return result;
    }

    private WorkspaceEntity getWorkspaceEntity(
            UUID id
    ) {

        return workspaceRepository.findById(id)
                .orElseThrow(() ->
                        new EntityNotFoundException(
                                "Workspace not found: " + id
                        )
                );
    }
}