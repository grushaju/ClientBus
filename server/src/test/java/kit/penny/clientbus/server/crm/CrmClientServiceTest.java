package kit.penny.clientbus.server.crm;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.OrganizationEntity;
import kit.penny.clientbus.server.persistence.entity.WorkspaceEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmClientEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity;
import kit.penny.clientbus.server.persistence.repository.crm.CrmConnectionRepository;
import kit.penny.clientbus.server.persistence.repository.crm.CrmEmployeeRepository;
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
    /**
     * Кандидат, удалённый между запросом идентификаторов и выборкой строк, не должен
     * оставлять призрачную карточку. С пакетной выборкой (`in :ids`) второго обращения по
     * каждому идентификатору больше нет: строка, которой не стало, просто не приходит в
     * списке, и проверка фиксирует именно это — плюс то, что построчных find не осталось.
     */
    @Test void candidateDeletedAfterIdSelectionIsSkippedWhileSurvivingCandidateIsReturned() {
        var em=mock(EntityManager.class);
        var guard=mock(CrmAccessGuard.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var store=new CrmStore(em,mock(CrmConnectionRepository.class),mock(CrmProjectRepository.class),
                mock(CrmEmployeeRepository.class),guard,mock(CrmStorage.class),mock(CrmRegistry.class));
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
        var ids=mock(Query.class);
        when(em.createNativeQuery(anyString(),eq(UUID.class))).thenReturn(ids);
        when(ids.setParameter(anyString(),any())).thenReturn(ids);
        when(ids.getResultList()).thenReturn(List.of(deletedId,survivingId));
        // Идентификаторов вернулось два, но удаление успело зафиксироваться до пакетной
        // выборки строк: пакет отвечает выжившей строкой.
        var surviving=new ClientEntity("Confirmed","Name",organization);
        surviving.setId(survivingId); surviving.setPhoneList(List.of("+7 (999) 123-45-67"));
        @SuppressWarnings("unchecked")
        TypedQuery<ClientEntity> targets=mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(ClientEntity.class))).thenReturn(targets);
        when(targets.setParameter(anyString(),any())).thenReturn(targets);
        when(targets.getResultList()).thenReturn(List.of(surviving));

        var candidates=service.candidates(sourceId,null);

        assertEquals(1,candidates.size());
        assertEquals(survivingId,candidates.getFirst().id());
        assertTrue(candidates.getFirst().phoneMatch());
        verify(em,never()).find(eq(ClientEntity.class),any());
        verify(em).createQuery(contains("in :ids"),eq(ClientEntity.class));
        verify(guard).workspace(workspaceId,false);
        verify(manager).commit(any());
    }
}
