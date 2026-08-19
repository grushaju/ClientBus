package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import kit.penny.clientbus.common.dto.employee.CreateEmployeeRequest;
import kit.penny.clientbus.common.dto.employee.EmployeeDto;
import kit.penny.clientbus.common.dto.employee.UpdateEmployeeRequest;
import kit.penny.clientbus.server.service.EmployeeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
@Tag(name = "Сотрудники", description = "API для управления сотрудниками")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(
            EmployeeService employeeService
    ) {
        this.employeeService = employeeService;
    }

    @PostMapping
    public ResponseEntity<EmployeeDto> createEmployee(
            @RequestBody CreateEmployeeRequest request
    ) {

        EmployeeDto created =
                employeeService.createEmployee(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDto> getEmployee(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                employeeService.getEmployee(id)
        );
    }

    @GetMapping("/workspace/{workspaceId}")
    public ResponseEntity<List<EmployeeDto>> getEmployeesByWorkspace(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                employeeService.getEmployeesByWorkspace(
                        workspaceId
                )
        );
    }

    @GetMapping("/workspace/{workspaceId}/active")
    public ResponseEntity<List<EmployeeDto>> getActiveEmployees(
            @PathVariable UUID workspaceId
    ) {

        return ResponseEntity.ok(
                employeeService.getActiveEmployeesByWorkspace(
                        workspaceId
                )
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeDto> updateEmployee(
            @PathVariable UUID id,
            @RequestBody UpdateEmployeeRequest request
    ) {

        return ResponseEntity.ok(
                employeeService.updateEmployee(
                        id,
                        request
                )
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmployee(
            @PathVariable UUID id
    ) {

        employeeService.deleteEmployee(id);

        return ResponseEntity.noContent().build();
    }
}