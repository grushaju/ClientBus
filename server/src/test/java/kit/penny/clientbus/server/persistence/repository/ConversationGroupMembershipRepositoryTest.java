package kit.penny.clientbus.server.persistence.repository;

import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConversationGroupMembershipRepositoryTest
        extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ConversationGroupMembershipRepository membershipRepository;

    @Test
    void findAccessibleMembershipsRespectsCurrentAssignment() {
        // Organization and workspace
        OrganizationEntity organization =
                TestDataFactory.organization();
        entityManager.persist(organization);

        WorkspaceEntity workspace =
                TestDataFactory.workspace(organization);
        entityManager.persist(workspace);

        // Two employees in the same organization
        UserEntity ownerUser = TestDataFactory.user(
                "group-owner",
                "group-owner@example.com",
                "password"
        );
        entityManager.persist(ownerUser);

        EmployeeEntity owner = new EmployeeEntity(
                organization, ownerUser, "Owner", "Employee", null
        );
        entityManager.persist(owner);

        UserEntity otherUser = TestDataFactory.user(
                "group-other",
                "group-other@example.com",
                "password"
        );
        entityManager.persist(otherUser);

        EmployeeEntity otherEmployee = new EmployeeEntity(
                organization, otherUser, "Other", "Employee", null
        );
        entityManager.persist(otherEmployee);

        // Conversation dependencies
        ChannelEntity channel = TestDataFactory.channel(workspace);
        entityManager.persist(channel);

        ChannelAccountEntity channelAccount =
                TestDataFactory.channelAccount(channel);
        entityManager.persist(channelAccount);

        ClientAccountEntity clientAccount =
                TestDataFactory.clientAccount();
        entityManager.persist(clientAccount);

        ConversationEntity conversation =
                TestDataFactory.conversation(
                        workspace, channelAccount, clientAccount
                );
        conversation.setAssignedEmployee(owner);
        entityManager.persist(conversation);

        // Personal group and membership
        ConversationGroupEntity group =
                new ConversationGroupEntity(
                        workspace, owner, "Follow up"
                );
        entityManager.persist(group);

        ConversationGroupMembershipEntity membership =
                new ConversationGroupMembershipEntity(
                        group, conversation
                );
        entityManager.persist(membership);

        entityManager.flush();
        entityManager.clear();

        // 1. Assigned to the group owner: visible
        List<ConversationGroupMembershipEntity> result =
                membershipRepository.findAccessibleMemberships(
                        group.getId(),
                        workspace.getId(),
                        owner.getId()
                );

        assertEquals(1, result.size());
        assertEquals(
                conversation.getId(),
                result.get(0).getConversation().getId()
        );

        // 2. Reassigned to another employee: no longer visible
        ConversationEntity reassigned =
                entityManager.find(
                        ConversationEntity.class,
                        conversation.getId()
                );
        reassigned.setAssignedEmployee(otherEmployee);
        entityManager.flush();
        entityManager.clear();

        assertTrue(
                membershipRepository.findAccessibleMemberships(
                        group.getId(),
                        workspace.getId(),
                        owner.getId()
                ).isEmpty()
        );

        // Membership itself must remain in the database
        assertTrue(
                membershipRepository.findByGroupIdAndConversationId(
                        group.getId(),
                        conversation.getId()
                ).isPresent()
        );

        // 3. Unassigned: still not visible
        reassigned = entityManager.find(
                ConversationEntity.class,
                conversation.getId()
        );
        reassigned.setAssignedEmployee(null);
        entityManager.flush();
        entityManager.clear();

        assertTrue(
                membershipRepository.findAccessibleMemberships(
                        group.getId(),
                        workspace.getId(),
                        owner.getId()
                ).isEmpty()
        );

        // 4. Reassigned back to the owner: visible again
        reassigned = entityManager.find(
                ConversationEntity.class,
                conversation.getId()
        );
        reassigned.setAssignedEmployee(owner);
        entityManager.flush();
        entityManager.clear();

        result = membershipRepository.findAccessibleMemberships(
                group.getId(),
                workspace.getId(),
                owner.getId()
        );

        assertEquals(1, result.size());
        assertEquals(
                conversation.getId(),
                result.get(0).getConversation().getId()
        );
    }
}
