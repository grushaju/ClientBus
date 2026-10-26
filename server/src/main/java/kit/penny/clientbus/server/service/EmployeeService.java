package kit.penny.clientbus.server.service;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.employee.*;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.exception.InvalidCurrentPasswordException;
import kit.penny.clientbus.server.mapper.EmployeeMapper;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EmployeeService {

    @org.springframework.beans.factory.annotation.Autowired
    private kit.penny.clientbus.server.notification.outbox.UiDomainEvents uiEvents;

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final IAttachmentStorage attachmentStorage;
    private final EmployeeWorkspaceRepository employeeWorkspaceRepository;

    public EmployeeService(
            EmployeeRepository employeeRepository,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            EmployeeMapper employeeMapper,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService,
            IAttachmentStorage attachmentStorage,
            EmployeeWorkspaceRepository employeeWorkspaceRepository
    ) {
        this.employeeRepository = employeeRepository;
        this.userRepository = userRepository;
        this.organizationRepository =
                organizationRepository;
        this.employeeMapper = employeeMapper;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
        this.attachmentStorage = attachmentStorage;
        this.employeeWorkspaceRepository = employeeWorkspaceRepository;
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

        changed(employee);

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

        if (!currentUserService.isSuperAdmin()
                && !currentUserService.isSupervisor(workspaceId)) {

            throw new AccessDeniedException(
                    "Workspace supervisor access required"
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

        changed(employee);

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

        changed(employee);

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

        changed(employee);

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

        changed(employee);

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

        changed(employee);

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

        changed(employee);
        employeeRepository.delete(employee);
        userRepository.delete(user);
    }

    public EmployeeDto uploadCurrentEmployeeAvatar(MultipartFile file) {
        EmployeeEntity employee = currentUserService.getCurrentEmployee();

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Avatar file is empty");
        }

        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Avatar content type is missing");
        }

        contentType = contentType.toLowerCase(Locale.ROOT);

        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> throw new IllegalArgumentException(
                    "Avatar must be a JPEG, PNG, WebP or GIF image"
            );
        };

        try {
            StoredAttachmentMetadata stored = attachmentStorage.store(
                    file.getInputStream(),
                    "employee-avatar-" + employee.getId() + extension,
                    file.getSize(),
                    contentType
            );

            String previousKey = employee.getAvatarUrl();
            employee.setAvatarUrl(stored.storageKey());

            EmployeeDto result = employeeMapper.toDto(
                    employeeRepository.saveAndFlush(employee)
            );

            if (previousKey != null && !previousKey.isBlank()
                    && !previousKey.equals(stored.storageKey())) {
                attachmentStorage.delete(previousKey);
            }

            changed(employee);

            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read employee avatar", e);
        }
    }

    public EmployeeDto clearCurrentEmployeeAvatar() {
        EmployeeEntity employee = currentUserService.getCurrentEmployee();
        String previousKey = employee.getAvatarUrl();

        employee.setAvatarUrl(null);
        EmployeeDto result = employeeMapper.toDto(
                employeeRepository.saveAndFlush(employee)
        );

        if (previousKey != null && !previousKey.isBlank()) {
            attachmentStorage.delete(previousKey);
        }

        changed(employee);

        return result;
    }

    @Transactional(readOnly = true)
    public EmployeeDto getEmployeeAvatar(UUID employeeId) {
        EmployeeEntity target = getEmployeeEntity(employeeId);

        UUID currentEmployeeId = currentUserService.getCurrentEmployeeId();

        // Собственный аватар доступен всегда авторизованному сотруднику.
        if (currentEmployeeId.equals(employeeId)) {
            return employeeMapper.toDto(target);
        }

        // SUPER_ADMIN может просматривать аватары сотрудников
        // только своей организации.
        if (currentUserService.isSuperAdmin()) {
            currentUserService.requireEmployeeInCurrentOrganization(employeeId);
            return employeeMapper.toDto(target);
        }

        // Для остальных пользователей нужен общий workspace.
        boolean sharesWorkspace = employeeWorkspaceRepository
                .findAllByEmployeeId(currentEmployeeId)
                .stream()
                .anyMatch(currentWorkspace ->
                        employeeWorkspaceRepository
                                .existsByEmployeeIdAndWorkspaceId(
                                        employeeId,
                                        currentWorkspace.getWorkspace().getId()
                                )
                );

        if (!sharesWorkspace) {
            throw new AccessDeniedException(
                    "Employee avatar is not accessible"
            );
        }

        return employeeMapper.toDto(target);
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

            throw new InvalidCurrentPasswordException();
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

    private void changed(EmployeeEntity employee) {
        if (uiEvents == null) return;
        uiEvents.identity(employee.getOrganization().getId(), employee.getId());
        uiEvents.organization(employee.getOrganization().getId(), "employees.invalidated");
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