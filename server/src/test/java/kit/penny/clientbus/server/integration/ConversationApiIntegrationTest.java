package kit.penny.clientbus.server.integration;

import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ChannelAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ChannelEntity;
import kit.penny.clientbus.server.persistence.entity.ClientAccountEntity;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.EmployeeWorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.repository.ChannelAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ChannelRepository;
import kit.penny.clientbus.server.persistence.repository.ClientAccountRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationRepository;
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

import java.util.UUID;

import kit.penny.clientbus.server.persistence.entity.ConversationGroupEntity;
import kit.penny.clientbus.server.persistence.entity.ConversationGroupMembershipEntity;
import kit.penny.clientbus.server.persistence.repository.ConversationGroupRepository;
import kit.penny.clientbus.server.persistence.repository.ConversationGroupMembershipRepository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConversationApiIntegrationTest
        extends AbstractIntegrationTest {

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

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelAccountRepository channelAccountRepository;

    @Autowired
    private ClientAccountRepository clientAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationGroupRepository groupRepository;

    @Autowired
    private ConversationGroupMembershipRepository membershipRepository;

    @Autowired
    private kit.penny.clientbus.server.persistence.repository.ClientRepository clientRepository;

    @Test
    void getConversation_returnsConversationFromDatabase() throws Exception {

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(organization)
                );

        UserEntity user =
                userRepository.saveAndFlush(
                        TestDataFactory.user(
                                "integration_" + UUID.randomUUID(),
                                "integration_" + UUID.randomUUID() + "@example.com",
                                "$2a$10$test"
                        )
                );

        user.setRole(UserRole.EMPLOYEE);

        EmployeeEntity employee =
                employeeRepository.saveAndFlush(
                        new EmployeeEntity(
                                organization,
                                user,
                                "Integration",
                                "Employee",
                                "+79990000010"
                        )
                );

        employeeWorkspaceRepository.saveAndFlush(
                new EmployeeWorkspaceEntity(
                        employee,
                        workspace
                )
        );

        ChannelEntity channel =
                channelRepository.saveAndFlush(
                        TestDataFactory.channel(workspace)
                );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(channel)
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                channel.getType(),
                                "client-" + UUID.randomUUID()
                        )
                );

        ConversationEntity conversation =
                conversationRepository.saveAndFlush(
                        TestDataFactory.conversation(
                                workspace,
                                channelAccount,
                                clientAccount
                        )
                );

        String token =
                jwtService.generateToken(user);

        mockMvc.perform(
                        get("/api/conversations/{id}", conversation.getId())
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(conversation.getId().toString())
                );
    }

    @Test
    void groupApiHidesConversationAfterReassignmentButPreservesMembership()
            throws Exception {

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(
                        TestDataFactory.organization()
                );

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(
                        TestDataFactory.workspace(organization)
                );

        UserEntity ownerUser = TestDataFactory.user(
                "group_owner",
                "group_owner@example.com",
                "hash"
        );

        ownerUser = userRepository.saveAndFlush(ownerUser);

        EmployeeEntity owner = employeeRepository.saveAndFlush(
                new EmployeeEntity(
                        organization,
                        ownerUser,
                        "Group",
                        "Owner",
                        null
                )
        );

        UserEntity otherUser = TestDataFactory.user(
                "group_other",
                "group_other@example.com",
                "hash"
        );

        otherUser = userRepository.saveAndFlush(otherUser);

        EmployeeEntity otherEmployee = employeeRepository.saveAndFlush(
                new EmployeeEntity(
                        organization,
                        otherUser,
                        "Other",
                        "Employee",
                        null
                )
        );

        employeeWorkspaceRepository.saveAndFlush(
                new EmployeeWorkspaceEntity(owner, workspace)
        );

        employeeWorkspaceRepository.saveAndFlush(
                new EmployeeWorkspaceEntity(otherEmployee, workspace)
        );

        ChannelEntity channel = channelRepository.saveAndFlush(
                TestDataFactory.channel(workspace)
        );

        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(
                        TestDataFactory.channelAccount(channel)
                );

        ClientAccountEntity clientAccount =
                clientAccountRepository.saveAndFlush(
                        TestDataFactory.clientAccount(
                                null,
                                channel.getType(),
                                "client-" + UUID.randomUUID()
                        )
                );

        ConversationEntity conversation =
                TestDataFactory.conversation(
                        workspace,
                        channelAccount,
                        clientAccount
                );

        conversation.setAssignedEmployee(owner);

        conversation = conversationRepository.saveAndFlush(conversation);

        ConversationGroupEntity group = new ConversationGroupEntity();
        group.setWorkspace(workspace);
        group.setOwnerEmployee(owner);
        group.setName("Follow up");

        group = groupRepository.saveAndFlush(group);

        ConversationGroupMembershipEntity membership =
                new ConversationGroupMembershipEntity();

        membership.setGroup(group);
        membership.setConversation(conversation);

        membershipRepository.saveAndFlush(membership);

        String ownerToken = jwtService.generateToken(ownerUser);

        String groupConversationsUrl =
                "/api/conversations/workspace/"
                        + workspace.getId()
                        + "/groups/"
                        + group.getId()
                        + "/conversations";

        // Диалог назначен владельцу группы — API его возвращает.
        mockMvc.perform(
                        get(groupConversationsUrl)
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(
                        jsonPath("$[0].id")
                                .value(conversation.getId().toString())
                );

        // Переназначаем диалог другому сотруднику.
        conversation.setAssignedEmployee(otherEmployee);
        conversationRepository.saveAndFlush(conversation);

        // Старый владелец больше не видит диалог в своей группе.
        mockMvc.perform(
                        get(groupConversationsUrl)
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Переназначение не удаляет membership.
        assertTrue(
                membershipRepository
                        .findByGroupIdAndConversationId(
                                group.getId(),
                                conversation.getId()
                        )
                        .isPresent()
        );

        // Возвращаем назначение владельцу — диалог снова доступен.
        conversation.setAssignedEmployee(owner);
        conversationRepository.saveAndFlush(conversation);

        mockMvc.perform(
                        get(groupConversationsUrl)
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(
                        jsonPath("$[0].id")
                                .value(conversation.getId().toString())
                );
    }

    /**
     * Подсказка по телефону при ручной привязке учётки чата к клиенту (Stage 13.9, пункт 3 плана).
     *
     * Проверяется то, что невозможно проверить на моках: настоящий SQL находит клиента по последним
     * десяти цифрам, записанным другим форматом и с другим префиксом; текст поиска добавляется к
     * подсказке, а не заменяет её; и ничего не видит тот, кто не видит диалог с этой учёткой.
     */
    @Test
    void clientLinkCandidatesRankByPhoneTailAndStayBehindDialogAccess()
            throws Exception {

        OrganizationEntity organization =
                organizationRepository.saveAndFlush(TestDataFactory.organization());

        WorkspaceEntity workspace =
                workspaceRepository.saveAndFlush(TestDataFactory.workspace(organization));

        UserEntity user = userRepository.saveAndFlush(
                TestDataFactory.user("link_" + UUID.randomUUID(), "link_" + UUID.randomUUID() + "@example.com", "$2a$10$test"));
        user.setRole(UserRole.EMPLOYEE);

        EmployeeEntity employee = employeeRepository.saveAndFlush(
                new EmployeeEntity(organization, user, "Link", "Employee", "+79990000011"));
        employeeWorkspaceRepository.saveAndFlush(new EmployeeWorkspaceEntity(employee, workspace));

        ChannelEntity channel = channelRepository.saveAndFlush(TestDataFactory.channel(workspace));
        ChannelAccountEntity channelAccount =
                channelAccountRepository.saveAndFlush(TestDataFactory.channelAccount(channel));

        ClientAccountEntity prepared =
                TestDataFactory.clientAccount(null, channel.getType(), "link-" + UUID.randomUUID());
        prepared.setPhone("8 (999) 123-45-67");
        ClientAccountEntity account = clientAccountRepository.saveAndFlush(prepared);

        ConversationEntity dialog = TestDataFactory.conversation(workspace, channelAccount, account);
        dialog.setAssignedEmployee(employee);
        conversationRepository.saveAndFlush(dialog);

        ClientEntity byTail = client(organization, "Мария", "Хвостова", java.util.List.of("+7 (999) 123-45-67"));
        ClientEntity byName = client(organization, "Иван", "Телефонный", java.util.List.of("79990000000"));
        client(organization, "Пётр", "Безтелефонов", java.util.List.of());

        String token = jwtService.generateToken(user);
        String url = "/api/clientaccounts/{id}/client-candidates";

        // Пустая строка поиска: список собирает одна телефонная подсказка. Номер учётки записан
        // через восьмёрку и со скобками, клиент — через семёрку: совпадают последние десять цифр.
        mockMvc.perform(get(url, account.getId()).header("Authorization", "Bearer " + token).param("query", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(byTail.getId().toString()))
                .andExpect(jsonPath("$[0].firstName").value("Мария"))
                .andExpect(jsonPath("$[0].phoneMatch").value(true));

        // Текст поиска не отменяет подсказку: совпавший по телефону ряд идёт первым, а совпадение по
        // имени помечается совпадением по имени — то есть отсутствием телефонной подсказки.
        mockMvc.perform(get(url, account.getId()).header("Authorization", "Bearer " + token).param("query", "иван"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(byTail.getId().toString()))
                .andExpect(jsonPath("$[0].phoneMatch").value(true))
                .andExpect(jsonPath("$[1].id").value(byName.getId().toString()))
                .andExpect(jsonPath("$[1].phoneMatch").value(false));

        // Чужой запрос не подбирает людей по номеру: у кого нет диалога с этой учёткой, тот не
        // получает ни списка, ни подсказки.
        UserEntity outsider = userRepository.saveAndFlush(
                TestDataFactory.user("out_" + UUID.randomUUID(), "out_" + UUID.randomUUID() + "@example.com", "$2a$10$test"));
        outsider.setRole(UserRole.EMPLOYEE);
        employeeRepository.saveAndFlush(new EmployeeEntity(organization, outsider, "Out", "Side", "+79990000012"));

        mockMvc.perform(get(url, account.getId()).header("Authorization", "Bearer " + jwtService.generateToken(outsider))
                        .param("query", "Мария"))
                .andExpect(status().isForbidden());
    }

    private ClientEntity client(
            OrganizationEntity organization,
            String firstName,
            String lastName,
            java.util.List<String> phones
    ) {

        ClientEntity entity = new ClientEntity(firstName, lastName, organization);
        entity.setPhoneList(new java.util.ArrayList<>(phones));

        return clientRepository.saveAndFlush(entity);
    }
}