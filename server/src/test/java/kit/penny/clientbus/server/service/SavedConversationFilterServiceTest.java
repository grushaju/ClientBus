
package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.conversation.ConversationSearchRequest;
import kit.penny.clientbus.common.dto.conversation.ConversationClientLinkFilter;
import kit.penny.clientbus.common.dto.conversation.ConversationSort;
import kit.penny.clientbus.common.dto.conversation.ConversationUnreadFilter;
import kit.penny.clientbus.common.dto.conversation.ConversationView;
import kit.penny.clientbus.common.dto.conversation.SaveConversationFilterRequest;
import kit.penny.clientbus.common.enums.SavedFilterType;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.SavedConversationFilterEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.SavedConversationFilterRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SavedConversationFilterServiceTest {

    @Mock
    private SavedConversationFilterRepository filterRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private CurrentUserService currentUserService;

    private SavedConversationFilterService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SavedConversationFilterService(
                filterRepository,
                workspaceRepository,
                currentUserService
        );
    }

    @Test
    void createPrivateFilter_assignsCurrentEmployeeAsOwner() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        EmployeeEntity employee = new EmployeeEntity();

        when(workspaceRepository.findById(workspaceId))
                .thenReturn(Optional.of(workspace));
        when(currentUserService.getCurrentEmployeeId())
                .thenReturn(employeeId);
        when(currentUserService.getCurrentEmployee())
                .thenReturn(employee);
        when(filterRepository
                .existsByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdAndNameIgnoreCase(
                        workspaceId,
                        SavedFilterType.PRIVATE,
                        employeeId,
                        "Important"
                ))
                .thenReturn(false);
        when(filterRepository.save(any(SavedConversationFilterEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createFilter(
                workspaceId,
                request("  Important  ", SavedFilterType.PRIVATE)
        );

        assertThat(result.name()).isEqualTo("Important");
        assertThat(result.filterType()).isEqualTo(SavedFilterType.PRIVATE);

        verify(filterRepository).save(argThat(filter ->
                filter.getWorkspace() == workspace
                        && filter.getOwnerEmployee() == employee
                        && filter.getFilterType() == SavedFilterType.PRIVATE
                        && filter.getName().equals("Important")
                        && filter.getFilterDefinition() != null
        ));
    }

    @Test
    void createSystemFilter_isRejectedForRegularEmployee() {
        when(currentUserService.getCurrentEmployeeId())
                .thenReturn(employeeId);
        when(currentUserService.isSuperAdmin()).thenReturn(false);
        when(currentUserService.isSupervisor(workspaceId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.createFilter(
                workspaceId,
                request("Shared", SavedFilterType.SYSTEM)
        )).isInstanceOf(AccessDeniedException.class);

        verify(filterRepository, never()).save(any());
    }

    @Test
    void createPrivateFilter_rejectsDuplicateNameIgnoringCase() {
        when(currentUserService.getCurrentEmployeeId())
                .thenReturn(employeeId);
        when(filterRepository
                .existsByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdAndNameIgnoreCase(
                        workspaceId,
                        SavedFilterType.PRIVATE,
                        employeeId,
                        "Important"
                ))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createFilter(
                workspaceId,
                request("Important", SavedFilterType.PRIVATE)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode()
                ).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));

        verify(filterRepository, never()).save(any());
    }

    @Test
    void getPrivateFilters_requestsOnlyCurrentEmployeesFilters() {
        when(currentUserService.getCurrentEmployeeId())
                .thenReturn(employeeId);
        when(filterRepository
                .findAllByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdOrderByNameAsc(
                        workspaceId,
                        SavedFilterType.PRIVATE,
                        employeeId
                ))
                .thenReturn(List.of());

        var result = service.getFilters(
                workspaceId,
                SavedFilterType.PRIVATE
        );

        assertThat(result).isEmpty();

        verify(filterRepository)
                .findAllByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdOrderByNameAsc(
                        workspaceId,
                        SavedFilterType.PRIVATE,
                        employeeId
                );
        verify(filterRepository, never())
                .findAllByWorkspaceIdAndFilterTypeOrderByNameAsc(
                        any(), any()
                );
    }

    @Test
    void updatePrivateFilter_rejectsFilterOwnedByAnotherEmployee() {
        UUID filterId = UUID.randomUUID();
        UUID anotherEmployeeId = UUID.randomUUID();

        EmployeeEntity anotherEmployee = new EmployeeEntity();
        anotherEmployee.setId(anotherEmployeeId);

        SavedConversationFilterEntity entity =
                new SavedConversationFilterEntity();
        entity.setFilterType(SavedFilterType.PRIVATE);
        entity.setOwnerEmployee(anotherEmployee);
        entity.setName("Original");

        when(filterRepository.findByIdAndWorkspaceId(filterId, workspaceId))
                .thenReturn(Optional.of(entity));
        when(currentUserService.getCurrentEmployeeId())
                .thenReturn(employeeId);

        assertThatThrownBy(() -> service.updateFilter(
                workspaceId,
                filterId,
                request("Changed", SavedFilterType.PRIVATE)
        )).isInstanceOf(AccessDeniedException.class);

        verify(filterRepository, never()).save(any());
    }

    @Test
    void filterWithActivityDates_preservesAllConditionsOnCreateUpdateAndReload() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        EmployeeEntity employee = new EmployeeEntity();
        employee.setId(employeeId);
        UUID filterId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-16T21:00:00Z");
        Instant to = Instant.parse("2026-10-17T20:59:59.999999Z");
        ConversationSearchRequest definition = new ConversationSearchRequest(
                ConversationView.MINE, null, "alice", List.of(UUID.randomUUID()),
                List.of(UUID.randomUUID()), ConversationClientLinkFilter.WITH_CLIENT,
                ConversationUnreadFilter.HAS_UNREAD, from, to,
                ConversationSort.LAST_MESSAGE_ASC, null, null, List.of(UUID.randomUUID())
        );
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(currentUserService.getCurrentEmployeeId()).thenReturn(employeeId);
        when(currentUserService.getCurrentEmployee()).thenReturn(employee);
        when(filterRepository.save(any(SavedConversationFilterEntity.class)))
                .thenAnswer(invocation -> {
                    SavedConversationFilterEntity entity = invocation.getArgument(0);
                    entity.setId(filterId);
                    return entity;
                });

        var created = service.createFilter(workspaceId,
                new SaveConversationFilterRequest("With dates", SavedFilterType.PRIVATE, definition));
        assertThat(created.filterDefinition()).isEqualTo(definition);

        var captor = org.mockito.ArgumentCaptor.forClass(SavedConversationFilterEntity.class);
        verify(filterRepository).save(captor.capture());
        SavedConversationFilterEntity stored = captor.getValue();
        assertThat(stored.getFilterDefinition()).contains("2026-10-17T20:59:59.999999Z");
        when(filterRepository.findByIdAndWorkspaceId(filterId, workspaceId)).thenReturn(Optional.of(stored));
        var updated = service.updateFilter(workspaceId, filterId,
                new SaveConversationFilterRequest("Renamed with dates", SavedFilterType.PRIVATE, definition));
        assertThat(updated.filterDefinition()).isEqualTo(definition);
        when(filterRepository.findAllByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdOrderByNameAsc(
                workspaceId, SavedFilterType.PRIVATE, employeeId)).thenReturn(List.of(stored));
        var reloaded = service.getFilters(workspaceId, SavedFilterType.PRIVATE);
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.getFirst().filterDefinition()).isEqualTo(definition);
    }

    private SaveConversationFilterRequest request(
            String name,
            SavedFilterType type
    ) {
        return new SaveConversationFilterRequest(
                name,
                type,
                new ConversationSearchRequest(
                        null, null, null, null, null, null,
                        null, null, null, null, 0, 20
                )
        );
    }
}
