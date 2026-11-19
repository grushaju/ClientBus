package kit.penny.clientbus.server.integration;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.dto.clientaccount.CreateClientAccountRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.exception.ClientInUseException;
import kit.penny.clientbus.server.fixture.TestDataFactory;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.repository.ClientRepository;
import kit.penny.clientbus.server.persistence.repository.OrganizationRepository;
import kit.penny.clientbus.server.persistence.repository.UserRepository;
import kit.penny.clientbus.server.persistence.repository.EmployeeRepository;
import kit.penny.clientbus.server.persistence.entity.EmployeeEntity;
import kit.penny.clientbus.common.enums.UserRole;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.clientbus.server.service.ClientAccountService;
import kit.penny.clientbus.server.service.ClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ClientLinkDeletionRaceIntegrationTest extends AbstractIntegrationTest {
    @Autowired ClientService clients;
    @Autowired ClientAccountService accounts;
    @Autowired ClientRepository repository;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired EmployeeRepository employees;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean CurrentUserService current;

    @Test void committedLinkMakesConcurrentDeletionFailWithoutDeletingTheClient() throws Exception { race(true); }
    @Test void committedDeletionMakesConcurrentLinkReturnNotFoundInsteadOfForeignKeyFailure() throws Exception { race(false); }

    private void race(boolean linkFirst) throws Exception {
        var organization=organizations.saveAndFlush(TestDataFactory.organization());
        when(current.isSuperAdmin()).thenReturn(true);
        when(current.getCurrentOrganizationId()).thenReturn(organization.getId());
        var user=TestDataFactory.user(); user.setRole(UserRole.SUPER_ADMIN);
        user.setEmail("link-race-"+java.util.UUID.randomUUID()+"@example.test");
        user=users.saveAndFlush(user);
        var employee=employees.saveAndFlush(new EmployeeEntity(organization,user,"Race","Admin","+79991234567"));
        when(current.getCurrentEmployeeId()).thenReturn(employee.getId());
        var client=repository.saveAndFlush(new ClientEntity("Race", "Probe",organization));
        var request=new CreateClientAccountRequest(client.getId(),ChannelType.TELEGRAM,
                "link-race-"+client.getId(),null,null,"Probe");
        var locked=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var started=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                if(linkFirst) accounts.createClientAccount(request); else clients.deleteClient(client.getId());
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("Race release timed out"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }));
            if(!locked.await(10,TimeUnit.SECONDS)) { first.get(1,TimeUnit.SECONDS); fail("First transaction did not acquire the client"); }
            var second=executor.submit(()->{
                started.countDown();
                if(linkFirst) return assertThrows(ClientInUseException.class,()->clients.deleteClient(client.getId()));
                return assertThrows(EntityNotFoundException.class,()->accounts.createClientAccount(request));
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->second.get(200,TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
            second.get(10,TimeUnit.SECONDS);
            assertEquals(linkFirst,repository.existsById(client.getId()));
        } finally { release.countDown(); }
    }
}
