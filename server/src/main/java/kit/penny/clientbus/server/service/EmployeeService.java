package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.employee.CreateEmployeeRequest;
import kit.penny.clientbus.common.dto.employee.EmployeeDto;
import kit.penny.clientbus.common.dto.employee.UpdateEmployeeRequest;
import kit.penny.clientbus.server.mapper.EmployeeMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EmployeeMapper employeeMapper;
    private final UserService userService;

    public EmployeeService(
            EmployeeRepository employeeRepository,
            WorkspaceRepository workspaceRepository,
            EmployeeMapper employeeMapper,
            UserService userService
    ) {
        this.employeeRepository = employeeRepository;
        this.workspaceRepository = workspaceRepository;
        this.employeeMapper = employeeMapper;
        this.userService = userService;
    }

    public EmployeeDto createEmployee(
            CreateEmployeeRequest request
    ) {

        WorkspaceEntity workspace =
                workspaceRepository.findById(
                        request.workspaceId()
                ).orElseThrow(() ->
                        new IllegalArgumentException(
                                "Workspace not found: "
                                        + request.workspaceId()
                        )
                );

        if (request.email() != null &&
                employeeRepository.existsByWorkspaceIdAndEmail(
                        request.workspaceId(),
                        request.email()
                )) {

            throw new IllegalArgumentException(
                    "Employee with email already exists in workspace"
            );
        }

        /*
         * UserService отвечает за:
         * login
         * password
         * passwordHash
         */
        UserEntity user =
                userService.createUser(
                        request.login(),
                        request.password()
                );

        EmployeeEntity employee =
                new EmployeeEntity();

        employee.setWorkspace(workspace);
        employee.setUser(user);

        employee.setFirstName(
                request.firstName()
        );

        employee.setLastName(
                request.lastName()
        );

        employee.setPhone(
                request.phone()
        );

        employee.setEmail(
                request.email()
        );

        employee.setEnabled(true);

        EmployeeEntity saved =
                employeeRepository.save(employee);

        return employeeMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public EmployeeDto getEmployee(UUID id) {

        EmployeeEntity employee =
                employeeRepository.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Employee not found: " + id
                                )
                        );

        return employeeMapper.toDto(employee);
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> getEmployeesByWorkspace(
            UUID workspaceId
    ) {

        return employeeRepository
                .findAllByWorkspaceId(workspaceId)
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> getActiveEmployeesByWorkspace(
            UUID workspaceId
    ) {

        return employeeRepository
                .findAllByWorkspaceIdAndIsEnabledTrue(workspaceId)
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    public EmployeeDto updateEmployee(
            UUID id,
            UpdateEmployeeRequest request
    ) {

        EmployeeEntity employee =
                employeeRepository.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Employee not found: " + id
                                )
                        );

        if (request.email() != null &&
                !request.email().equalsIgnoreCase(
                        employee.getEmail()
                ) &&
                employeeRepository
                        .existsByWorkspaceIdAndEmailAndIdNot(
                                employee.getWorkspace().getId(),
                                request.email(),
                                id
                        )) {

            throw new IllegalArgumentException(
                    "Employee with email already exists"
            );
        }

        employeeMapper.updateEntity(
                employee,
                request
        );

        return employeeMapper.toDto(employee);
    }

    public void deleteEmployee(UUID id) {

        EmployeeEntity employee =
                employeeRepository.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Employee not found: " + id
                                )
                        );

        /*
         * Сейчас физически удаляем Employee.
         * User пока оставляем.
         *
         * Позже здесь лучше сделать soft delete/deactivate.
         */
        employeeRepository.delete(employee);
    }
}