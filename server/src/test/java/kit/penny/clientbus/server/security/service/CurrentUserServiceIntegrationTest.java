package kit.penny.clientbus.server.security.service;

import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import kit.penny.clientbus.server.security.UserPrincipal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class CurrentUserServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CurrentUserService currentUserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;

    private OrganizationEntity organizationA;
    private OrganizationEntity organizationB;

    private EmployeeEntity superAdminA;
    private EmployeeEntity employeeA1;
    private EmployeeEntity employeeB1;

    private WorkspaceEntity workspaceA1;
    private WorkspaceEntity workspaceA2;
    private WorkspaceEntity workspaceB1;

    @BeforeEach
    void setUp() {

        employeeWorkspaceRepository.deleteAll();
        employeeRepository.deleteAll();
        userRepository.deleteAll();
        workspaceRepository.deleteAll();
        organizationRepository.deleteAll();

        organizationA = createOrganization(
                "Organization A"
        );

        organizationB = createOrganization(
                "Organization B"
        );

        superAdminA = createEmployee(
                "super-admin-a",
                UserRole.SUPER_ADMIN,
                organizationA
        );

        employeeA1 = createEmployee(
                "employee-a1",
                UserRole.EMPLOYEE,
                organizationA
        );

        employeeB1 = createEmployee(
                "employee-b1",
                UserRole.EMPLOYEE,
                organizationB
        );

        workspaceA1 = createWorkspace(
                "Workspace A1",
                organizationA
        );

        workspaceA2 = createWorkspace(
                "Workspace A2",
                organizationA
        );

        workspaceB1 = createWorkspace(
                "Workspace B1",
                organizationB
        );

        /*
         * EMPLOYEE A1 имеет доступ только к A1.
         *
         * A2 намеренно НЕ назначаем.
         */
        assignEmployeeToWorkspace(
                employeeA1,
                workspaceA1
        );

        /*
         * B1 принадлежит Organization B.
         *
         * Поэтому A1 не должен получить к нему доступ
         * независимо от своей роли.
         */
        assignEmployeeToWorkspace(
                employeeB1,
                workspaceB1
        );

        clearSecurityContext();
    }

    @AfterEach
    void tearDown() {
        clearSecurityContext();
    }

    // =========================================================
    // EMPLOYEE A1
    // =========================================================

    @Test
    void employeeA1_canAccessAssignedWorkspaceA1() {

        authenticate(employeeA1);

        assertDoesNotThrow(() ->
                currentUserService.requireWorkspaceAccess(
                        workspaceA1.getId()
                )
        );
    }

    @Test
    void employeeA1_cannotAccessUnassignedWorkspaceA2() {

        authenticate(employeeA1);

        assertThrows(
                AccessDeniedException.class,
                () ->
                        currentUserService.requireWorkspaceAccess(
                                workspaceA2.getId()
                        )
        );
    }

    @Test
    void employeeA1_cannotAccessWorkspaceB1() {

        authenticate(employeeA1);

        assertThrows(
                AccessDeniedException.class,
                () ->
                        currentUserService.requireWorkspaceAccess(
                                workspaceB1.getId()
                        )
        );
    }

    // =========================================================
    // SUPER ADMIN A
    // =========================================================

    @Test
    void superAdminA_canAccessWorkspaceA1() {

        authenticate(superAdminA);

        assertDoesNotThrow(() ->
                currentUserService.requireWorkspaceAccess(
                        workspaceA1.getId()
                )
        );
    }

    @Test
    void superAdminA_canAccessWorkspaceA2() {

        authenticate(superAdminA);

        /*
         * A2 НЕ назначен через EmployeeWorkspace.
         *
         * Это принципиальная проверка:
         * SUPER_ADMIN не нуждается в EmployeeWorkspace.
         */
        assertDoesNotThrow(() ->
                currentUserService.requireWorkspaceAccess(
                        workspaceA2.getId()
                )
        );
    }

    @Test
    void superAdminA_cannotAccessWorkspaceB1() {

        authenticate(superAdminA);

        /*
         * Даже SUPER_ADMIN не получает глобальный доступ.
         *
         * Organization A != Organization B.
         */
        assertThrows(
                AccessDeniedException.class,
                () ->
                        currentUserService.requireWorkspaceAccess(
                                workspaceB1.getId()
                        )
        );
    }

    // =========================================================
    // hasWorkspaceAccess()
    // =========================================================

    @Test
    void employeeA1_hasWorkspaceAccess_onlyForAssignedWorkspace() {

        authenticate(employeeA1);

        assert currentUserService.hasWorkspaceAccess(
                workspaceA1.getId()
        );

        assert !currentUserService.hasWorkspaceAccess(
                workspaceA2.getId()
        );

        assert !currentUserService.hasWorkspaceAccess(
                workspaceB1.getId()
        );
    }

    @Test
    void superAdminA_hasWorkspaceAccess_forEntireOrganization() {

        authenticate(superAdminA);

        assert currentUserService.hasWorkspaceAccess(
                workspaceA1.getId()
        );

        assert currentUserService.hasWorkspaceAccess(
                workspaceA2.getId()
        );

        assert !currentUserService.hasWorkspaceAccess(
                workspaceB1.getId()
        );
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private void authenticate(
            EmployeeEntity employee
    ) {

        UserPrincipal principal =
                new UserPrincipal(
                        employee.getUser()
                );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(
                        authentication
                );
    }

    private void clearSecurityContext() {

        SecurityContextHolder
                .clearContext();
    }

    private OrganizationEntity createOrganization(
            String name
    ) {

        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setName(name);

        return organizationRepository.save(
                organization
        );
    }

    private EmployeeEntity createEmployee(
            String username,
            UserRole role,
            OrganizationEntity organization
    ) {

        UserEntity user =
                new UserEntity();

        user.setUsername(username);
        user.setPasswordHash("password");
        user.setEmail(username + "@test.local");
        user.setRole(role);
        user.setEnabled(true);

        user = userRepository.save(user);

        EmployeeEntity employee =
                new EmployeeEntity();

        employee.setUser(user);
        employee.setOrganization(organization);

        return employeeRepository.save(
                employee
        );
    }

    private WorkspaceEntity createWorkspace(
            String name,
            OrganizationEntity organization
    ) {

        WorkspaceEntity workspace =
                new WorkspaceEntity();

        workspace.setName(name);
        workspace.setOrganization(organization);

        return workspaceRepository.save(
                workspace
        );
    }

    private void assignEmployeeToWorkspace(
            EmployeeEntity employee,
            WorkspaceEntity workspace
    ) {

        EmployeeWorkspaceEntity assignment =
                new EmployeeWorkspaceEntity();

        assignment.setEmployee(employee);
        assignment.setWorkspace(workspace);

        employeeWorkspaceRepository.save(
                assignment
        );
    }
}