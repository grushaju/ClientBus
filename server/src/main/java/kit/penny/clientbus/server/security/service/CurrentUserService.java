package kit.penny.clientbus.server.security.service;

import kit.penny.clientbus.server.security.UserPrincipal;

import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CurrentUserService {

    private final EmployeeRepository employeeRepository;

    public CurrentUserService(
            EmployeeRepository employeeRepository
    ) {
        this.employeeRepository = employeeRepository;
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

    public String getCurrentLogin() {
        return getCurrentUser().getUsername();
    }

    public EmployeeEntity getCurrentEmployee() {

        UUID userId =
                getCurrentUserId();

        return employeeRepository
                .findByUserId(userId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Employee profile not found for user: "
                                        + userId
                        )
                );
    }

    public UUID getCurrentWorkspaceId() {

        return getCurrentEmployee()
                .getWorkspace()
                .getId();
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