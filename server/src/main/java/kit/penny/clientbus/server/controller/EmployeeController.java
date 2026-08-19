package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.employee.ChangeEmployeePasswordRequest;
import kit.penny.clientbus.common.dto.employee.CreateEmployeeRequest;
import kit.penny.clientbus.common.dto.employee.EmployeeDto;
import kit.penny.clientbus.common.dto.employee.SetEmployeeEnabledRequest;
import kit.penny.clientbus.common.dto.employee.UpdateEmployeeCredentialsRequest;
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

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    /**
     * Создание сотрудника.
     * Одновременно создаётся User с username/email/password.
     */
    @PostMapping
    public ResponseEntity<EmployeeDto> createEmployee(
            @Valid
            @RequestBody CreateEmployeeRequest request
    ) {

        EmployeeDto employee =
                employeeService.createEmployee(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(employee);
    }

    /**
     * Получение сотрудника по ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDto> getEmployee(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                employeeService.getEmployee(id)
        );
    }

    /**
     * Получение всех сотрудников Workspace.
     */
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

    /**
     * Поиск сотрудника по:
     * - имени
     * - фамилии
     * - телефону
     * - username
     * - email
     */
    @GetMapping("/workspace/{workspaceId}/search")
    public ResponseEntity<List<EmployeeDto>> searchEmployees(
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

    /**
     * Изменение бизнес-данных сотрудника:
     * - firstName
     * - lastName
     * - phone
     */
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

    /**
     * Изменение username/email.
     */
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

    /**
     * Изменение пароля.
     */
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

    /**
     * Включение / отключение пользователя.
     */
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

    /**
     * Удаление сотрудника и связанного User.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmployee(
            @PathVariable UUID id
    ) {

        employeeService.deleteEmployee(id);

        return ResponseEntity.noContent().build();
    }
}