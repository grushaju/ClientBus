package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.employee.*;
import kit.penny.clientbus.server.service.EmployeeService;
import kit.penny.clientbus.server.service.EmployeeWorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Сотрудники",
        description = "API для управления сотрудниками"
)
public class EmployeeController {

    private final EmployeeService employeeService;
    private final EmployeeWorkspaceService employeeWorkspaceService;

    public EmployeeController(
            EmployeeService employeeService,
            EmployeeWorkspaceService employeeWorkspaceService
    ) {
        this.employeeService = employeeService;
        this.employeeWorkspaceService =
                employeeWorkspaceService;
    }

    /*
     * =========================
     * SELF SERVICE
     * =========================
     */

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmployeeDto> getCurrentEmployee() {

        return ResponseEntity.ok(
                employeeService.getCurrentEmployee()
        );
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmployeeDto> updateCurrentEmployee(
            @Valid
            @RequestBody UpdateEmployeeRequest request
    ) {

        return ResponseEntity.ok(
                employeeService.updateCurrentEmployee(
                        request
                )
        );
    }

    @PutMapping("/me/credentials")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmployeeDto> updateCurrentCredentials(
            @Valid
            @RequestBody UpdateEmployeeCredentialsRequest request
    ) {

        return ResponseEntity.ok(
                employeeService.updateCurrentCredentials(
                        request
                )
        );
    }

    @PutMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changeCurrentPassword(
            @Valid
            @RequestBody ChangeEmployeePasswordRequest request
    ) {

        employeeService.changeCurrentPassword(
                request
        );

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/workspaces")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<EmployeeWorkspaceDto>>
    getCurrentEmployeeWorkspaces() {

        return ResponseEntity.ok(
                employeeWorkspaceService
                        .getCurrentEmployeeWorkspaces()
        );
    }

    /*
     * =========================
     * ADMIN
     * =========================
     */

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<EmployeeDto> getEmployee(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                employeeService.getEmployee(id)
        );
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
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
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteEmployee(
            @PathVariable UUID id
    ) {

        employeeService.deleteEmployee(id);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/password/reset")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<EmployeeDto> resetEmployeePassword(
            @PathVariable UUID id,
            @Valid
            @RequestBody ResetEmployeePasswordRequest request
    ) {

        employeeService.resetEmployeePassword(id, request);

        return ResponseEntity.noContent().build();
    }
}