package kit.penny.clientbus.server.crm;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmClientEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity;
import kit.penny.clientbus.server.persistence.repository.crm.CrmConnectionRepository;
import kit.penny.clientbus.server.persistence.repository.crm.CrmMasterRepository;
import kit.penny.clientbus.server.persistence.repository.crm.CrmProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrmClientServiceTest {
    @Test void candidateDeletedAfterIdSelectionIsSkippedWhileSurvivingCandidateIsReturned() {
        var em=mock(EntityManager.class);
        var guard=mock(CrmAccessGuard.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var store=new CrmStore(em,mock(CrmConnectionRepository.class),mock(CrmProjectRepository.class),
                mock(CrmMasterRepository.class),guard,mock(CrmStorage.class));
        var service=new CrmClientService(store,mock(CrmClientSync.class),mock(UiDomainEvents.class),manager);
        UUID organizationId=UUID.randomUUID(), workspaceId=UUID.randomUUID();
        var organization=mock(OrganizationEntity.class);
        when(organization.getId()).thenReturn(organizationId);
        var workspace=mock(WorkspaceEntity.class);
        when(workspace.getId()).thenReturn(workspaceId);
        var project=new CrmProjectEntity();
        project.setOrganization(organization); project.setWorkspace(workspace);
        UUID sourceId=UUID.randomUUID(), deletedId=UUID.randomUUID(), survivingId=UUID.randomUUID();
        var source=new CrmClientEntity();
        source.setProject(project); source.setNormalizedPhone("79991234567");
        when(guard.organization()).thenReturn(organizationId);
        when(em.find(CrmClientEntity.class,sourceId)).thenReturn(source);
        var query=mock(Query.class);
        when(em.createNativeQuery(anyString(),eq(UUID.class))).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(deletedId,survivingId));
        // The first SQL statement observed the card, but DELETE committed before find.
        when(em.find(ClientEntity.class,deletedId)).thenReturn(null);
        var surviving=new ClientEntity("Confirmed","Name",organization);
        surviving.setId(survivingId); surviving.setPhoneList(List.of("+7 (999) 123-45-67"));
        when(em.find(ClientEntity.class,survivingId)).thenReturn(surviving);

        var candidates=service.candidates(sourceId,null);

        assertEquals(1,candidates.size());
        assertEquals(survivingId,candidates.getFirst().id());
        assertTrue(candidates.getFirst().phoneMatch());
        verify(em).find(ClientEntity.class,deletedId);
        verify(guard).workspace(workspaceId,false);
        verify(manager).commit(any());
    }
}
