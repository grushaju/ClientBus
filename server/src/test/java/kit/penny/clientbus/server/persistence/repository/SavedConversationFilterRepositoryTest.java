
package kit.penny.clientbus.server.persistence.repository;

import jakarta.persistence.EntityManager;
import kit.penny.clientbus.common.enums.SavedFilterType;
import kit.penny.clientbus.server.integration.AbstractIntegrationTest;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.SavedConversationFilterEntity;
import kit.penny.clientbus.server.persistence.entity.UserEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SavedConversationFilterRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SavedConversationFilterRepository repository;

    private WorkspaceEntity workspace;
    private EmployeeEntity owner;
    private EmployeeEntity otherEmployee;

    @BeforeEach
    void setUp() {
        OrganizationEntity organization = TestDataFactory.organization();
        entityManager.persist(organization);

        workspace = TestDataFactory.workspace(organization);
        entityManager.persist(workspace);

        UserEntity ownerUser = TestDataFactory.user(
                "filter-owner", "filter-owner@example.com", "password"
        );
        entityManager.persist(ownerUser);

        owner = new EmployeeEntity(
                organization, ownerUser, "Filter", "Owner", null
        );
        entityManager.persist(owner);

        UserEntity otherUser = TestDataFactory.user(
                "filter-other", "filter-other@example.com", "password"
        );
        entityManager.persist(otherUser);

        otherEmployee = new EmployeeEntity(
                organization, otherUser, "Other", "Employee", null
        );
        entityManager.persist(otherEmployee);

        entityManager.flush();
    }

    @Test
    void saveAndFindById() {
        SavedConversationFilterEntity filter = createFilter(
                "Waiting", SavedFilterType.PRIVATE, owner
        );

        repository.saveAndFlush(filter);
        entityManager.clear();

        SavedConversationFilterEntity loaded =
                repository.findById(filter.getId()).orElseThrow();

        assertEquals("Waiting", loaded.getName());
        assertEquals(SavedFilterType.PRIVATE, loaded.getFilterType());
        assertEquals(workspace.getId(), loaded.getWorkspace().getId());
        assertEquals(owner.getId(), loaded.getOwnerEmployee().getId());
        assertNotNull(loaded.getCreatedAt());
    }

    @Test
    void findPrivateFilters_shouldReturnOnlyFiltersOfRequestedOwner() {
        repository.saveAndFlush(
                createFilter("My filter", SavedFilterType.PRIVATE, owner)
        );
        repository.saveAndFlush(
                createFilter("Other filter", SavedFilterType.PRIVATE, otherEmployee)
        );

        List<SavedConversationFilterEntity> result =
                repository
                        .findAllByWorkspaceIdAndFilterTypeAndOwnerEmployeeIdOrderByNameAsc(
                                workspace.getId(),
                                SavedFilterType.PRIVATE,
                                owner.getId()
                        );

        assertEquals(1, result.size());
        assertEquals("My filter", result.getFirst().getName());
    }

    @Test
    void findSystemFilters_shouldReturnFiltersOrderedByName() {
        repository.saveAndFlush(
                createFilter("Zulu", SavedFilterType.SYSTEM, null)
        );
        repository.saveAndFlush(
                createFilter("Alpha", SavedFilterType.SYSTEM, null)
        );

        List<SavedConversationFilterEntity> result =
                repository.findAllByWorkspaceIdAndFilterTypeOrderByNameAsc(
                        workspace.getId(),
                        SavedFilterType.SYSTEM
                );

        assertEquals(List.of("Alpha", "Zulu"),
                result.stream().map(SavedConversationFilterEntity::getName).toList());
    }

    @Test
    void findFilters_shouldNotReturnFiltersFromAnotherWorkspace() {
        OrganizationEntity organization2 = TestDataFactory.organization();
        entityManager.persist(organization2);

        WorkspaceEntity anotherWorkspace =
                TestDataFactory.workspace(organization2);
        entityManager.persist(anotherWorkspace);

        repository.saveAndFlush(
                createFilter("Local", SavedFilterType.SYSTEM, null)
        );

        SavedConversationFilterEntity foreignFilter =
                new SavedConversationFilterEntity();
        foreignFilter.setWorkspace(anotherWorkspace);
        foreignFilter.setName("Foreign");
        foreignFilter.setFilterType(SavedFilterType.SYSTEM);
        foreignFilter.setFilterDefinition("{}");
        repository.saveAndFlush(foreignFilter);

        List<SavedConversationFilterEntity> result =
                repository.findAllByWorkspaceIdAndFilterTypeOrderByNameAsc(
                        workspace.getId(),
                        SavedFilterType.SYSTEM
                );

        assertEquals(1, result.size());
        assertEquals("Local", result.getFirst().getName());
    }

    private SavedConversationFilterEntity createFilter(
            String name,
            SavedFilterType type,
            EmployeeEntity filterOwner
    ) {
        SavedConversationFilterEntity filter =
                new SavedConversationFilterEntity();
        filter.setWorkspace(workspace);
        filter.setOwnerEmployee(filterOwner);
        filter.setName(name);
        filter.setFilterType(type);
        filter.setFilterDefinition("{}");
        return filter;
    }
}
