package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.employee.*;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.mapper.EmployeeMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
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
    private final OrganizationRepository organizationRepository;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;

    public EmployeeService(
            EmployeeRepository employeeRepository,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            EmployeeMapper employeeMapper,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService
    ) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.organizationRepository =
                organizationRepository;
        this.employeeMapper = employeeMapper;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
    }

    /**
     * ADMIN
     */
    public EmployeeDto createEmployee(
            CreateEmployeeRequest request
    ) {

        currentUserService.requireSuperAdminOrganization(
                request.organizationId()
        );

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

        OrganizationEntity organization =
                organizationRepository.findById(
                        request.organizationId()
                ).orElseThrow(() ->
                        new EntityNotFoundException(
                                "Organization not found"
                        )
                );

        UserEntity user = new UserEntity(
                request.username(),
                request.email(),
                passwordEncoder.encode(
                        request.password()
                ),
                UserRole.EMPLOYEE
        );

        user = userRepository.save(user);

        EmployeeEntity employee =
                new EmployeeEntity(
                        organization,
                        user,
                        request.firstName(),
                        request.lastName(),
                        request.phone()
                );

        employee =
                employeeRepository.saveAndFlush(
                        employee
                );

        return employeeMapper.toDto(employee);
    }

    /**
     * ADMIN:
     * получить другого сотрудника.
     */
    @Transactional(readOnly = true)
    public EmployeeDto getEmployee(UUID employeeId) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        currentUserService.requireEmployeeInCurrentOrganization(
                employeeId
        );

        return employeeMapper.toDto(employee);
    }

    /**
     * SELF.
     */
    @Transactional(readOnly = true)
    public EmployeeDto getCurrentEmployee() {

        return employeeMapper.toDto(
                currentUserService.getCurrentEmployee()
        );
    }

    /**
     * ADMIN.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> getEmployeesByOrganization(
            UUID organizationId
    ) {

        currentUserService.requireSuperAdminOrganization(
                organizationId
        );

        return employeeRepository
                .findAllByOrganizationId(organizationId)
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    /**
     * ADMIN.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> getEmployeesByWorkspace(
            UUID workspaceId
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity currentEmployee =
                currentUserService.getCurrentEmployee();

        if (!workspaceBelongsToCurrentOrganization(
                workspaceId,
                currentEmployee.getOrganization().getId()
        )) {

            throw new AccessDeniedException(
                    "Workspace does not belong to current organization"
            );
        }

        return employeeRepository
                .findAllByWorkspaceId(workspaceId)
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    /**
     * ADMIN.
     */
    @Transactional(readOnly = true)
    public List<EmployeeDto> searchEmployees(
            UUID workspaceId,
            String query
    ) {

        currentUserService.requireSuperAdmin();

        currentUserService.requireWorkspaceAccess(
                workspaceId
        );

        if (query == null || query.isBlank()) {
            return getEmployeesByWorkspace(workspaceId);
        }

        return employeeRepository
                .searchEmployees(
                        workspaceId,
                        query.trim()
                )
                .stream()
                .map(employeeMapper::toDto)
                .toList();
    }

    /**
     * ADMIN:
     * изменить произвольного Employee
     * своей Organization.
     */
    public EmployeeDto updateEmployee(
            UUID employeeId,
            UpdateEmployeeRequest request
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        currentUserService.requireEmployeeInCurrentOrganization(
                employeeId
        );

        updateProfile(employee, request);

        return employeeMapper.toDto(employee);
    }

    /**
     * SELF:
     * изменить собственные персональные данные.
     */
    public EmployeeDto updateCurrentEmployee(
            UpdateEmployeeRequest request
    ) {

        EmployeeEntity employee =
                currentUserService.getCurrentEmployee();

        updateProfile(employee, request);

        return employeeMapper.toDto(employee);
    }

    /**
     * ADMIN:
     * изменить credentials другого Employee
     * своей Organization.
     */
    public EmployeeDto updateCredentials(
            UUID employeeId,
            UpdateEmployeeCredentialsRequest request
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        currentUserService.requireEmployeeInCurrentOrganization(
                employeeId
        );

        updateUserCredentials(
                employee.getUser(),
                request
        );

        return employeeMapper.toDto(employee);
    }

    /**
     * SELF:
     * изменить собственные credentials.
     */
    public EmployeeDto updateCurrentCredentials(
            UpdateEmployeeCredentialsRequest request
    ) {

        EmployeeEntity employee =
                currentUserService.getCurrentEmployee();

        updateUserCredentials(
                employee.getUser(),
                request
        );

        return employeeMapper.toDto(employee);
    }

    /**
     * SELF ONLY.
     *
     * Изменение пароля требует текущий пароль.
     */
    public void changeCurrentPassword(
            ChangeEmployeePasswordRequest request
    ) {

        changePasswordForEmployee(
                currentUserService.getCurrentEmployee(),
                request
        );
    }

    /**
     * Оставляем этот метод для совместимости,
     * но он теперь SELF ONLY.
     */
    public void changePassword(
            UUID employeeId,
            ChangeEmployeePasswordRequest request
    ) {

        currentUserService.requireSelf(employeeId);

        changePasswordForEmployee(
                currentUserService.getCurrentEmployee(),
                request
        );
    }

    /**
     * ADMIN ONLY.
     */
    public EmployeeDto setEnabled(
            UUID employeeId,
            SetEmployeeEnabledRequest request
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        currentUserService.requireEmployeeInCurrentOrganization(
                employeeId
        );

        employee.getUser().setEnabled(
                request.enabled()
        );

        return employeeMapper.toDto(employee);
    }

    /**
     * SUPER_ADMIN ONLY.
     *
     * Сбрасывает пароль другого Employee.
     * Текущий пароль сотрудника не требуется.
     */
    public void resetEmployeePassword(
            UUID employeeId,
            ResetEmployeePasswordRequest request
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        currentUserService.requireEmployeeInCurrentOrganization(
                employeeId
        );

        employee.getUser().setPasswordHash(
                passwordEncoder.encode(
                        request.newPassword()
                )
        );
    }

    public void deleteEmployee(
            UUID employeeId
    ) {

        currentUserService.requireSuperAdmin();

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        currentUserService.requireEmployeeInCurrentOrganization(
                employeeId
        );

        /*
         * Дополнительное правило безопасности:
         * SUPER_ADMIN не должен удалить самого себя.
         */
        if (currentUserService.getCurrentEmployeeId()
                .equals(employeeId)) {

            throw new AccessDeniedException(
                    "SUPER_ADMIN cannot delete itself"
            );
        }

        UserEntity user =
                employee.getUser();

        employeeRepository.delete(employee);
        userRepository.delete(user);
    }

    private void updateProfile(
            EmployeeEntity employee,
            UpdateEmployeeRequest request
    ) {

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
    }

    private void updateUserCredentials(
            UserEntity user,
            UpdateEmployeeCredentialsRequest request
    ) {

        if (request.username() != null &&
                !request.username().equals(
                        user.getUsername()
                )) {

            if (userRepository.existsByUsername(
                    request.username()
            )) {

                throw new IllegalArgumentException(
                        "Username already exists"
                );
            }

            user.setUsername(
                    request.username()
            );
        }

        if (request.email() != null &&
                !request.email().equals(
                        user.getEmail()
                )) {

            if (userRepository.existsByEmail(
                    request.email()
            )) {

                throw new IllegalArgumentException(
                        "Email already exists"
                );
            }

            user.setEmail(
                    request.email()
            );
        }
    }

    private void changePasswordForEmployee(
            EmployeeEntity employee,
            ChangeEmployeePasswordRequest request
    ) {

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

    private boolean workspaceBelongsToCurrentOrganization(
            UUID workspaceId,
            UUID organizationId
    ) {

        return currentUserService
                .hasWorkspaceAccess(workspaceId)
                && currentUserService
                .getCurrentEmployee()
                .getOrganization()
                .getId()
                .equals(organizationId);
    }

    private EmployeeEntity getEmployeeEntity(
            UUID employeeId
    ) {

        return employeeRepository.findById(
                employeeId
        ).orElseThrow(() ->
                new EntityNotFoundException(
                        "Employee not found: "
                                + employeeId
                )
        );
    }
}