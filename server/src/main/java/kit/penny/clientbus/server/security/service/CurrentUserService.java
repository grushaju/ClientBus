package kit.penny.clientbus.server.security.service;

import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CurrentUserService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeWorkspaceRepository
            employeeWorkspaceRepository;
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

        Object principal =
                authentication.getPrincipal();

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

    /**
     * Проверяет, может ли текущий пользователь работать
     * с указанным Workspace.
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
     * Проверяет доступ и возвращает workspaceId.
     */
    public UUID requireWorkspaceAccess(
            UUID workspaceId
    ) {

        if (!hasWorkspaceAccess(workspaceId)) {

            throw new org.springframework.security.access.AccessDeniedException(
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