
package kit.penny.clientbus.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kit.penny.clientbus.common.dto.conversation.ConversationSearchRequest;
import kit.penny.clientbus.common.dto.conversation.SaveConversationFilterRequest;
import kit.penny.clientbus.common.dto.conversation.SavedConversationFilterDto;
import kit.penny.clientbus.common.enums.SavedFilterType;
import kit.penny.clientbus.server.persistence.entity.SavedConversationFilterEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.SavedConversationFilterRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SavedConversationFilterService {

    private static final int MAX_NAME_LENGTH = 100;

    private final SavedConversationFilterRepository filterRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public SavedConversationFilterService(
            SavedConversationFilterRepository filterRepository,
            WorkspaceRepository workspaceRepository,
            CurrentUserService currentUserService
    ) {
        this.filterRepository = filterRepository;
        this.workspaceRepository = workspaceRepository;
        this.currentUserService = currentUserService;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional(readOnly = true)
    public List<SavedConversationFilterDto> getFilters(
            UUID workspaceId,
            SavedFilterType filterType
    ) {
        requireWorkspaceAccess(workspaceId);
        requireFilterType(filterType);

        List<SavedConversationFilterEntity> filters;

        if (filterType == SavedFilterType.PRIVATE) {
            UUID employeeId = currentUserService.getCurrentEmployeeId();

            filters = filterRepository
                    .findAllByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdOrderByNameAsc(
                            workspaceId,
                            filterType,
                            employeeId
                    );
        } else {
            filters = filterRepository
                    .findAllByWorkspaceIdAndFilterTypeOrderByNameAsc(
                            workspaceId,
                            filterType
                    );
        }

        return filters.stream()
                .map(this::toDto)
                .toList();
    }

    public SavedConversationFilterDto createFilter(
            UUID workspaceId,
            SaveConversationFilterRequest request
    ) {
        requireWorkspaceAccess(workspaceId);
        validateRequest(request);

        SavedFilterType filterType = request.filterType();
        UUID employeeId = currentUserService.getCurrentEmployeeId();

        if (filterType == SavedFilterType.SYSTEM) {
            requireSystemFilterManagement(workspaceId);
        }

        String name = normalizeName(request.name());
        ensureNameAvailable(
                workspaceId,
                filterType,
                employeeId,
                name
        );

        SavedConversationFilterEntity entity =
                new SavedConversationFilterEntity();

        WorkspaceEntity workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Workspace not found"
                ));

        entity.setWorkspace(workspace);
        entity.setName(name);
        entity.setFilterType(filterType);
        entity.setOwnerEmployee(
                filterType == SavedFilterType.PRIVATE
                        ? currentUserService.getCurrentEmployee()
                        : null
        );
        entity.setFilterDefinition(serialize(request));

        return toDto(filterRepository.save(entity));
    }

    public SavedConversationFilterDto updateFilter(
            UUID workspaceId,
            UUID filterId,
            SaveConversationFilterRequest request
    ) {
        requireWorkspaceAccess(workspaceId);
        validateRequest(request);

        SavedConversationFilterEntity entity =
                findEditableFilter(workspaceId, filterId);

        if (entity.getFilterType() != request.filterType()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Changing filter type is not allowed"
            );
        }

        String name = normalizeName(request.name());

        if (!entity.getName().equalsIgnoreCase(name)) {
            ensureNameAvailable(
                    workspaceId,
                    entity.getFilterType(),
                    currentUserService.getCurrentEmployeeId(),
                    name
            );
        }

        entity.setName(name);
        entity.setFilterDefinition(serialize(request));

        return toDto(entity);
    }

    public void deleteFilter(
            UUID workspaceId,
            UUID filterId
    ) {
        requireWorkspaceAccess(workspaceId);

        SavedConversationFilterEntity entity =
                findEditableFilter(workspaceId, filterId);

        filterRepository.delete(entity);
    }

    private SavedConversationFilterEntity findEditableFilter(
            UUID workspaceId,
            UUID filterId
    ) {
        SavedConversationFilterEntity entity =
                filterRepository.findByIdAndWorkspaceId(
                        filterId,
                        workspaceId
                ).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Saved filter not found"
                ));

        if (entity.getFilterType() == SavedFilterType.SYSTEM) {
            requireSystemFilterManagement(workspaceId);
            return entity;
        }

        UUID employeeId = currentUserService.getCurrentEmployeeId();

        if (entity.getOwnerEmployee() == null
                || !entity.getOwnerEmployee().getId().equals(employeeId)) {
            throw new AccessDeniedException(
                    "Private filter belongs to another employee"
            );
        }

        return entity;
    }

    private void requireWorkspaceAccess(UUID workspaceId) {
        currentUserService.requireWorkspaceAccess(workspaceId);
    }

    private void requireSystemFilterManagement(UUID workspaceId) {
        if (!currentUserService.isSuperAdmin()
                && !currentUserService.isSupervisor(workspaceId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only supervisors can manage system filters"
            );
        }
    }

    private void requireFilterType(SavedFilterType filterType) {
        if (filterType == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Filter type is required"
            );
        }
    }

    private void validateRequest(SaveConversationFilterRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Request body is required"
            );
        }

        requireFilterType(request.filterType());

        if (request.filterDefinition() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Filter definition is required"
            );
        }

        normalizeName(request.name());
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Filter name must not be blank"
            );
        }

        String normalized = name.trim();

        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Filter name must not exceed 100 characters"
            );
        }

        return normalized;
    }

    private void ensureNameAvailable(
            UUID workspaceId,
            SavedFilterType filterType,
            UUID employeeId,
            String name
    ) {
        boolean exists;

        if (filterType == SavedFilterType.PRIVATE) {
            exists = filterRepository
                    .existsByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdAndNameIgnoreCase(
                            workspaceId,
                            filterType,
                            employeeId,
                            name
                    );
        } else {
            exists = filterRepository
                    .existsByWorkspaceIdAndFilterTypeAndNameIgnoreCase(
                            workspaceId,
                            filterType,
                            name
                    );
        }

        if (exists) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A filter with this name already exists"
            );
        }
    }

    private String serialize(SaveConversationFilterRequest request) {
        try {
            return objectMapper.writeValueAsString(
                    request.filterDefinition()
            );
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to serialize filter definition",
                    exception
            );
        }
    }

    private SavedConversationFilterDto toDto(
            SavedConversationFilterEntity entity
    ) {
        try {
            return new SavedConversationFilterDto(
                    entity.getId(),
                    entity.getName(),
                    entity.getFilterType(),
                    objectMapper.readValue(
                            entity.getFilterDefinition(),
                            ConversationSearchRequest.class
                    ),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored filter definition contains invalid JSON",
                    exception
            );
        }
    }
}
