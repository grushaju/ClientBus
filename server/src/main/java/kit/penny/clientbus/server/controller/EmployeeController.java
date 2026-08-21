package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.employee.*;
import kit.penny.clientbus.server.service.EmployeeService;
import kit.penny.clientbus.server.service.EmployeeWorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
@Tag(
        name = "Сотрудники",
        description = "API для управления сотрудниками"
)
public class EmployeeController {

    private final EmployeeService employeeService;
    private final EmployeeWorkspaceService
            employeeWorkspaceService;

    public EmployeeController(
            EmployeeService employeeService,
            EmployeeWorkspaceService employeeWorkspaceService
    ) {
        this.employeeService = employeeService;
        this.employeeWorkspaceService =
                employeeWorkspaceService;
    }

    @PostMapping
    public ResponseEntity<EmployeeDto> createEmployee(
            @Valid
            @RequestBody CreateEmployeeRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        employeeService.createEmployee(
                                request
                        )
                );
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDto> getEmployee(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                employeeService.getEmployee(id)
        );
    }

    @GetMapping("/organization/{organizationId}")
    public ResponseEntity<List<EmployeeDto>>
    getEmployeesByOrganization(
            @PathVariable UUID organizationId
    ) {

        return ResponseEntity.ok(
                employeeService.getEmployeesByOrganization(
                        organizationId
                )
        );
    }

    @GetMapping("/workspace/{workspaceId}")
    public ResponseEntity<List<EmployeeDto>>
    getEmployeesByWorkspace(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                employeeService.getEmployeesByWorkspace(
                        workspaceId
                )
        );
    }

    @GetMapping("/workspace/{workspaceId}/search")
    public ResponseEntity<List<EmployeeDto>>
    searchEmployees(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) String query
    ) {

        return ResponseEntity.ok(
                employeeService.searchEmployees(
                        workspaceId,
                        query
                )
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeDto> updateEmployee(
            @PathVariable UUID id,
            @Valid
            @RequestBody UpdateEmployeeRequest request
    ) {

        return ResponseEntity.ok(
                employeeService.updateEmployee(
                        id,
                        request
                )
        );
    }

    @PutMapping("/{id}/credentials")
    public ResponseEntity<EmployeeDto> updateCredentials(
            @PathVariable UUID id,
            @Valid
            @RequestBody UpdateEmployeeCredentialsRequest request
    ) {

        return ResponseEntity.ok(
                employeeService.updateCredentials(
                        id,
                        request
                )
        );
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable UUID id,
            @Valid
            @RequestBody ChangeEmployeePasswordRequest request
    ) {

        employeeService.changePassword(
                id,
                request
        );

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<EmployeeDto> setEnabled(
            @PathVariable UUID id,
            @Valid
            @RequestBody SetEmployeeEnabledRequest request
    ) {

        return ResponseEntity.ok(
                employeeService.setEnabled(
                        id,
                        request
                )
        );
    }

    @GetMapping("/{id}/workspaces")
    public ResponseEntity<List<EmployeeWorkspaceDto>>
    getEmployeeWorkspaces(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                employeeWorkspaceService
                        .getEmployeeWorkspaces(id)
        );
    }

    @PostMapping("/{id}/workspaces")
    public ResponseEntity<EmployeeWorkspaceDto>
    assignWorkspace(
            @PathVariable UUID id,
            @Valid
            @RequestBody AssignEmployeeWorkspaceRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        employeeWorkspaceService
                                .assignWorkspace(
                                        id,
                                        request.workspaceId()
                                )
                );
    }

    @DeleteMapping("/{id}/workspaces/{workspaceId}")
    public ResponseEntity<Void> removeWorkspace(
            @PathVariable UUID id,
            @PathVariable UUID workspaceId
    ) {

        employeeWorkspaceService.removeWorkspace(
                id,
                workspaceId
        );

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmployee(
            @PathVariable UUID id
    ) {

        employeeService.deleteEmployee(id);

        return ResponseEntity.noContent().build();
    }
}