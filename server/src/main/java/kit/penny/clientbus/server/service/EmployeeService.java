package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.employee.*;
import kit.penny.clientbus.server.mapper.EmployeeMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.UserRole;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
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

    public EmployeeService(
            EmployeeRepository employeeRepository,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            EmployeeMapper employeeMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.organizationRepository =
                organizationRepository;
        this.employeeMapper = employeeMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Создание Employee + User.
     *
     * Workspace здесь НЕ назначается.
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

    @Transactional(readOnly = true)
    public EmployeeDto getEmployee(UUID id) {

        return employeeMapper.toDto(
                getEmployeeEntity(id)
        );
    }

    @Transactional(readOnly = true)
    public List<EmployeeDto> getEmployeesByOrganization(
            UUID organizationId
    ) {

        return employeeRepository
                .findAllByOrganizationId(organizationId)
                .stream()
                .map(employeeMapper::toDto)
                .toList();
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
            return getEmployeesByWorkspace(
                    workspaceId
            );
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

    public EmployeeDto updateCredentials(
            UUID employeeId,
            UpdateEmployeeCredentialsRequest request
    ) {

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        UserEntity user =
                employee.getUser();

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

        return employeeMapper.toDto(employee);
    }

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

    public void deleteEmployee(
            UUID employeeId
    ) {

        EmployeeEntity employee =
                getEmployeeEntity(employeeId);

        UserEntity user =
                employee.getUser();

        employeeRepository.delete(employee);

        userRepository.delete(user);
    }

    private EmployeeEntity getEmployeeEntity(
            UUID employeeId
    ) {

        return employeeRepository.findById(
                employeeId
        ).orElseThrow(() ->
                new EntityNotFoundException(
                        "Employee not found"
                )
        );
    }
}