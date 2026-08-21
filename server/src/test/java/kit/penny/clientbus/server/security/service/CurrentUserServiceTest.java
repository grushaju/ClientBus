package kit.penny.clientbus.server.security.service;

import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeWorkspaceRepository;
import kit.penny.clientbus.server.persistence.repository.WorkspaceRepository;
import kit.penny.clientbus.server.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CurrentUserServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    private CurrentUserService currentUserService;

    private OrganizationEntity organizationA;
    private OrganizationEntity organizationB;

    private EmployeeEntity superAdminA;
    private EmployeeEntity employeeA1;

    private WorkspaceEntity workspaceA1;
    private WorkspaceEntity workspaceA2;
    private WorkspaceEntity workspaceB1;

    @BeforeEach
    void setUp() {

        currentUserService = new CurrentUserService(
                employeeRepository,
                employeeWorkspaceRepository,
                workspaceRepository
        );

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
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================
    // EMPLOYEE OBJECT-LEVEL ACL
    // =========================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource("employeeWorkspaceAccessCases")
    void employeeA1_workspaceAccess_isCheckedByEmployeeWorkspaceAcl(
            String description,
            UUID workspaceId,
            boolean aclResult,
            boolean expected
    ) {

        authenticate(employeeA1);

        when(employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employeeA1.getId(),
                        workspaceId
                ))
                .thenReturn(aclResult);

        boolean actual =
                currentUserService.hasWorkspaceAccess(
                        workspaceId
                );

        assertEquals(expected, actual);

        verify(employeeWorkspaceRepository)
                .existsByEmployeeIdAndWorkspaceId(
                        employeeA1.getId(),
                        workspaceId
                );

        verifyNoInteractions(workspaceRepository);
    }

    Stream<Arguments> employeeWorkspaceAccessCases() {

        return Stream.of(
                Arguments.of(
                        "EMPLOYEE A1 -> Workspace A1",
                        workspaceA1.getId(),
                        true,
                        true
                ),
                Arguments.of(
                        "EMPLOYEE A1 -> Workspace A2",
                        workspaceA2.getId(),
                        false,
                        false
                ),
                Arguments.of(
                        "EMPLOYEE A1 -> Workspace B1",
                        workspaceB1.getId(),
                        false,
                        false
                )
        );
    }

    // =========================================================
    // SUPER ADMIN OBJECT-LEVEL ACL
    // =========================================================

    @ParameterizedTest(name = "{0}")
    @MethodSource("superAdminWorkspaceAccessCases")
    void superAdminA_workspaceAccess_isRestrictedByOrganization(
            String description,
            UUID workspaceId,
            boolean belongsToOrganization,
            boolean expected
    ) {

        authenticate(superAdminA);

        when(workspaceRepository
                .existsByIdAndOrganizationId(
                        workspaceId,
                        organizationA.getId()
                ))
                .thenReturn(belongsToOrganization);

        boolean actual =
                currentUserService.hasWorkspaceAccess(
                        workspaceId
                );

        assertEquals(expected, actual);

        verify(workspaceRepository)
                .existsByIdAndOrganizationId(
                        workspaceId,
                        organizationA.getId()
                );

        verifyNoInteractions(employeeWorkspaceRepository);
    }

    Stream<Arguments> superAdminWorkspaceAccessCases() {

        return Stream.of(
                Arguments.of(
                        "SUPER_ADMIN A -> Workspace A1",
                        workspaceA1.getId(),
                        true,
                        true
                ),
                Arguments.of(
                        "SUPER_ADMIN A -> Workspace A2",
                        workspaceA2.getId(),
                        true,
                        true
                ),
                Arguments.of(
                        "SUPER_ADMIN A -> Workspace B1",
                        workspaceB1.getId(),
                        false,
                        false
                )
        );
    }

    // =========================================================
    // requireWorkspaceAccess()
    // =========================================================

    @Test
    void employeeA1_requireWorkspaceAccess_allowsAssignedWorkspace() {

        authenticate(employeeA1);

        when(employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employeeA1.getId(),
                        workspaceA1.getId()
                ))
                .thenReturn(true);

        assertDoesNotThrow(() ->
                currentUserService.requireWorkspaceAccess(
                        workspaceA1.getId()
                )
        );
    }

    @Test
    void employeeA1_requireWorkspaceAccess_deniesUnassignedWorkspace() {

        authenticate(employeeA1);

        when(employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employeeA1.getId(),
                        workspaceA2.getId()
                ))
                .thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> currentUserService.requireWorkspaceAccess(
                        workspaceA2.getId()
                )
        );
    }

    @Test
    void employeeA1_requireWorkspaceAccess_deniesOtherOrganization() {

        authenticate(employeeA1);

        when(employeeWorkspaceRepository
                .existsByEmployeeIdAndWorkspaceId(
                        employeeA1.getId(),
                        workspaceB1.getId()
                ))
                .thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> currentUserService.requireWorkspaceAccess(
                        workspaceB1.getId()
                )
        );
    }

    @Test
    void superAdminA_requireWorkspaceAccess_allowsOwnOrganizationWorkspace() {

        authenticate(superAdminA);

        when(workspaceRepository
                .existsByIdAndOrganizationId(
                        workspaceA2.getId(),
                        organizationA.getId()
                ))
                .thenReturn(true);

        assertDoesNotThrow(() ->
                currentUserService.requireWorkspaceAccess(
                        workspaceA2.getId()
                )
        );
    }

    @Test
    void superAdminA_requireWorkspaceAccess_deniesOtherOrganization() {

        authenticate(superAdminA);

        when(workspaceRepository
                .existsByIdAndOrganizationId(
                        workspaceB1.getId(),
                        organizationA.getId()
                ))
                .thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> currentUserService.requireWorkspaceAccess(
                        workspaceB1.getId()
                )
        );
    }

    // =========================================================
    // CRITICAL OBJECT-LEVEL ACL TEST
    // =========================================================

    @Test
    void superAdminA_cannotAccessWorkspaceOfOrganizationB() {

        authenticate(superAdminA);

        when(workspaceRepository
                .existsByIdAndOrganizationId(
                        workspaceB1.getId(),
                        organizationA.getId()
                ))
                .thenReturn(false);

        assertFalse(
                currentUserService.hasWorkspaceAccess(
                        workspaceB1.getId()
                )
        );

        verify(workspaceRepository)
                .existsByIdAndOrganizationId(
                        workspaceB1.getId(),
                        organizationA.getId()
                );

        verifyNoInteractions(employeeWorkspaceRepository);
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private void authenticate(EmployeeEntity employee) {

        UserEntity user = employee.getUser();

        UserPrincipal principal =
                new UserPrincipal(user);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);

        when(employeeRepository.findByUserId(
                user.getId()
        )).thenReturn(Optional.of(employee));
    }

    private OrganizationEntity createOrganization(
            String name
    ) {

        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setId(UUID.randomUUID());
        organization.setName(name);

        return organization;
    }

    private EmployeeEntity createEmployee(
            String username,
            UserRole role,
            OrganizationEntity organization
    ) {

        UserEntity user =
                new UserEntity();

        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash("password");
        user.setRole(role);
        user.setEnabled(true);

        EmployeeEntity employee =
                new EmployeeEntity();

        employee.setId(UUID.randomUUID());
        employee.setUser(user);
        employee.setOrganization(organization);

        return employee;
    }

    private WorkspaceEntity createWorkspace(
            String name,
            OrganizationEntity organization
    ) {

        WorkspaceEntity workspace =
                new WorkspaceEntity();

        workspace.setId(UUID.randomUUID());
        workspace.setName(name);
        workspace.setOrganization(organization);

        return workspace;
    }
}