package kit.penny.clientbus.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.fixture.TestDataFactory;
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
import kit.penny.clientbus.server.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmployeeWorkspaceApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeWorkspaceRepository employeeWorkspaceRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void employeeCannotAssignWorkspace() throws Exception {
        TestContext ctx = createContext(UserRole.EMPLOYEE);

        mockMvc.perform(post("/api/employees/{id}/workspaces", ctx.targetEmployee().getId())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workspaceId", ctx.workspace().getId(),
                                "isSupervisor", true
                        ))))
                .andExpect(status().isForbidden());

        assertFalse(employeeWorkspaceRepository.existsByEmployeeIdAndWorkspaceId(
                ctx.targetEmployee().getId(),
                ctx.workspace().getId()
        ));
    }

    @Test
    void employeeCannotChangeSupervisorFlag() throws Exception {
        TestContext ctx = createContext(UserRole.EMPLOYEE);

        EmployeeWorkspaceEntity link =
                employeeWorkspaceRepository.saveAndFlush(
                        new EmployeeWorkspaceEntity(
                                ctx.targetEmployee(),
                                ctx.workspace()
                        )
                );

        link.setSupervisor(false);
        employeeWorkspaceRepository.saveAndFlush(link);

        mockMvc.perform(patch(
                        "/api/employees/{id}/workspaces/{workspaceId}/supervisor",
                        ctx.targetEmployee().getId(),
                        ctx.workspace().getId()
                )
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("isSupervisor", true)
                        )))
                .andExpect(status().isForbidden());

        assertFalse(employeeWorkspaceRepository
                .findByEmployeeIdAndWorkspaceId(
                        ctx.targetEmployee().getId(),
                        ctx.workspace().getId()
                )
                .orElseThrow()
                .isSupervisor());
    }

    @Test
    void superAdminCanAssignWorkspaceAsSupervisor() throws Exception {
        TestContext ctx = createContext(UserRole.SUPER_ADMIN);

        mockMvc.perform(post("/api/employees/{id}/workspaces", ctx.targetEmployee().getId())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workspaceId", ctx.workspace().getId(),
                                "isSupervisor", true
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId")
                        .value(ctx.targetEmployee().getId().toString()))
                .andExpect(jsonPath("$.workspaceId")
                        .value(ctx.workspace().getId().toString()))
                .andExpect(jsonPath("$.isSupervisor").value(true));

        assertTrue(employeeWorkspaceRepository
                .findByEmployeeIdAndWorkspaceId(
                        ctx.targetEmployee().getId(),
                        ctx.workspace().getId()
                )
                .orElseThrow()
                .isSupervisor());
    }

    @Test
    void superAdminCanChangeSupervisorFlag() throws Exception {
        TestContext ctx = createContext(UserRole.SUPER_ADMIN);

        employeeWorkspaceRepository.saveAndFlush(
                new EmployeeWorkspaceEntity(
                        ctx.targetEmployee(),
                        ctx.workspace()
                )
        );

        mockMvc.perform(patch(
                        "/api/employees/{id}/workspaces/{workspaceId}/supervisor",
                        ctx.targetEmployee().getId(),
                        ctx.workspace().getId()
                )
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("isSupervisor", true)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId")
                        .value(ctx.targetEmployee().getId().toString()))
                .andExpect(jsonPath("$.workspaceId")
                        .value(ctx.workspace().getId().toString()))
                .andExpect(jsonPath("$.isSupervisor").value(true));

        assertTrue(employeeWorkspaceRepository
                .findByEmployeeIdAndWorkspaceId(
                        ctx.targetEmployee().getId(),
                        ctx.workspace().getId()
                )
                .orElseThrow()
                .isSupervisor());
    }

    private TestContext createContext(UserRole role) {
        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(organization)
                );

        UserEntity currentUser = userRepository.saveAndFlush(
                new UserEntity(
                        "api_test_" + UUID.randomUUID(),
                        "api_test_" + UUID.randomUUID() + "@example.com",
                        "$2a$10$test",
                        role
                )
        );

        EmployeeEntity currentEmployee =
                employeeRepository.saveAndFlush(
                        new EmployeeEntity(
                                organization,
                                currentUser,
                                "Current",
                                "User",
                                null
                        )
                );

        UserEntity targetUser = userRepository.saveAndFlush(
                TestDataFactory.user(
                        "target_" + UUID.randomUUID(),
                        "target_" + UUID.randomUUID() + "@example.com",
                        "$2a$10$test"
                )
        );

        EmployeeEntity targetEmployee =
                employeeRepository.saveAndFlush(
                        new EmployeeEntity(
                                organization,
                                targetUser,
                                "Target",
                                "Employee",
                                null
                        )
                );

        String token = jwtService.generateToken(currentUser);

        return new TestContext(
                currentEmployee,
                targetEmployee,
                workspace,
                token
        );
    }

    private record TestContext(
            EmployeeEntity currentEmployee,
            EmployeeEntity targetEmployee,
            WorkspaceEntity workspace,
            String token
    ) {
    }
}
