package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.employee.*;
import kit.penny.clientbus.server.mapper.EmployeeMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;

    public EmployeeService(
            EmployeeRepository employeeRepository,
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            EmployeeMapper employeeMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.employeeMapper = employeeMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Создание Employee + User
     */
    public EmployeeDto createEmployee(
            CreateEmployeeRequest request
    ) {

        if (userRepository.existsByUsername(
                request.username()
        )) {
            throw new IllegalArgumentException(
                    "Username already exists"
            );
        }

        if (userRepository.existsByEmail(
                request.email()
        )) {
            throw new IllegalArgumentException(
                    "Email already exists"
            );
        }

        WorkspaceEntity workspace =
                workspaceRepository.findById(
                        request.workspaceId()
                ).orElseThrow(() ->
                        new IllegalArgumentException(
                                "Workspace not found"
                        )
                );

        UserEntity user = new UserEntity(
                request.username(),
                request.email(),
                passwordEncoder.encode(
                        request.password()
                )
        );

        user = userRepository.save(user);

        EmployeeEntity employee =
                new EmployeeEntity(
                        workspace,
                        user,
                        request.firstName(),
                        request.lastName(),
                        request.phone()
                );

        employee = employeeRepository.saveAndFlush(employee);

        return employeeMapper.toDto(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeDto getEmployee(UUID id) {

        EmployeeEntity employee =
                getEmployeeEntity(id);

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
    public List<EmployeeDto> searchEmployees(
            UUID workspaceId,
            String query
    ) {
        if (query == null || query.isBlank()) {
            return getEmployeesByWorkspace(workspaceId);
        }

        return employeeRepository
                .searchEmployees(workspaceId, query.trim())
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    /**
     * Изменение бизнес-данных Employee.
     */
    public EmployeeDto updateEmployee(
            UUID employeeId,
            UpdateEmployeeRequest request
    ) {

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        if (request.firstName() != null) {
            employee.setFirstName(
                    request.firstName()
            );
        }

        if (request.lastName() != null) {
            employee.setLastName(
                    request.lastName()
            );
        }

        if (request.phone() != null) {
            employee.setPhone(
                    request.phone()
            );
        }

        return employeeMapper.toDto(employee);
    }

    /**
     * Изменение username/email.
     */
    public EmployeeDto updateCredentials(
            UUID employeeId,
            UpdateEmployeeCredentialsRequest request
    ) {

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        UserEntity user =
                employee.getUser();

        if (request.username() != null &&
                !request.username().equals(user.getUsername())) {

            if (userRepository.existsByUsername(
                    request.username()
            )) {
                throw new IllegalArgumentException(
                        "Username already exists"
                );
            }

            user.setUsername(request.username());
        }

        if (request.email() != null &&
                !request.email().equals(user.getEmail())) {

            if (userRepository.existsByEmail(
                    request.email()
            )) {
                throw new IllegalArgumentException(
                        "Email already exists"
                );
            }

            user.setEmail(request.email());
        }

        return employeeMapper.toDto(employee);
    }

    /**
     * Изменение пароля.
     */
    public void changePassword(
            UUID employeeId,
            ChangeEmployeePasswordRequest request
    ) {

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        UserEntity user =
                employee.getUser();

        if (!passwordEncoder.matches(
                request.currentPassword(),
                user.getPasswordHash()
        )) {
            throw new IllegalArgumentException(
                    "Invalid current password"
            );
        }

        user.setPasswordHash(
                passwordEncoder.encode(
                        request.newPassword()
                )
        );
    }

    /**
     * Включение / отключение пользователя.
     */
    public EmployeeDto setEnabled(
            UUID employeeId,
            SetEmployeeEnabledRequest request
    ) {

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        employee.getUser().setEnabled(
                request.enabled()
        );

        return employeeMapper.toDto(employee);
    }

    /**
     * Удаление Employee вместе с User.
     */
    public void deleteEmployee(UUID employeeId) {

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        UserEntity user =
                employee.getUser();

        /*
         * Сначала Employee,
         * поскольку на User есть FK.
         */
        employeeRepository.delete(employee);

        /*
         * Затем User.
         */
        userRepository.delete(user);
    }

    private EmployeeEntity getEmployeeEntity(
            UUID employeeId
    ) {

        return employeeRepository.findById(
                employeeId
        ).orElseThrow(() ->
                new IllegalArgumentException(
                        "Employee not found"
                )
        );
    }
}