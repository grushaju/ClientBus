package kit.penny.clientbus.server.security.service;

import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CurrentUserService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeWorkspaceRepository employeeWorkspaceRepository;
    private final WorkspaceRepository workspaceRepository;

    public CurrentUserService(
            EmployeeRepository employeeRepository,
            EmployeeWorkspaceRepository employeeWorkspaceRepository,
            WorkspaceRepository workspaceRepository
    ) {
        this.employeeRepository = employeeRepository;
        this.employeeWorkspaceRepository =
                employeeWorkspaceRepository;
        this.workspaceRepository = workspaceRepository;
    }

    public UserPrincipal getCurrentUser() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null ||
                !authentication.isAuthenticated()) {

            throw new AuthenticationCredentialsNotFoundException(
                    "User is not authenticated"
            );
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof UserPrincipal userPrincipal)) {

            throw new AuthenticationCredentialsNotFoundException(
                    "Invalid authentication principal"
            );
        }

        return userPrincipal;
    }

    public UUID getCurrentUserId() {
        return getCurrentUser().getId();
    }

    public String getCurrentUsername() {
        return getCurrentUser().getUsername();
    }

    public boolean isSuperAdmin() {
        return getCurrentUser().isSuperAdmin();
    }

    public boolean isEmployee() {
        return getCurrentUser().isEmployee();
    }

    public EmployeeEntity getCurrentEmployee() {

        UUID userId = getCurrentUserId();

        return employeeRepository
                .findByUserId(userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Employee profile not found for user: "
                                        + userId
                        )
                );
    }

    public UUID getCurrentEmployeeId() {
        return getCurrentEmployee().getId();
    }

    public UUID getCurrentOrganizationId() {
        return getCurrentEmployee()
                .getOrganization()
                .getId();
    }

    /**
     * Требует роль SUPER_ADMIN.
     */
    public void requireSuperAdmin() {

        if (!isSuperAdmin()) {
            throw new AccessDeniedException(
                    "SUPER_ADMIN role required"
            );
        }
    }

    /**
     * Проверяет, что employeeId принадлежит
     * текущему пользователю.
     *
     * Используется для self-service операций.
     */
    public void requireSelf(UUID employeeId) {

        if (!getCurrentEmployeeId().equals(employeeId)) {

            throw new AccessDeniedException(
                    "Access denied: operation is allowed only for current employee"
            );
        }
    }

    /**
     * Требует SUPER_ADMIN и принадлежность
     * указанной Organization текущему SUPER_ADMIN.
     */
    public void requireSuperAdminOrganization(
            UUID organizationId
    ) {

        requireSuperAdmin();

        if (!getCurrentOrganizationId().equals(organizationId)) {

            throw new AccessDeniedException(
                    "Access denied for organization: "
                            + organizationId
            );
        }
    }

    /**
     * Проверяет принадлежность employee
     * текущей Organization.
     */
    public void requireEmployeeInCurrentOrganization(
            UUID employeeId
    ) {

        if (!employeeRepository.existsByIdAndOrganizationId(
                employeeId,
                getCurrentOrganizationId()
        )) {

            throw new AccessDeniedException(
                    "Employee does not belong to current organization"
            );
        }
    }

    /**
     * Проверяет, может ли текущий пользователь
     * работать с Workspace.
     */
    public boolean hasWorkspaceAccess(
            UUID workspaceId
    ) {

        EmployeeEntity employee =
                getCurrentEmployee();

        UUID organizationId =
                employee.getOrganization().getId();

        if (isSuperAdmin()) {

            return workspaceRepository
                    .existsByIdAndOrganizationId(
                            workspaceId,
                            organizationId
                    );
        }

        return employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employee.getId(),
                        workspaceId
                );
    }

    /**
     * Требует доступ текущего пользователя
     * к Workspace.
     */
    public UUID requireWorkspaceAccess(
            UUID workspaceId
    ) {

        if (!hasWorkspaceAccess(workspaceId)) {

            throw new AccessDeniedException(
                    "Access denied for workspace: "
                            + workspaceId
            );
        }

        return workspaceId;
    }

    public boolean isAuthenticated() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal()
                instanceof UserPrincipal;
    }
}