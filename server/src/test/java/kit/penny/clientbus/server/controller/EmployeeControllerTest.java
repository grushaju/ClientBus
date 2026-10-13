package kit.penny.clientbus.server.controller;

import kit.penny.clientbus.common.dto.employee.AssignEmployeeWorkspaceRequest;
import kit.penny.clientbus.common.dto.employee.EmployeeWorkspaceDto;
import kit.penny.clientbus.common.dto.employee.SetEmployeeWorkspaceSupervisorRequest;
import kit.penny.clientbus.server.service.EmployeeService;
import kit.penny.clientbus.server.service.EmployeeWorkspaceService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeControllerTest {

    @Mock
    private EmployeeService employeeService;

    @Mock
    private EmployeeWorkspaceService employeeWorkspaceService;

    @Mock
    private IAttachmentStorage attachmentStorage;

    private EmployeeController controller;

    private UUID employeeId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        controller = new EmployeeController(
                employeeService,
                employeeWorkspaceService,
                attachmentStorage
        );

        employeeId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
    }

    @Test
    void assignWorkspace_returnsCreatedWorkspaceAssignment() {
        AssignEmployeeWorkspaceRequest request =
                new AssignEmployeeWorkspaceRequest(workspaceId, false);

        EmployeeWorkspaceDto expected =
                new EmployeeWorkspaceDto(
                        employeeId,
                        workspaceId,
                        false
                );

        when(employeeWorkspaceService.assignWorkspace(
                employeeId,
                workspaceId,
                false
        )).thenReturn(expected);

        ResponseEntity<EmployeeWorkspaceDto> response =
                controller.assignWorkspace(employeeId, request);

        assertEquals(201, response.getStatusCode().value());
        assertSame(expected, response.getBody());

        verify(employeeWorkspaceService).assignWorkspace(
                employeeId,
                workspaceId,
                false
        );
    }

    @Test
    void setSupervisor_returnsUpdatedWorkspaceAssignment() {
        SetEmployeeWorkspaceSupervisorRequest request =
                new SetEmployeeWorkspaceSupervisorRequest(true);

        EmployeeWorkspaceDto expected =
                new EmployeeWorkspaceDto(
                        employeeId,
                        workspaceId,
                        true
                );

        when(employeeWorkspaceService.setSupervisor(
                employeeId,
                workspaceId,
                true
        )).thenReturn(expected);

        ResponseEntity<EmployeeWorkspaceDto> response =
                controller.setSupervisor(
                        employeeId,
                        workspaceId,
                        request
                );

        assertEquals(200, response.getStatusCode().value());
        assertSame(expected, response.getBody());

        verify(employeeWorkspaceService).setSupervisor(
                employeeId,
                workspaceId,
                true
        );
    }
}
