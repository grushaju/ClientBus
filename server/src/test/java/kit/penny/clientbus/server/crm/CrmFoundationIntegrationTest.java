package kit.penny.clientbus.server.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.UiEventTypes;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.crm.dikidi.*;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.persistence.entity.*;
import kit.penny.clientbus.server.persistence.entity.crm.*;
import kit.penny.clientbus.server.persistence.repository.crm.*;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.*;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.math.BigDecimal;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class CrmFoundationIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("crmfoundation").withUsername("test").withPassword("test");
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static SessionFactory factory;
    private static StandardServiceRegistry hibernate;
    private TransactionTemplate tx;
    private EntityManager em;
    private CrmService service;
    private CrmCatalogSync sync;
    private CrmCatalogReader catalog;
    private CrmClientSync clientSync;
    private CrmClientService clients;
    private CrmScheduleSync scheduleSync;
    private CrmScheduleReader scheduleReader;
    private CrmAppointmentSync appointmentSync;
    private CrmAppointmentReader appointmentReader;
    private CrmAppointmentTimes appointmentTimes;
    private CrmStore store;
    private UiDomainEvents events;
    private CrmDeletionGuard deletion;
    private FakeProvider provider;
    private CrmSecretCipher cipher;
    private CrmStorage storage;
    private CrmMasterRepository masterRepository;
    private final AtomicReference<UUID> actor = new AtomicReference<>();
    private final AtomicLong clientNanoTime = new AtomicLong();
    private final AtomicLong appointmentNanoTime = new AtomicLong();
    private final ThreadLocal<UUID> requestActor = new ThreadLocal<>();
    private volatile BiConsumer<Method, Object[]> afterJpaCall = (method, args) -> {};
    private volatile Runnable afterMasterPage = () -> {};
    private UUID organization, workspace, admin, member, target, otherWorkspace;

    @BeforeAll static void bootstrap() {
        datasource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("create schema dbo"); jdbc.execute("create schema crm");
        factory = buildFactory();
    }
    private static SessionFactory buildFactory() {
        hibernate = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", datasource)
                .applySetting("hibernate.default_schema", "dbo")
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .applySetting("hibernate.hbm2ddl.halt_on_error", true).build();
        var metadata = new MetadataSources(hibernate);
        for (var type : List.of(OrganizationEntity.class, UserEntity.class, EmployeeEntity.class,
                WorkspaceEntity.class, EmployeeWorkspaceEntity.class, CrmConnectionEntity.class,
                CrmProjectEntity.class, CrmMasterEntity.class, CrmServiceEntity.class, CrmMasterServiceEntity.class,
                CrmSyncStateEntity.class, ClientEntity.class, ClientAccountEntity.class, CrmClientEntity.class,CrmWorkIntervalEntity.class,
                CrmAppointmentEntity.class,CrmAppointmentServiceEntity.class,CrmAppointmentMasterEntity.class)) metadata.addAnnotatedClass(type);
        return metadata.buildMetadata().buildSessionFactory();
    }
    @AfterAll static void closeDatabase() {
        if (factory != null) factory.close();
        if (hibernate != null) StandardServiceRegistryBuilder.destroy(hibernate);
    }
    @BeforeEach void setup() {
        jdbc.execute("truncate dbo.organization,dbo.users cascade");
        startRuntime();
        tx.executeWithoutResult(status -> {
            var org = new OrganizationEntity("test-organization"); em.persist(org); organization = org.getId();
            var adminEntity = employee(org, UserRole.SUPER_ADMIN, "admin"); admin = adminEntity.getId(); actor.set(admin);
            var ws = new WorkspaceEntity(org, "workspace"); em.persist(ws); workspace = ws.getId();
            var memberEntity = employee(org, UserRole.EMPLOYEE, "member"); member = memberEntity.getId();
            em.persist(new EmployeeWorkspaceEntity(memberEntity, ws));
            target = employee(org, UserRole.EMPLOYEE, "target-without-membership").getId();
            var other = new OrganizationEntity("other-organization"); em.persist(other);
            var otherWs = new WorkspaceEntity(other, "other-workspace"); em.persist(otherWs); otherWorkspace = otherWs.getId();
        });
    }
    private void startRuntime() {
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        afterJpaCall = (method, args) -> {};
        afterMasterPage = () -> {};
        clientNanoTime.set(0);
        appointmentNanoTime.set(0);
        em = (EntityManager)Proxy.newProxyInstance(EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(shared, args);
                        afterJpaCall.accept(method, args);
                        return result;
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
        var manager = new JpaTransactionManager(factory); manager.setDataSource(datasource); manager.setJpaDialect(new HibernateJpaDialect());
        manager.afterPropertiesSet(); tx = new TransactionTemplate(manager);
        var current = mock(CurrentUserService.class);
        when(current.getCurrentEmployeeId()).thenAnswer(invocation -> requestActor.get() == null ? actor.get() : requestActor.get());
        var access = new CrmAccessGuard(current, new AccessPolicy(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)), jdbc);
        storage = new CrmStorage(jdbc); storage.initialize();
        var repositories = new JpaRepositoryFactory(em);
        var masterDelegate = repositories.getRepository(CrmMasterRepository.class);
        masterRepository = (CrmMasterRepository)Proxy.newProxyInstance(CrmMasterRepository.class.getClassLoader(),
                new Class<?>[]{CrmMasterRepository.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(masterDelegate, args);
                        if (method.getName().equals("findAllByProjectId")) afterMasterPage.run();
                        return result;
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
        store = new CrmStore(em, repositories.getRepository(CrmConnectionRepository.class),
                repositories.getRepository(CrmProjectRepository.class), masterRepository, access, storage);
        events = mock(UiDomainEvents.class);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(UiEventTypes.ALL.contains(invocation.getArgument(1, String.class)));
            return null;
        }).when(events).workspace(any(), anyString());
        provider = new FakeProvider(); var registry = new CrmRegistry(List.of(provider));
        cipher = new CrmSecretCipher(new CrmSettings(Base64.getEncoder().encodeToString(new byte[32]), "test-key", 1000, 2000, 4096));
        var serviceRepository = repositories.getRepository(CrmServiceRepository.class);
        var offerRepository = repositories.getRepository(CrmMasterServiceRepository.class);
        sync = new CrmCatalogSync(store, jdbc, manager, registry, cipher, events,
                new CrmCatalogWriter(store));
        catalog = new CrmCatalogReader(store, serviceRepository, offerRepository, sync, manager);
        clientSync = new CrmClientSync(store, jdbc, manager, registry, cipher, events, new CrmClientWriter(store), clientNanoTime::get);
        clients = new CrmClientService(store, clientSync, events, manager);
        scheduleSync=new CrmScheduleSync(store,jdbc,manager,registry,cipher,events,new CrmScheduleWriter(store));
        scheduleReader=new CrmScheduleReader(store,scheduleSync,manager);
        appointmentSync=new CrmAppointmentSync(store,jdbc,manager,registry,cipher,events,new CrmAppointmentWriter(store),appointmentNanoTime::get);
        appointmentReader=new CrmAppointmentReader(store,appointmentSync,jdbc,manager);
        appointmentTimes=new CrmAppointmentTimes(store,jdbc,manager,events);
        service = new CrmService(store, registry, cipher, sync, manager, events);
        deletion = new CrmDeletionGuard(store, events);
    }
    private EmployeeEntity employee(OrganizationEntity org, UserRole role, String name) {
        var user = new UserEntity(name, name + "@example.invalid", "test-hash", role); em.persist(user);
        var employee = new EmployeeEntity(org, user, name, "test", ""); em.persist(employee); return employee;
    }
    @AfterEach void stopWorkers() throws Exception {
        provider.release.countDown(); sync.close();
        var executor = (ThreadPoolExecutor)ReflectionTestUtils.getField(sync, "executor");
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        clientSync.close();
        assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(clientSync, "executor")).awaitTermination(5, TimeUnit.SECONDS));
        scheduleSync.close();
        assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(scheduleSync,"executor")).awaitTermination(5,TimeUnit.SECONDS));
        appointmentSync.close();
        assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(appointmentSync,"executor")).awaitTermination(5,TimeUnit.SECONDS));
    }
    private final AtomicLong connectionNumber = new AtomicLong(70000000000L);
    private CrmConnectionDto connection() {
        return service.createConnection(new CreateCrmConnectionRequest(CrmType.DIKIDI, Long.toString(connectionNumber.getAndIncrement()), "test-only-secret"));
    }
    @Test void connectionLoginIsNormalizedUniqueAndReservedWhenDisabled() {
        var first = connection();
        service.updateConnection(first.id(), new UpdateCrmConnectionRequest(first.version(), null, null, false));
        var conflict = assertThrows(CrmException.class, () -> service.createConnection(
                new CreateCrmConnectionRequest(CrmType.DIKIDI, " +70000000000 ", "different-secret")));
        assertEquals(409, conflict.status()); assertEquals("CRM_CONNECTION_LOGIN_CONFLICT", conflict.code());
        var second = connection();
        byte[] secret = jdbc.queryForObject("select secretciphertext from crm.connection where id=?", byte[].class, second.id());
        assertEquals("CRM_CONNECTION_LOGIN_CONFLICT", assertThrows(CrmException.class, () -> service.updateConnection(
                second.id(), new UpdateCrmConnectionRequest(second.version(), "+70000000000", "replacement-secret", null))).code());
        assertArrayEquals(secret, jdbc.queryForObject("select secretciphertext from crm.connection where id=?", byte[].class, second.id()));
        assertEquals(second.version(), service.connection(second.id()).version());
        service.updateConnection(second.id(), new UpdateCrmConnectionRequest(second.version(), "+70000000001", "new-secret", null));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "update crm.connection set login='70000000000' where id=?", second.id()));
    }
    @Test void unusedConnectionDeletionChecksVersionRemovesCredentialsAndReleasesItsLogin() {
        var original=connection();
        var disabled=service.updateConnection(original.id(),new UpdateCrmConnectionRequest(original.version(),null,null,false));
        provider.invalidations.clear(); clearInvocations(events);
        assertEquals("CRM_VERSION_CONFLICT",assertThrows(CrmException.class,() -> service.deleteConnection(original.id(),original.version())).code());
        assertEquals(1,jdbc.queryForObject("select count(*) from crm.connection where id=?",Integer.class,original.id()));
        assertTrue(provider.invalidations.isEmpty());
        service.deleteConnection(disabled.id(),disabled.version());
        assertEquals(0,jdbc.queryForObject("select count(*) from crm.connection where id=?",Integer.class,original.id()));
        assertEquals(404,assertThrows(CrmException.class,() -> service.connection(original.id())).status());
        assertTrue(provider.invalidations.contains(original.id()));
        verify(events).organization(organization,"crm.invalidated");
        var replacement=service.createConnection(new CreateCrmConnectionRequest(CrmType.DIKIDI,"70000000000","replacement-test-secret"));
        assertNotEquals(original.id(),replacement.id());
    }
    @Test void connectionDeletionRechecksAnAdministratorDisabledWhileWaitingForTheConnection() throws Exception {
        connectionDeletionAfterAdminRevocation(true);
    }
    @Test void connectionDeletionRechecksAnAdministratorDemotedWhileWaitingForTheConnection() throws Exception {
        connectionDeletionAfterAdminRevocation(false);
    }
    private void connectionDeletionAfterAdminRevocation(boolean disable) throws Exception {
        var connection=connection(); var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var holderPid=new AtomicInteger();
        try (var workers=Executors.newFixedThreadPool(2)) {
            var holder=workers.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from crm.connection where id=? for update",UUID.class,connection.id());
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); locked.countDown(); await(release);
            }));
            await(locked);
            var removing=workers.submit(() -> {
                try { service.deleteConnection(connection.id(),connection.version()); return "deleted"; }
                catch (AccessDeniedException error) { return "ACCESS_DENIED"; }
            });
            try {
                awaitCondition(() -> blockedBy(holderPid.get())>0);
                jdbc.update(disable ? "update dbo.users set isenabled=false where userid=(select userid from dbo.employee where employeeid=?)"
                        : "update dbo.users set role='EMPLOYEE' where userid=(select userid from dbo.employee where employeeid=?)",admin);
            } finally { release.countDown(); }
            holder.get(10,TimeUnit.SECONDS);
            assertEquals("ACCESS_DENIED",removing.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from crm.connection where id=?",Integer.class,connection.id()));
        } finally {
            release.countDown();
            jdbc.update("update dbo.users set isenabled=true,role='SUPER_ADMIN' where userid=(select userid from dbo.employee where employeeid=?)",admin);
        }
        assertEquals(connection,service.connection(connection.id()));
    }
    @Test void connectionDeletionRequiresAnAdministratorFromTheSameOrganization() {
        var original=connection(); actor.set(member);
        assertThrows(AccessDeniedException.class,() -> service.deleteConnection(original.id(),original.version()));
        actor.set(tx.execute(status -> employee(em.find(WorkspaceEntity.class,otherWorkspace).getOrganization(),UserRole.SUPER_ADMIN,"other-admin").getId()));
        assertEquals(404,assertThrows(CrmException.class,() -> service.deleteConnection(original.id(),original.version())).status());
        actor.set(admin);
        assertEquals(original,service.connection(original.id()));
    }
    @Test void connectionDeletionRejectsEvenDisabledProjectsAndPreservesTheirImportedRows() throws Exception {
        var original=connection(); var project=bind(original); awaitIdle(); var master=first(project.id());
        for (boolean disabled : List.of(false,true)) {
            if (disabled) {
                project=service.updateProject(project.id(),new UpdateCrmProjectRequest(project.version(),false,null));
                var connection=service.connection(original.id());
                service.updateConnection(connection.id(),new UpdateCrmConnectionRequest(connection.version(),null,null,false));
            }
            var connection=service.connection(original.id());
            var error=assertThrows(CrmException.class,() -> service.deleteConnection(connection.id(),connection.version()));
            assertEquals(409,error.status()); assertEquals("CRM_CONNECTION_IN_USE",error.code());
            assertEquals(master.id(),first(project.id()).id());
            assertEquals(1,jdbc.queryForObject("select count(*) from crm.project where id=?",Integer.class,project.id()));
        }
    }
    @Test void connectionDeletionFencesAnInFlightProjectBinding() throws Exception {
        var original=connection(); var inspected=new CountDownLatch(1); var releaseInspection=new CountDownLatch(1);
        provider.inspector=id -> { inspected.countDown(); await(releaseInspection); return new CrmProjectInfo(id,"Test project","RUB",null); };
        try (var worker=Executors.newSingleThreadExecutor()) {
            var binding=worker.submit(() -> bind(original));
            try { await(inspected); service.deleteConnection(original.id(),original.version()); }
            finally { releaseInspection.countDown(); }
            var error=assertThrows(ExecutionException.class,() -> binding.get(10,TimeUnit.SECONDS));
            assertEquals(404,assertInstanceOf(CrmException.class,error.getCause()).status());
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from crm.connection",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from crm.project",Integer.class));
    }
    @Test void connectionDeletionWaitsForAConcurrentBindingAndThenRejectsIt() throws Exception {
        var original=connection(); var bound=new CountDownLatch(1); var releaseBinding=new CountDownLatch(1); var holderPid=new AtomicInteger();
        provider.catalogReader=access -> { throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE); };
        afterJpaCall=(method,args) -> {
            if (method.getName().equals("persist") && args!=null && args.length==1 && args[0] instanceof CrmProjectEntity) {
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); bound.countDown(); await(releaseBinding);
            }
        };
        try (var workers=Executors.newFixedThreadPool(2)) {
            var binding=workers.submit(() -> bind(original)); await(bound);
            var removing=workers.submit(() -> {
                try { service.deleteConnection(original.id(),original.version()); return "deleted"; }
                catch (CrmException error) { return error.code(); }
            });
            try { awaitCondition(() -> blockedBy(holderPid.get())>0); }
            finally { releaseBinding.countDown(); }
            assertNotNull(binding.get(10,TimeUnit.SECONDS));
            assertEquals("CRM_CONNECTION_IN_USE",removing.get(10,TimeUnit.SECONDS));
        } finally { releaseBinding.countDown(); afterJpaCall=(method,args) -> {}; }
        awaitIdle();
        assertEquals(1,jdbc.queryForObject("select count(*) from crm.connection",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from crm.project",Integer.class));
    }
    @Test void deletingThePreviousConnectionAfterRebindingPreservesAppointmentsAndHistoricalTerms() throws Exception {
        var original=connection(); var project=bind(original); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("a1",0,10,"1")));
        var before=appointments(project.id()).items().getFirst(); var replacement=connection();
        var current=service.projects(workspace,null).getFirst();
        service.changeConnection(project.id(),new ChangeCrmConnectionRequest(replacement.id(),current.version())); awaitIdle();
        var unused=service.connection(original.id()); service.deleteConnection(unused.id(),unused.version());
        var after=appointments(project.id()).items().getFirst();
        assertEquals(before.id(),after.id()); assertEquals(before.services(),after.services()); assertEquals(before.masters(),after.masters());
        assertEquals(replacement.id(),service.projects(workspace,null).getFirst().connectionId());
    }
    @Test void concurrentDuplicateConnectionsProduceOneSuccessAndOneDomainConflict() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<String> create = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { connectionForDuplicateTest(); return "created"; }
                catch (CrmException error) { return error.code(); }
            };
            var one = workers.submit(create); var two = workers.submit(create);
            assertEquals(Set.of("created", "CRM_CONNECTION_LOGIN_CONFLICT"), Set.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)));
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from crm.connection", Integer.class));
    }
    private void connectionForDuplicateTest() {
        service.createConnection(new CreateCrmConnectionRequest(CrmType.DIKIDI, "+70000000000", "test-only-secret"));
    }
    @Test void existingDuplicatesDoNotAllowAnotherCopyBeforeDatabaseCleanup() {
        var first = connection(); var second = connection();
        jdbc.execute("alter table crm.connection drop constraint uqconnectionlogin");
        try {
            jdbc.update("update crm.connection set login='70000000000' where id=?", second.id());
            assertEquals("CRM_CONNECTION_LOGIN_CONFLICT", assertThrows(CrmException.class, this::connectionForDuplicateTest).code());
            assertEquals(2, service.connections().size(), "Existing connections must not be deleted or merged automatically");
            service.updateConnection(first.id(), new UpdateCrmConnectionRequest(first.version(), null, null, false));
        } finally {
            jdbc.update("update crm.connection set login='70000000001' where id=?", second.id());
            jdbc.execute("alter table crm.connection add constraint uqconnectionlogin unique (organizationid,provider,login)");
        }
    }
    @Test void sameLoginInDifferentOrganizationsIsAllowed() {
        connection();
        UUID otherAdmin = tx.execute(status -> employee(em.find(WorkspaceEntity.class, otherWorkspace).getOrganization(), UserRole.SUPER_ADMIN, "other-admin").getId());
        actor.set(otherAdmin);
        connectionForDuplicateTest();
        assertEquals(2, jdbc.queryForObject("select count(*) from crm.connection", Integer.class));
        assertEquals(1, service.connections().size());
    }
    private CrmProjectDto bind(CrmConnectionDto connection) {
        return service.bind(workspace, new BindCrmProjectRequest(connection.id(), "100", null)).project();
    }
    private void awaitIdle() throws Exception {
        var executor = (ThreadPoolExecutor)ReflectionTestUtils.getField(sync, "executor");
        awaitCondition(() -> jdbc.queryForObject("select count(*) from crm.syncstate where dataset='WRITER:CATALOG' "
                + "and claimtoken is not null and claimuntil>clock_timestamp()", Integer.class) == 0 && executor.getActiveCount() == 0);
    }
    private CrmMasterDto first(UUID project) { return service.masters(project, null).items().getFirst(); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent test did not reach its barrier"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Expected database state was not reached");
    }
    private static int blockedBy(int holderPid) {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where ?=any(pg_blocking_pids(pid))", Integer.class, holderPid);
    }
    private <T> T as(UUID employee, Callable<T> action) throws Exception {
        requestActor.set(employee);
        try { return action.call(); }
        finally { requestActor.remove(); }
    }
    private CrmMasterDto additionalProjectMaster(CrmProjectDto source, UUID projectId, String externalId) {
        return tx.execute(status -> {
            var project=em.find(CrmProjectEntity.class,source.id());
            var additionalWorkspace=new WorkspaceEntity(project.getOrganization(),"Additional "+externalId);
            em.persist(additionalWorkspace); em.flush();
            // Explicit UUIDs let concurrency tests exercise the opposite insertion/lock order.
            jdbc.update("""
                    insert into crm.project (id,version,createdat,updatedat,organizationid,connectionid,workspaceid,
                        provider,externalid,displayname,currency,zoneid,zonesource,contextversion,zonerevision,enabled)
                    select ?,0,clock_timestamp(),clock_timestamp(),organizationid,connectionid,?,provider,?,?,
                        currency,zoneid,zonesource,contextversion,zonerevision,true from crm.project where id=?
                    """,projectId,additionalWorkspace.getId(),externalId,"Project "+externalId,source.id());
            var master=new CrmMasterEntity(); master.setProject(em.getReference(CrmProjectEntity.class,projectId));
            master.setExternalId(externalId); master.setDisplayName("Master "+externalId);
            master.setLastObservedAt(Instant.now()); master.setObservationState("OBSERVED");
            em.persist(master); em.flush(); return CrmStore.dto(master);
        });
    }
    @Test void employeeDeletionDoesNotDeadlockWithConnectionCredentialChange() throws Exception {
        employeeDeletionDuringConnectionChange(false);
    }
    @Test void employeeDeletionDoesNotDeadlockWithConnectionDisabling() throws Exception {
        employeeDeletionDuringConnectionChange(true);
    }
    private void employeeDeletionDuringConnectionChange(boolean disable) throws Exception {
        var connection=connection(); var project=bind(connection); awaitIdle();
        UUID lowerProject=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var additional=additionalProjectMaster(project,lowerProject,"101");
        var original=first(project.id());
        service.linkEmployee(original.id(),target,original.version());
        service.linkEmployee(additional.id(),target,additional.version());
        var current=service.connection(connection.id());
        var deletionHasLowerProject=new CountDownLatch(1); var updateHasHigherProject=new CountDownLatch(1);
        var deletionPid=new AtomicInteger();
        afterJpaCall=(method,args) -> {
            if (args==null || args.length!=2 || !(args[0] instanceof CrmProjectEntity row)
                    || args[1]!=LockModeType.PESSIMISTIC_WRITE) return;
            if (method.getName().equals("refresh") && row.getId().equals(lowerProject)) {
                deletionHasLowerProject.countDown();
                try {
                    // A correctly ordered updater waits for our lower project. An unordered one
                    // may already hold the higher project, producing the original lock cycle.
                    awaitCondition(() -> {
                        // The updater opens its connection after this transaction's first statistics read.
                        jdbc.execute("select pg_stat_clear_snapshot()");
                        return updateHasHigherProject.getCount()==0 || blockedBy(deletionPid.get())>0;
                    });
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
            if (method.getName().equals("lock") && row.getId().equals(project.id())) updateHasHigherProject.countDown();
        };
        try (var workers=Executors.newFixedThreadPool(2)) {
            var remove=workers.submit(() -> tx.executeWithoutResult(status -> {
                deletionPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                var employee=em.find(EmployeeEntity.class,target); deletion.employee(employee); em.remove(employee);
            }));
            await(deletionHasLowerProject);
            var update=workers.submit(() -> service.updateConnection(connection.id(),new UpdateCrmConnectionRequest(
                    current.version(),null,disable ? null : "new-test-only-secret",disable ? false : null)));
            assertDoesNotThrow(() -> remove.get(15,TimeUnit.SECONDS));
            var changed=assertDoesNotThrow(() -> update.get(15,TimeUnit.SECONDS));
            assertEquals(disable ? CrmConnectionStatus.DISABLED : CrmConnectionStatus.CONFIGURED,changed.status());
        } finally { afterJpaCall=(method,args) -> {}; }
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.employee where employeeid=?",Integer.class,target));
        for (UUID master : List.of(original.id(),additional.id())) {
            var detached=service.master(master);
            assertNull(detached.employeeId()); assertEquals(target,detached.mappingPreviousId());
            assertEquals(admin,detached.mappingUpdatedBy()); assertEquals("TARGET_DELETED",detached.mappingReason());
        }
    }
    @Test void catalogWaitsForConnectionBeforeTakingItsProjectLock() throws Exception {
        var connection = connection(); var project = bind(connection); awaitIdle();
        provider.reader = access -> provider.block("updated");
        sync.refresh(project.id()); assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
        var connectionLocked = new CountDownLatch(1); var releaseConnection = new CountDownLatch(1);
        var holderPid = new AtomicInteger();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var holder = workers.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from crm.connection where id=? for update", UUID.class, connection.id());
                holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                connectionLocked.countDown(); await(releaseConnection);
            }));
            try {
                await(connectionLocked); provider.release.countDown();
                awaitCondition(() -> blockedBy(holderPid.get()) > 0);
                assertDoesNotThrow(() -> tx.execute(status -> jdbc.queryForObject(
                        "select id from crm.project where id=? for update nowait", UUID.class, project.id())),
                        "A catalog waiting for its connection must not already own the project lock");
            } finally { releaseConnection.countDown(); holder.get(10, TimeUnit.SECONDS); }
        }
        awaitIdle();
        assertEquals("updated one", first(project.id()).displayName());
    }
    @Test void masterPageAndItsFreshnessDescribeTheSameCatalogGeneration() throws Exception {
        var project = bind(connection()); awaitIdle();
        long generation = sync.state(project.id()).generation();
        int reads = provider.reads.get();
        var rowsRead = new CountDownLatch(1); var releasePage = new CountDownLatch(1);
        var readerPid = new AtomicInteger();
        afterMasterPage = () -> {
            readerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            rowsRead.countDown(); await(releasePage);
        };
        provider.reader = access -> FakeProvider.masters("new generation");
        var executor = (ThreadPoolExecutor)ReflectionTestUtils.getField(sync, "executor");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var page = workers.submit(() -> service.masters(project.id(), null));
            Future<CrmSyncStateDto> refresh = null;
            try {
                await(rowsRead);
                refresh = workers.submit(() -> sync.refresh(project.id()));
                Future<CrmSyncStateDto> pendingRefresh = refresh;
                awaitCondition(() -> blockedBy(readerPid.get()) > 0
                        || pendingRefresh.isDone() && provider.reads.get() > reads && executor.getActiveCount() == 0);
                releasePage.countDown();
                var response = page.get(10, TimeUnit.SECONDS);
                assertEquals("initial one", response.items().getFirst().displayName());
                assertEquals(generation, response.dataState().generation(),
                        "Old rows must not be labelled with a newer catalog observation");
            } finally {
                releasePage.countDown();
                page.get(10, TimeUnit.SECONDS);
                if (refresh != null) refresh.get(10, TimeUnit.SECONDS);
            }
        }
        awaitIdle();
        assertEquals("new generation one", first(project.id()).displayName());
    }
    @Test void employeesCanConcurrentlyLinkMastersToEachOtherWithoutDeadlock() throws Exception {
        var project = bind(connection()); awaitIdle();
        tx.executeWithoutResult(status -> {
            var membership = new EmployeeWorkspaceEntity(em.getReference(EmployeeEntity.class, admin), em.getReference(WorkspaceEntity.class, workspace));
            membership.setSupervisor(true); em.persist(membership);
            jdbc.update("update dbo.employeeworkspace set issupervisor=true where employeeid=? and workspaceid=?", member, workspace);
        });
        var masters = service.masters(project.id(), null).items();
        var authorsLocked = new CyclicBarrier(2);
        afterJpaCall = (method, args) -> {
            if (method.getName().equals("find") && args != null && args.length == 3
                    && args[0] == EmployeeEntity.class && args[2] == LockModeType.PESSIMISTIC_READ) {
                try { authorsLocked.await(5, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError(error); }
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstLink = workers.submit(() -> as(admin, () -> service.linkEmployee(masters.get(0).id(), member, masters.get(0).version())));
            var secondLink = workers.submit(() -> as(member, () -> service.linkEmployee(masters.get(1).id(), admin, masters.get(1).version())));
            assertEquals(member, firstLink.get(10, TimeUnit.SECONDS).employeeId());
            assertEquals(admin, secondLink.get(10, TimeUnit.SECONDS).employeeId());
        } finally { afterJpaCall = (method, args) -> {}; }
    }
    @Test void employeeDeletionAuditDoesNotDeadlockWithAReciprocalLink() throws Exception {
        var project = bind(connection()); awaitIdle();
        jdbc.update("update dbo.users set role='SUPER_ADMIN' where userid=(select userid from dbo.employee where employeeid=?)", target);
        UUID author = admin.toString().compareTo(target.toString()) < 0 ? admin : target;
        UUID removed = author.equals(admin) ? target : admin;
        var masters = service.masters(project.id(), null).items();
        as(author, () -> service.linkEmployee(masters.get(0).id(), removed, masters.get(0).version()));
        var deletedEmployeeLocked = new CountDownLatch(1); var releaseDelete = new CountDownLatch(1);
        var deletePid = new AtomicInteger();
        afterJpaCall = (method, args) -> {
            if (method.getName().equals("lock") && args != null && args.length == 2
                    && args[0] instanceof EmployeeEntity row && row.getId().equals(removed)) {
                deletedEmployeeLocked.countDown(); await(releaseDelete);
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var remove = workers.submit(() -> as(author, () -> {
                tx.executeWithoutResult(status -> {
                    deletePid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    var employee = em.find(EmployeeEntity.class, removed);
                    deletion.employee(employee); em.remove(employee);
                });
                return true;
            }));
            Future<String> link = null;
            try {
                await(deletedEmployeeLocked);
                link = workers.submit(() -> as(removed, () -> {
                    try { service.linkEmployee(masters.get(1).id(), author, masters.get(1).version()); return "LINKED"; }
                    catch (CrmException error) { return error.code(); }
                }));
                awaitCondition(() -> blockedBy(deletePid.get()) > 0);
                releaseDelete.countDown();
                assertTrue(remove.get(10, TimeUnit.SECONDS));
                assertEquals("NOT_FOUND", link.get(10, TimeUnit.SECONDS));
            } finally {
                releaseDelete.countDown();
                remove.get(10, TimeUnit.SECONDS);
                if (link != null) link.get(10, TimeUnit.SECONDS);
                afterJpaCall = (method, args) -> {};
            }
        }
        actor.set(author);
        var detached = first(project.id());
        assertNull(detached.employeeId()); assertEquals(author, detached.mappingUpdatedBy());
        assertEquals("TARGET_DELETED", detached.mappingReason());
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.employee where employeeid=?", Integer.class, removed));
    }
    private static final class FakeProvider implements CrmProvider {
        final Set<UUID> invalidations=ConcurrentHashMap.newKeySet();
        record AppointmentRequest(String project,CrmAppointmentQuery query,String cursor,boolean history) {}
        final AtomicInteger appointmentReads=new AtomicInteger();
        volatile Function<AppointmentRequest,CrmAppointmentPage> appointmentsReader=request -> new CrmAppointmentPage(
                List.of(),null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,request.project(),request.query());
        public CrmAppointmentPage readAppointments(CrmAccess access,String project,CrmAppointmentQuery query,String cursor) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            appointmentReads.incrementAndGet(); return appointmentsReader.apply(new AppointmentRequest(project,query,cursor,false));
        }
        public CrmAppointmentPage readClientHistory(CrmAccess access,String project,CrmAppointmentQuery query,String cursor) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            appointmentReads.incrementAndGet(); return appointmentsReader.apply(new AppointmentRequest(project,query,cursor,true));
        }
        final AtomicInteger reads = new AtomicInteger(), inspections = new AtomicInteger();
        final AtomicInteger scheduleReads=new AtomicInteger();
        volatile Function<CrmWorkIntervalQuery,CrmScheduleSnapshot> scheduleReader=query -> new CrmScheduleSnapshot(
                List.of(),Instant.now(),CrmReadResult.Completeness.UNKNOWN,"100",query);
        public CrmScheduleSnapshot readWorkIntervals(CrmAccess access,String project,CrmWorkIntervalQuery query) {
            scheduleReads.incrementAndGet(); return scheduleReader.apply(query);
        }
        volatile Function<CrmAccess, CrmReadResult<CrmMaster>> reader = access -> masters("initial");
        volatile Function<CrmAccess, CrmCatalogSnapshot> catalogReader;
        volatile Function<String, CrmCatalogSnapshot> projectCatalogReader;
        volatile String projectDisplayName = "Test project";
        volatile Function<String, CrmClientPage> clientReader = cursor -> new CrmClientPage(List.of(), null,
                Instant.now(), CrmReadResult.Completeness.UNKNOWN, "100:CLIENTS");
        public CrmClientPage readClients(CrmAccess access, String id, String cursor) { return clientReader.apply(cursor); }
        volatile Function<String, CrmProjectInfo> inspector = id -> new CrmProjectInfo(id, "Test project", "RUB", null);
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        public String code() { return "DIKIDI"; }
        public void verifyConnection(CrmAccess access) {}
        public CrmProjectInfo inspectProject(CrmAccess access, String id) {
            inspections.incrementAndGet(); return inspector.apply(id);
        }
        public CrmReadResult<CrmMaster> readMasters(CrmAccess access, String id) { reads.incrementAndGet(); return reader.apply(access); }
        public CrmCatalogSnapshot readCatalog(CrmAccess access, String id) {
            reads.incrementAndGet();
            if (projectCatalogReader != null) return projectCatalogReader.apply(id);
            if (catalogReader != null) return catalogReader.apply(access);
            var result = reader.apply(access);
            return new CrmCatalogSnapshot(result.items(), List.of(), List.of(), result.observedAt(), result.completeness(),
                    result.scope().replace(":MASTERS", ":CATALOG"), projectDisplayName);
        }
        public void invalidate(UUID id) { invalidations.add(id); }
        static CrmReadResult<CrmMaster> masters(String name) {
            return new CrmReadResult<>(List.of(new CrmMaster("m1", name+" one", Map.of()),
                    new CrmMaster("m2", name+" two", Map.of())), Instant.now(), CrmReadResult.Completeness.COMPLETE, "100:MASTERS");
        }
        CrmReadResult<CrmMaster> block(String name) {
            entered.countDown();
            try { if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("Unreleased read"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE); }
            return masters(name);
        }
    }
    @Test void catalogRefreshUpdatesProjectNameWithoutChangingIdentityZoneOrEmployeeMappings() throws Exception {
        var project=bind(connection()); awaitIdle();
        var master=first(project.id()); service.linkEmployee(master.id(),target,master.version());
        var configured=service.updateProject(project.id(),new UpdateCrmProjectRequest(project.version(),null,"Europe/Moscow"));
        provider.projectDisplayName="Renamed project";
        clearInvocations(events);
        sync.refresh(project.id(),"CATALOG"); awaitIdle();
        var current=service.projects(workspace,null).getFirst();
        assertEquals("Renamed project",current.displayName());
        assertEquals(project.id(),current.id()); assertEquals(project.externalId(),current.externalId());
        assertEquals(project.workspaceId(),current.workspaceId()); assertEquals(project.connectionId(),current.connectionId());
        assertEquals(configured.contextVersion(),current.contextVersion()); assertEquals(configured.zoneRevision(),current.zoneRevision());
        assertEquals("Europe/Moscow",current.zoneId()); assertEquals(target,first(project.id()).employeeId());
        verify(events).workspace(any(),eq("crm.invalidated"));
    }
    private void dueCatalog(UUID project) {
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where projectid=? and dataset='CATALOG'",project);
    }
    private static CrmCatalogSnapshot projectSnapshot(String name,CrmFailure.Code error) {
        return new CrmCatalogSnapshot(FakeProvider.masters("profile").items(),List.of(),List.of(),Instant.now(),
                CrmReadResult.Completeness.COMPLETE,"100:CATALOG",name,error);
    }
    @Test void busyProjectProfileIsRetriedQuicklyAndTheWarningClearsOnRecovery() throws Exception {
        var project=bind(connection()); awaitIdle();
        provider.catalogReader=access -> projectSnapshot(null,CrmFailure.Code.CONNECTION_BUSY);
        sync.refresh(project.id()); awaitIdle();
        var busy=sync.state(project.id(),"CATALOG");
        assertEquals("SUCCEEDED",busy.status()); assertNull(busy.errorCode());
        assertEquals("CRM_CONNECTION_BUSY",busy.attemptCoverage().get("projectErrorCode"));
        assertEquals(project.displayName(),service.projects(workspace,null).getFirst().displayName());
        assertEquals(0,sync.refreshDue(300000));
        provider.catalogReader=access -> projectSnapshot("Recovered name",null);
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '11 seconds' where projectid=? and dataset='CATALOG'",project.id());
        assertEquals(1,sync.refreshDue(300000)); awaitIdle();
        assertEquals("Recovered name",service.projects(workspace,null).getFirst().displayName());
        assertFalse(sync.state(project.id(),"CATALOG").attemptCoverage().containsKey("projectErrorCode"));
        assertEquals(0,sync.refreshDue(300000));
    }
    @Test void currentRemoteRateLimitOverridesAnOlderBusyProfileWarning() throws Exception {
        var project=bind(connection()); awaitIdle();
        provider.catalogReader=access -> projectSnapshot(null,CrmFailure.Code.CONNECTION_BUSY);
        sync.refresh(project.id()); awaitIdle();
        provider.catalogReader=access -> { throw new CrmFailure(CrmFailure.Code.RATE_LIMITED); };
        sync.refresh(project.id()); awaitIdle();
        var failed=sync.state(project.id(),"CATALOG");
        assertEquals("CRM_RATE_LIMITED",failed.errorCode());
        assertEquals("CRM_CONNECTION_BUSY",failed.attemptCoverage().get("projectErrorCode"));
        int reads=provider.reads.get();
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '11 seconds' where projectid=? and dataset='CATALOG'",project.id());
        assertEquals(0,sync.refreshDue(300000)); assertEquals(reads,provider.reads.get());
        provider.catalogReader=access -> projectSnapshot("Recovered name",null); dueCatalog(project.id());
        assertEquals(1,sync.refreshDue(300000)); awaitIdle();
        assertFalse(sync.state(project.id(),"CATALOG").attemptCoverage().containsKey("projectErrorCode"));
    }
    @Test void catalogPoolRaceAndManualRejectionAreRetriedAsLocalContention() throws Exception { retryPoolRace(true); }
    @Test void schedulePoolRaceAndManualRejectionAreRetriedAsLocalContention() throws Exception { retryPoolRace(false); }
    private void retryPoolRace(boolean catalogDomain) throws Exception {
        var project=bind(connection()); awaitIdle(); var primary=first(project.id());
        var second=additionalProjectMaster(project,UUID.randomUUID(),"101");
        var third=additionalProjectMaster(project,UUID.randomUUID(),"102");
        var workersEntered=new CountDownLatch(2); var releaseWorkers=new CountDownLatch(1); var blockWorkers=new AtomicBoolean();
        provider.projectCatalogReader=id -> {
            if (blockWorkers.get() && !"100".equals(id)) { workersEntered.countDown(); await(releaseWorkers); }
            return new CrmCatalogSnapshot(List.of(),List.of(),List.of(),Instant.now(),CrmReadResult.Completeness.COMPLETE,id+":CATALOG");
        };
        provider.scheduleReader=query -> {
            if (blockWorkers.get() && !"m1".equals(query.externalMasterId())) { workersEntered.countDown(); await(releaseWorkers); }
            return new CrmScheduleSnapshot(List.of(),Instant.now(),CrmReadResult.Completeness.COMPLETE,
                    query.externalMasterId().equals("m1") ? "100" : query.externalMasterId(),query);
        };
        java.util.function.Consumer<CrmMasterDto> refresh=request -> {
            if (catalogDomain) sync.refresh(request.projectId());
            else scheduleSync.refresh(request.projectId(),request.id(),SCHEDULE_DAY,SCHEDULE_DAY);
        };
        java.util.function.IntSupplier poll=() -> catalogDomain ? sync.refreshDue(300000) : scheduleSync.refreshDue(300000);
        for (var request : List.of(primary,second,third)) { refresh.accept(request); awaitIdle(); awaitSchedule(); }
        if (catalogDomain) dueCatalog(project.id()); else dueSchedule(project.id(),primary.id(),SCHEDULE_DAY,SCHEDULE_DAY);
        var claimReached=new CountDownLatch(1); var resumeClaim=new CountDownLatch(1); var intercepted=new AtomicBoolean();
        afterJpaCall=(method,args) -> {
            if (method.getName().equals("refresh") && args[0] instanceof CrmProjectEntity row
                    && row.getId().equals(project.id()) && intercepted.compareAndSet(false,true)) {
                claimReached.countDown(); await(resumeClaim);
            }
        };
        blockWorkers.set(true);
        try (var polling=Executors.newSingleThreadExecutor()) {
            Future<Integer> pending=null;
            try {
                pending=polling.submit(poll::getAsInt);
                assertTrue(claimReached.await(3,TimeUnit.SECONDS));
                refresh.accept(second); refresh.accept(third); assertTrue(workersEntered.await(3,TimeUnit.SECONDS));
                resumeClaim.countDown(); assertEquals(0,pending.get(5,TimeUnit.SECONDS));
                var failed=catalogDomain ? sync.state(project.id(),"CATALOG") : scheduleSync.state(project.id(),primary.id(),SCHEDULE_DAY,SCHEDULE_DAY);
                assertEquals("CRM_CONNECTION_BUSY",failed.errorCode());
                var manual=assertThrows(CrmException.class,() -> refresh.accept(primary));
                assertEquals(503,manual.status()); assertEquals("CRM_CONNECTION_BUSY",manual.code());
            } finally {
                resumeClaim.countDown(); releaseWorkers.countDown(); afterJpaCall=(method,args) -> {};
                if (pending!=null) pending.get(5,TimeUnit.SECONDS);
                awaitIdle(); awaitSchedule();
            }
        }
        String dataset=catalogDomain ? "CATALOG" : "WORK_INTERVALS";
        assertEquals(0,poll.getAsInt());
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '11 seconds' where projectid=? and dataset=?",project.id(),dataset);
        assertEquals(1,poll.getAsInt()); awaitIdle(); awaitSchedule();
        var recovered=catalogDomain ? sync.state(project.id(),"CATALOG") : scheduleSync.state(project.id(),primary.id(),SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals("SUCCEEDED",recovered.status()); assertNull(recovered.errorCode());
        assertEquals(0,poll.getAsInt());
    }
    private static final class ReviewDikidiTransport implements DikidiTransport {
        private static final String DATA="""
                {"company":{"accesses":{"100":{"actor":{"id":"actor"}}}},"masters":{
                  "info":{"ids":["m1"],"data":{"m1":{"id":"m1","item_type":"master","is_master":"1","title":"Master"}}},
                  "services":{"ids":["m1"],"data":{"m1":{"s1":{"id":"s1","master_id":"m1","title":"Service","cost":"1000","time":"30"}}}},
                  "schedules":{"data":{}}}}
                """;
        volatile String name="Original name", blockedDataset;
        volatile int profileStatus=200;
        final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        private static Response ok(String body) { return new Response(200,body); }
        public Response exchange(String method,java.net.URI uri,List<Map.Entry<String,String>> form,boolean ajax,String project,Duration timeout) {
            return switch (uri.getPath()) {
                case "/ru/" -> ok("<input name='telegram_csrf' type='hidden' value='csrf'>");
                case "/ajax/check/auth/" -> ok("{\"html\":\"<input name='csrf' type='hidden' value='next'>\"}");
                case "/ajax/user/auth/" -> ok("{\"callback\":[\"sw.auth.complete();\"]}");
                case "/ru/owner/journal/" -> ok("journal");
                case "/ru/owner/ajax/profile/index/" -> new Response(profileStatus,"{\"html\":\"<h2>"+name+"</h2>\"}");
                case "/ru/owner/ajax/journal/api/" -> {
                    String dataset=form.contains(Map.entry("request[get][]","masters.schedules")) ? "WORK_INTERVALS" : "CATALOG";
                    if (dataset.equals(blockedDataset)) { entered.countDown(); await(release); }
                    yield ok(DATA);
                }
                default -> throw new AssertionError(uri.getPath());
            };
        }
    }
    private DikidiProvider realProvider(ReviewDikidiTransport transport) {
        var real=new DikidiProvider(new DikidiProvider.Settings(Duration.ofSeconds(2),Duration.ofSeconds(15),8192),() -> transport);
        var registry=new CrmRegistry(List.of(real));
        ReflectionTestUtils.setField(sync,"registry",registry); ReflectionTestUtils.setField(scheduleSync,"registry",registry);
        return real;
    }
    @Test void catalogBusyOnTheScheduleSessionIsRetriedBeforeTheNormalRefreshInterval() throws Exception { retryBusySession(true); }
    @Test void scheduleBusyOnTheCatalogSessionIsRetriedBeforeTheNormalRefreshInterval() throws Exception { retryBusySession(false); }
    private void retryBusySession(boolean blockSchedule) throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY); awaitSchedule();
        var transport=new ReviewDikidiTransport();
        try (var real=realProvider(transport)) {
            sync.refresh(project.id()); awaitIdle(); assertEquals("Original name",service.projects(workspace,null).getFirst().displayName());
            transport.name="Renamed project"; transport.blockedDataset=blockSchedule ? "WORK_INTERVALS" : "CATALOG";
            dueCatalog(project.id()); dueSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
            try {
                if (blockSchedule) assertEquals(1,scheduleSync.refreshDue(300000)); else assertEquals(1,sync.refreshDue(300000));
                assertTrue(transport.entered.await(5,TimeUnit.SECONDS));
                if (blockSchedule) { assertEquals(1,sync.refreshDue(300000)); awaitIdle(); }
                else { assertEquals(1,scheduleSync.refreshDue(300000)); awaitSchedule(); }
                var failed=blockSchedule ? sync.state(project.id(),"CATALOG") : scheduleSync.state(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
                assertEquals("CRM_CONNECTION_BUSY",failed.errorCode());
            } finally { transport.release.countDown(); awaitIdle(); awaitSchedule(); }
            assertEquals(0,blockSchedule ? sync.refreshDue(300000) : scheduleSync.refreshDue(300000));
            String dataset=blockSchedule ? "CATALOG" : "WORK_INTERVALS";
            jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '11 seconds' where projectid=? and dataset=?",project.id(),dataset);
            assertEquals(1,blockSchedule ? sync.refreshDue(300000) : scheduleSync.refreshDue(300000)); awaitIdle(); awaitSchedule();
            var current=blockSchedule ? sync.state(project.id(),"CATALOG") : scheduleSync.state(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
            assertNull(current.errorCode()); assertNotNull(current.lastSuccessAt());
            assertEquals(blockSchedule ? "SUCCEEDED" : "PARTIAL",current.status());
            assertEquals("Renamed project",service.projects(workspace,null).getFirst().displayName());
        }
    }
    @Test void remoteRateLimitsKeepTheNormalCatalogAndScheduleRetryInterval() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        provider.reader=access -> { throw new CrmFailure(CrmFailure.Code.RATE_LIMITED); };
        provider.scheduleReader=query -> { throw new CrmFailure(CrmFailure.Code.RATE_LIMITED); };
        sync.refresh(project.id()); awaitIdle(); scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY); awaitSchedule();
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '11 seconds' where dataset in ('CATALOG','WORK_INTERVALS')");
        assertEquals(0,sync.refreshDue(300000)); assertEquals(0,scheduleSync.refreshDue(300000));
        assertEquals("CRM_RATE_LIMITED",sync.state(project.id()).errorCode());
        assertEquals("CRM_RATE_LIMITED",scheduleSync.state(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).errorCode());
    }
    @Test void localConnectionContentionDoesNotDegradeConnectionHealth() {
        var connection=connection(); var checked=service.verify(connection.id());
        long generation=jdbc.queryForObject("select healthgeneration from crm.connection where id=?",Long.class,connection.id());
        long applied=jdbc.queryForObject("select healthappliedgeneration from crm.connection where id=?",Long.class,connection.id());
        provider.inspector=id -> { throw new CrmFailure(CrmFailure.Code.CONNECTION_BUSY); };
        var failure=assertThrows(CrmException.class,() -> service.check(connection.id(),new CheckCrmProjectRequest("100")));
        assertEquals(503,failure.status()); assertEquals("CRM_CONNECTION_BUSY",failure.code());
        assertEquals(checked,service.connection(connection.id()));
        assertTrue(jdbc.queryForObject("select healthgeneration from crm.connection where id=?",Long.class,connection.id())>generation);
        assertEquals(applied,jdbc.queryForObject("select healthappliedgeneration from crm.connection where id=?",Long.class,connection.id()));
    }
    @Test void failedProfilePreservesTheNameWhileCatalogPricesUpdateAndTheWarningClearsOnRecovery() throws Exception {
        var project=bind(connection()); awaitIdle(); var transport=new ReviewDikidiTransport();
        try (var real=realProvider(transport)) {
            for (int status : List.of(403,500)) {
                transport.profileStatus=status; sync.refresh(project.id()); awaitIdle();
                var current=sync.state(project.id(),"CATALOG"); assertEquals("SUCCEEDED",current.status()); assertNull(current.errorCode());
                assertEquals(status==403 ? "CRM_PROVIDER_FORBIDDEN" : "CRM_TEMPORARILY_UNAVAILABLE",current.attemptCoverage().get("projectErrorCode"));
                assertEquals(project.displayName(),service.projects(workspace,null).getFirst().displayName());
                var services=catalog.services(project.id(),null,null,false,null).items(); assertEquals(1,services.size());
                assertEquals("1000",services.getFirst().offers().getFirst().price());
            }
            transport.profileStatus=200; transport.name="Recovered name"; sync.refresh(project.id()); awaitIdle();
            assertEquals("Recovered name",service.projects(workspace,null).getFirst().displayName());
            assertFalse(sync.state(project.id(),"CATALOG").attemptCoverage().containsKey("projectErrorCode"));
        }
    }
    @Test void periodicCatalogRefreshUpdatesTheNameAfterRestartWithoutRequestAuthentication() throws Exception {
        var project=bind(connection()); awaitIdle();
        assertEquals(0,sync.refreshDue(300000));
        jdbc.update("update crm.syncstate set scope=scope-'refreshActorId' where projectid=? and dataset='CATALOG'",project.id());
        sync.close(); assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(sync,"executor")).awaitTermination(5,TimeUnit.SECONDS));
        sync=new CrmCatalogSync(store,jdbc,tx.getTransactionManager(),new CrmRegistry(List.of(provider)),cipher,events,new CrmCatalogWriter(store));
        dueCatalog(project.id()); provider.projectDisplayName="Renamed automatically";
        clearInvocations(events); actor.set(null);
        try { assertEquals(1,sync.refreshDue(300000)); awaitIdle(); }
        finally { actor.set(admin); }
        assertEquals("Renamed automatically",service.projects(workspace,null).getFirst().displayName());
        assertEquals(0,sync.refreshDue(300000)); verify(events).workspace(any(),eq("crm.invalidated"));
        assertEquals(admin.toString(),jdbc.queryForObject("select scope->>'refreshActorId' from crm.syncstate where projectid=? and dataset='CATALOG'",String.class,project.id()));
    }
    @Test void periodicCatalogRefreshSkipsRevokedActorsAndDisabledConfiguration() throws Exception {
        var connection=connection(); var project=bind(connection); awaitIdle();
        actor.set(member); sync.refresh(project.id()); awaitIdle(); actor.set(admin); dueCatalog(project.id());
        int reads=provider.reads.get();
        jdbc.update("delete from dbo.employeeworkspace where employeeid=? and workspaceid=?",member,workspace);
        assertEquals(0,sync.refreshDue(300000));
        tx.executeWithoutResult(status -> em.persist(new EmployeeWorkspaceEntity(em.find(EmployeeEntity.class,member),em.find(WorkspaceEntity.class,workspace))));
        jdbc.update("update dbo.users set isenabled=false where userid=(select userid from dbo.employee where employeeid=?)",member);
        assertEquals(0,sync.refreshDue(300000));
        jdbc.update("update dbo.users set isenabled=true where userid=(select userid from dbo.employee where employeeid=?)",member);
        var disabled=service.updateProject(project.id(),new UpdateCrmProjectRequest(project.version(),false,null));
        assertEquals(0,sync.refreshDue(300000));
        service.updateProject(project.id(),new UpdateCrmProjectRequest(disabled.version(),true,null));
        jdbc.update("update crm.connection set status='REAUTH_REQUIRED' where id=?",connection.id());
        assertEquals(0,sync.refreshDue(300000));
        var current=service.connection(connection.id());
        service.updateConnection(current.id(),new UpdateCrmConnectionRequest(current.version(),null,null,false));
        assertEquals(0,sync.refreshDue(300000)); assertEquals(reads,provider.reads.get());
    }
    @Test void periodicCatalogRefreshServesOldestProjectsBeforeApplyingWorkerCapacity() throws Exception {
        var project=bind(connection()); awaitIdle();
        var projects=new ArrayList<>(List.of(project.id(),additionalProjectMaster(project,UUID.randomUUID(),"101").projectId(),
                additionalProjectMaster(project,UUID.randomUUID(),"102").projectId()));
        provider.projectCatalogReader=id -> new CrmCatalogSnapshot(List.of(),List.of(),List.of(),Instant.now(),
                CrmReadResult.Completeness.COMPLETE,id+":CATALOG","Project "+id);
        // Newly upgraded projects without a CATALOG observation must be discovered as well.
        assertEquals(2,sync.refreshDue(300000)); awaitIdle();
        projects.sort(Comparator.comparing(UUID::toString)); var oldest=projects.getLast();
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where dataset='CATALOG'");
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '1 day' where projectid=? and dataset='CATALOG'",oldest);
        long generation=sync.state(oldest,"CATALOG").generation();
        assertEquals(2,sync.refreshDue(300000)); awaitIdle();
        assertTrue(sync.state(oldest,"CATALOG").generation()>generation);
        assertEquals(1,sync.refreshDue(300000)); awaitIdle(); assertEquals(0,sync.refreshDue(300000));
    }
    @Test void catalogWithoutANameRetainsItAndInvalidNameCannotReplaceIt() throws Exception {
        var project=bind(connection()); awaitIdle();
        provider.catalogReader=access -> new CrmCatalogSnapshot(List.of(),List.of(),List.of(),Instant.now(),
                CrmReadResult.Completeness.COMPLETE,"100:CATALOG");
        sync.refresh(project.id()); awaitIdle();
        assertEquals(project.displayName(),service.projects(workspace,null).getFirst().displayName());
        provider.catalogReader=access -> new CrmCatalogSnapshot(List.of(),List.of(),List.of(),Instant.now(),
                CrmReadResult.Completeness.COMPLETE,"100:CATALOG"," ");
        sync.refresh(project.id()); awaitIdle();
        assertEquals("CRM_INVALID_RESPONSE",sync.state(project.id()).errorCode());
        assertEquals(project.displayName(),service.projects(workspace,null).getFirst().displayName());
    }
    @Test void renamingConnectionDoesNotInvalidateCredentialsOrCatalogAndUsesVersion() throws Exception {
        var connection = service.createConnection(new CreateCrmConnectionRequest(CrmType.DIKIDI,"70000000000","test-only-secret"," Main "));
        var project = bind(connection); awaitIdle();
        var verified = service.verify(connection.id());
        long credentialVersion = jdbc.queryForObject("select credentialversion from crm.connection where id=?", Long.class, connection.id());
        long revision = sync.revision(project.id());
        int reads = provider.reads.get();
        var renamed = service.updateConnection(connection.id(), new UpdateCrmConnectionRequest(verified.version(), null, null, null," Branch "));
        assertEquals("Main", connection.displayName()); assertEquals("Branch", renamed.displayName());
        assertTrue(renamed.version() > verified.version());
        assertEquals(verified.status(), renamed.status()); assertEquals(verified.lastCheckedAt(), renamed.lastCheckedAt());
        assertEquals(credentialVersion, jdbc.queryForObject("select credentialversion from crm.connection where id=?", Long.class, connection.id()));
        assertEquals(revision, sync.revision(project.id())); assertEquals(reads, provider.reads.get());
        assertEquals("CRM_VERSION_CONFLICT", assertThrows(CrmException.class, () -> service.updateConnection(connection.id(),
                new UpdateCrmConnectionRequest(verified.version(), null, null, null,"Stale"))).code());
        var cleared = service.updateConnection(connection.id(), new UpdateCrmConnectionRequest(renamed.version(), null, null, null," "));
        assertNull(cleared.displayName());
        assertThrows(CrmException.class, () -> service.updateConnection(connection.id(), new UpdateCrmConnectionRequest(cleared.version(), null, null, null,"x".repeat(81))));
    }
    @Test void masterSearchIsLiteralPagedAndCursorIsBoundToFilters() throws Exception {
        var source = new ArrayList<CrmMaster>();
        for (int i = 0; i < 105; i++) source.add(new CrmMaster(String.format("m%03d", i), "Needle " + i, Map.of()));
        source.add(new CrmMaster("z", "100%_literal!", Map.of()));
        provider.catalogReader = access -> new CrmCatalogSnapshot(source, List.of(), List.of(), Instant.now(), CrmReadResult.Completeness.COMPLETE,"100:CATALOG");
        var project = bind(connection()); awaitIdle();
        var first = service.masters(project.id(), null, "NEEDLE", false, false);
        assertEquals(100, first.items().size());
        assertEquals(5, service.masters(project.id(), first.nextCursor(), "needle", false, false).items().size());
        assertEquals(1, service.masters(project.id(), null, "%_", false, false).items().size());
        assertEquals(1, service.masters(project.id(), null, "!", false, false).items().size());
        assertEquals("INVALID_REQUEST", assertThrows(CrmException.class,
                () -> service.masters(project.id(), first.nextCursor(), "changed", false, false)).code());
        var master = first.items().getFirst(); service.linkEmployee(master.id(), target, master.version());
        assertEquals(100, service.masters(project.id(), null, "needle", true, false).items().size());
        assertEquals(4, service.masters(project.id(), service.masters(project.id(), null, "needle", true, false).nextCursor(), "needle", true, false).items().size());
        actor.set(member);
        assertThrows(AccessDeniedException.class, () -> service.masters(project.id(), null, null, true, false));
        assertEquals(master.id(), service.master(master.id()).id());
        assertThrows(AccessDeniedException.class, () -> service.employeeCandidates(project.id()));
    }
    @Test void serviceSearchFindsLaterRowsAndDoesNotTreatWildcardsAsPatterns() throws Exception {
        var rows = new ArrayList<CrmCatalogService>();
        for (int i = 0; i < 105; i++) rows.add(new CrmCatalogService(String.format("s%03d", i), "Service " + i, "Care", Map.of()));
        rows.add(new CrmCatalogService("z", "100%_literal!", "Other", Map.of()));
        provider.catalogReader = access -> catalogSnapshot(rows, List.of(), CrmReadResult.Completeness.COMPLETE);
        var project = bind(connection()); awaitIdle();
        assertEquals("s104", catalog.services(project.id(), null, null, false, null, "service 104").items().getFirst().externalId());
        var first = catalog.services(project.id(), null, null, false, null, "care");
        assertEquals(100, first.items().size());
        assertEquals(5, catalog.services(project.id(), null, null, false, first.nextCursor(), "CARE").items().size());
        assertEquals(1, catalog.services(project.id(), null, null, false, null, "%_").items().size());
        assertEquals("INVALID_REQUEST", assertThrows(CrmException.class, () -> catalog.services(project.id(), null, null, false, first.nextCursor(), "Other")).code());
    }
    @Test void employeeCandidatesFollowAccessPolicyAcrossManagedWorkspaces() throws Exception {
        var project = bind(connection()); awaitIdle();
        assertTrue(service.employeeCandidates(project.id()).stream().anyMatch(row -> row.id().equals(target)));
        assertFalse(service.employeeCandidates(project.id()).stream().anyMatch(row -> row.id().equals(admin)));
        tx.executeWithoutResult(status -> {
            var ws = new WorkspaceEntity(em.getReference(OrganizationEntity.class, organization), "second-managed"); em.persist(ws);
            var manager = new EmployeeWorkspaceEntity(em.getReference(EmployeeEntity.class, member), ws); manager.setSupervisor(true); em.persist(manager);
            em.persist(new EmployeeWorkspaceEntity(em.getReference(EmployeeEntity.class, target), ws));
        });
        jdbc.update("update dbo.employeeworkspace set issupervisor=true where workspaceid=? and employeeid=?", workspace, member);
        actor.set(member);
        assertTrue(service.employeeCandidates(project.id()).stream().anyMatch(row -> row.id().equals(target)));
        assertThrows(AccessDeniedException.class, service::organizationProjects);
        jdbc.update("update dbo.employeeworkspace set issupervisor=false where employeeid=?", member);
        assertThrows(AccessDeniedException.class, () -> service.employeeCandidates(project.id()));
    }
    @Test void projectPreviewHasNoPersistenceAndAvailableTypesGateSecrets() {
        var connection = connection();
        assertEquals(List.of(CrmType.DIKIDI), service.types());
        var preview = service.check(connection.id(), new CheckCrmProjectRequest("100"));
        assertEquals("Test project", preview.displayName());
        assertEquals(0, provider.reads.get());
        assertEquals(0, jdbc.queryForObject("select count(*) from crm.project", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from crm.master", Integer.class));
        for (var type : List.of(CrmType.AMO, CrmType.BITRIX))
            assertEquals("CRM_UNSUPPORTED", assertThrows(CrmException.class,
                    () -> service.createConnection(new CreateCrmConnectionRequest(type,"70000000000","test-only-secret"))).code());
        assertEquals(1, jdbc.queryForObject("select count(*) from crm.connection", Integer.class));
    }
    @Test void persistedSecretUsesConnectionBoundCipherAndNeverAppearsInDto() {
        var connection = connection();
        byte[] bytes = jdbc.queryForObject("select secretciphertext from crm.connection where id=?", byte[].class, connection.id());
        assertArrayEquals("test-only-secret".toCharArray(), cipher.decrypt(organization, connection.id(), "test-key", bytes));
        assertFalse(connection.toString().contains("test-only-secret"));
        assertFalse(connection.loginHint().contains("70000000000"));
    }
    @Test void providerProjectMismatchCannotPreviewBindOrReplaceConnection() throws Exception {
        var connection = connection(); var project = bind(connection); awaitIdle();
        var replacement = connection();
        provider.inspector = id -> new CrmProjectInfo("wrong-project", "Wrong project", "RUB", null);
        assertEquals("CRM_CONTEXT_MISMATCH", assertThrows(CrmException.class,
                () -> service.check(connection.id(), new CheckCrmProjectRequest("100"))).code());
        assertEquals("CRM_CONTEXT_MISMATCH", assertThrows(CrmException.class,
                () -> service.bind(workspace, new BindCrmProjectRequest(connection.id(), "200", null))).code());
        assertEquals("CRM_CONTEXT_MISMATCH", assertThrows(CrmException.class,
                () -> service.changeConnection(project.id(), new ChangeCrmConnectionRequest(replacement.id(), project.version()))).code());
        assertEquals(connection.id(), service.projects(workspace, null).getFirst().connectionId());
        assertEquals(1, jdbc.queryForObject("select count(*) from crm.project", Integer.class));
    }
    @Test void concurrentBindingToDifferentWorkspacesReturnsDomainConflict() throws Exception {
        var connection = connection();
        UUID second = tx.execute(status -> {
            var row = new WorkspaceEntity(em.getReference(OrganizationEntity.class, organization), "second-workspace");
            em.persist(row); return row.getId();
        });
        var barrier = new CyclicBarrier(2);
        provider.inspector = id -> {
            try { barrier.await(5, TimeUnit.SECONDS); }
            catch (Exception error) { throw new AssertionError(error); }
            return new CrmProjectInfo(id, "Test project", "RUB", null);
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = List.of(workspace, second).stream().map(id -> workers.submit(() -> {
                try { return service.bind(id, new BindCrmProjectRequest(connection.id(), "100", null)).created() ? "CREATED" : "EXISTING"; }
                catch (CrmException error) { return error.code(); }
            })).toList();
            var outcomes = new ArrayList<String>();
            for (var result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(Set.of("CREATED", "CRM_PROJECT_BINDING_CONFLICT"), new HashSet<>(outcomes));
        }
        awaitIdle();
        assertEquals(1, jdbc.queryForObject("select count(*) from crm.project", Integer.class));
    }
    @Test void bindingIsIdempotentAndDisabledWorkspaceBindingStillBlocksAnotherProject() throws Exception {
        var connection = connection(); var project = bind(connection); awaitIdle();
        assertFalse(service.bind(workspace, new BindCrmProjectRequest(connection.id(),"100",null)).created());
        assertEquals(2, service.masters(project.id(), null).items().size());
        service.updateProject(project.id(), new UpdateCrmProjectRequest(project.version(),false,null));
        assertEquals("CRM_PROJECT_BINDING_CONFLICT", assertThrows(CrmException.class,
                () -> service.bind(workspace, new BindCrmProjectRequest(connection.id(),"200",null))).code());
        assertEquals(1, jdbc.queryForObject("select count(*) from crm.project", Integer.class));
    }
    @Test void catalogReloadPreservesManualMappingAndChecksVersions() throws Exception {
        var project = bind(connection()); awaitIdle();
        var master = first(project.id());
        var linked = service.linkEmployee(master.id(), target, master.version());
        assertEquals(target, linked.employeeId()); assertEquals(admin, linked.mappingUpdatedBy());
        Instant persistedMappingTime = first(project.id()).mappingUpdatedAt();
        assertEquals("CRM_VERSION_CONFLICT", assertThrows(CrmException.class,
                () -> service.linkEmployee(master.id(), member, master.version())).code());
        provider.reader = access -> FakeProvider.masters("renamed");
        sync.refresh(project.id()); awaitIdle();
        var loaded = first(project.id());
        assertEquals(target, loaded.employeeId()); assertEquals("renamed one", loaded.displayName());
        assertEquals(persistedMappingTime, loaded.mappingUpdatedAt()); assertEquals("LINK", loaded.mappingReason());
    }
    private static CrmCatalogService catalogService(String id) { return new CrmCatalogService(id, "Service "+id, null, Map.of()); }
    private static CrmServiceOffer offer(String master, String service, String price, long duration) {
        return new CrmServiceOffer(master, service, new BigDecimal(price), null, duration,
                Map.of("active", true, "online", true), Map.of(), Set.of("price", "durationSeconds"));
    }
    private static CrmCatalogSnapshot catalogSnapshot(List<CrmCatalogService> services, List<CrmServiceOffer> offers,
                                                      CrmReadResult.Completeness completeness) {
        return new CrmCatalogSnapshot(FakeProvider.masters("catalog").items(), services, offers, Instant.now(), completeness, "100:CATALOG");
    }
    private CrmProjectDto priceCatalog() throws Exception {
        var prices=Arrays.asList("1000.0000","1200.2500","0.0000",null,"999999999999999.9999","0.0001");
        var services=java.util.stream.IntStream.range(0,prices.size()).mapToObj(index -> catalogService("price-"+index)).toList();
        var offers=java.util.stream.IntStream.range(0,prices.size()).mapToObj(index -> new CrmServiceOffer(
                "m1",services.get(index).externalId(),prices.get(index)==null ? null : new BigDecimal(prices.get(index)),
                "RUB",600L,Map.of(),Map.of(),Set.of("price","durationSeconds"))).toList();
        provider.catalogReader=access -> catalogSnapshot(services,offers,CrmReadResult.Completeness.COMPLETE);
        var project=bind(connection()); awaitIdle(); return project;
    }
    private List<String> storedPrices(UUID projectId) {
        return jdbc.query("""
                select o.price::text from crm.masterservice o join crm.service s on s.id=o.serviceid
                where o.projectid=? order by s.externalid
                """,(rs,index) -> rs.getString(1),projectId);
    }
    @Test void catalogPricesAreStoredWithoutPaddingAndKeepExactFractionalValues() throws Exception {
        var project=priceCatalog();
        var expected=Arrays.asList("1000","1200.25","0",null,"999999999999999.9999","0.0001");
        assertEquals(expected,storedPrices(project.id()),"Check PostgreSQL price::text, not UI formatting");
        var before=catalog.services(project.id(),null,null,false,null);
        assertEquals(expected,before.items().stream().map(row -> row.offers().getFirst().price()).toList());
        // Older application instances can still write padded numbers to an unconstrained numeric column.
        jdbc.update("update crm.masterservice set price=price::numeric(19,4) where projectid=?",project.id());
        assertEquals("1000.0000",storedPrices(project.id()).getFirst());
        storage.initialize(); assertEquals(expected,storedPrices(project.id()));
        long generation=sync.state(project.id()).generation();
        sync.refresh(project.id());
        awaitCondition(() -> { var state=sync.state(project.id()); return state.generation()>generation && "SUCCEEDED".equals(state.status()); });
        awaitIdle();
        assertEquals(expected,storedPrices(project.id()));
        var after=catalog.services(project.id(),null,null,false,null);
        assertEquals(before.items().stream().map(row -> row.offers().getFirst().id()).toList(),
                after.items().stream().map(row -> row.offers().getFirst().id()).toList());
    }
    @Test void priceStorageUpgradeAndHibernateRestartPreserveValuesObservationsAndMappings() throws Exception {
        var project=priceCatalog(); var master=first(project.id());
        service.linkEmployee(master.id(),target,master.version());
        jdbc.execute("alter table crm.masterservice alter column price type numeric(19,4)");
        assertEquals("1000.0000",storedPrices(project.id()).getFirst());
        var observations=jdbc.queryForList("select id,version,createdat,updatedat,lastobservedat,lastobservedgeneration,currency from crm.masterservice order by id");
        var mappings=jdbc.queryForList("select id,version,employeeid,mappingupdatedat,mappingupdatedby,mappingpreviousid,mappingreason from crm.master order by id");
        var states=jdbc.queryForList("select * from crm.syncstate order by id");
        sync.close(); assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(sync,"executor")).awaitTermination(3,TimeUnit.SECONDS));
        factory.close(); StandardServiceRegistryBuilder.destroy(hibernate); factory=buildFactory();
        new CrmStorage(jdbc).initialize();
        var expected=Arrays.asList("1000","1200.25","0",null,"999999999999999.9999","0.0001");
        assertEquals(expected,storedPrices(project.id()));
        assertNull(jdbc.queryForObject("select numeric_scale from information_schema.columns where table_schema='crm' and table_name='masterservice' and column_name='price'",Integer.class));
        // A later ddl-auto=update must not put numeric(19,4) and its padding back.
        factory.close(); StandardServiceRegistryBuilder.destroy(hibernate); factory=buildFactory();
        assertNull(jdbc.queryForObject("select numeric_scale from information_schema.columns where table_schema='crm' and table_name='masterservice' and column_name='price'",Integer.class));
        new CrmStorage(jdbc).initialize();
        assertEquals(expected,storedPrices(project.id()));
        assertEquals(observations,jdbc.queryForList("select id,version,createdat,updatedat,lastobservedat,lastobservedgeneration,currency from crm.masterservice order by id"));
        assertEquals(mappings,jdbc.queryForList("select id,version,employeeid,mappingupdatedat,mappingupdatedby,mappingpreviousid,mappingreason from crm.master order by id"));
        assertEquals(states,jdbc.queryForList("select * from crm.syncstate order by id"));
    }
    @Test void serviceOffersAreIdempotentMasterSpecificAndPreserveEmployeeLinks() throws Exception {
        provider.catalogReader = access -> catalogSnapshot(List.of(catalogService("s1"), catalogService("s2")),
                List.of(offer("m1","s1","1200.25",1800), offer("m2","s1","1500",2400), offer("m1","s2","700",900)),
                CrmReadResult.Completeness.COMPLETE);
        var project = bind(connection()); awaitIdle();
        var master = first(project.id()); service.linkEmployee(master.id(), target, master.version());
        var before = catalog.services(project.id(), null, null, false, null);
        assertEquals(2, before.items().size()); assertEquals("CATALOG", before.dataState().dataset());
        assertEquals("CURRENT", before.dataState().freshness());
        var shared = before.items().getFirst();
        assertEquals(2, shared.offers().size());
        assertEquals(Set.of("1200.25", "1500"),
                shared.offers().stream().map(CrmServiceOfferDto::price).collect(java.util.stream.Collectors.toSet()));
        assertTrue(shared.offers().stream().allMatch(row -> row.currency().equals("RUB") && row.warnings().isEmpty()));
        sync.refresh(project.id(), "CATALOG"); awaitIdle();
        assertEquals(2, jdbc.queryForObject("select count(*) from crm.service", Integer.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from crm.masterservice", Integer.class));
        assertEquals(shared.id(), catalog.services(project.id(), null, shared.id(), false, null).items().getFirst().id());
        assertEquals(target, first(project.id()).employeeId()); assertEquals("LINK", first(project.id()).mappingReason());
        actor.set(member);
        var filtered = catalog.services(project.id(), master.id(), null, false, null);
        assertEquals(2, filtered.items().size()); assertTrue(filtered.items().stream().allMatch(row -> row.offers().size()==1));
        assertTrue(filtered.items().stream().flatMap(row -> row.offers().stream()).allMatch(row -> row.masterId().equals(master.id())));
    }
    @Test void completeCatalogRetainsMissingRowsAndLinksAndReusesThemOnReturn() throws Exception {
        var full = catalogSnapshot(List.of(catalogService("s1"), catalogService("s2")),
                List.of(offer("m1","s1","100",600), offer("m2","s2","200",1200)), CrmReadResult.Completeness.COMPLETE);
        provider.catalogReader = access -> full;
        var project = bind(connection()); awaitIdle();
        var second = service.masters(project.id(), null).items().getLast();
        service.linkEmployee(second.id(), member, second.version());
        UUID serviceId = catalog.services(project.id(), null, null, false, null).items().getLast().id();
        provider.catalogReader = access -> new CrmCatalogSnapshot(List.of(full.masters().getFirst()), List.of(catalogService("s1")),
                List.of(offer("m1","s1","100",600)), Instant.now(), CrmReadResult.Completeness.COMPLETE, "100:CATALOG");
        sync.refresh(project.id()); awaitIdle();
        assertEquals(1, catalog.services(project.id(), null, null, false, null).items().size());
        var retained = catalog.services(project.id(), null, serviceId, true, null).items().getFirst();
        assertEquals("NOT_OBSERVED", retained.observationState()); assertEquals("NOT_OBSERVED", retained.offers().getFirst().observationState());
        var retainedMaster = service.masters(project.id(), null).items().getLast();
        assertEquals("NOT_OBSERVED", retainedMaster.observationState()); assertEquals(member, retainedMaster.employeeId());
        provider.catalogReader = access -> full;
        sync.refresh(project.id()); awaitIdle();
        assertEquals(serviceId, catalog.services(project.id(), null, serviceId, false, null).items().getFirst().id());
        assertEquals("OBSERVED", service.masters(project.id(), null).items().getLast().observationState());
        assertEquals(member, service.masters(project.id(), null).items().getLast().employeeId());
    }
    @Test void partialCatalogDoesNotClearMissingFieldsOrUnobservedOffers() throws Exception {
        provider.catalogReader = access -> catalogSnapshot(List.of(catalogService("s1"), catalogService("s2")),
                List.of(offer("m1","s1","100",600), offer("m2","s2","200",1200)), CrmReadResult.Completeness.COMPLETE);
        var project = bind(connection()); awaitIdle();
        provider.catalogReader = access -> catalogSnapshot(List.of(catalogService("s1")),
                List.of(new CrmServiceOffer("m1","s1",null,null,null,Map.of(),Map.of(),Set.of())), CrmReadResult.Completeness.PARTIAL);
        sync.refresh(project.id()); awaitIdle();
        var page = catalog.services(project.id(), null, null, false, null);
        assertEquals(2, page.items().size()); assertEquals("PARTIAL", page.dataState().status());
        assertEquals("STALE", page.dataState().freshness());
        var retained = page.items().getFirst().offers().getFirst();
        assertEquals("100", retained.price()); assertEquals(600L, retained.durationSeconds());
        assertTrue(retained.warnings().containsAll(List.of("PRICE_NOT_OBSERVED","DURATION_NOT_OBSERVED","ACTIVE_NOT_OBSERVED","ONLINE_NOT_OBSERVED")));
        assertEquals(true, retained.offerState().get("active"));
        provider.catalogReader = access -> catalogSnapshot(List.of(catalogService("s1")),
                List.of(new CrmServiceOffer("m1","s1",null,null,null,Map.of(),Map.of(),Set.of("price","durationSeconds"))),
                CrmReadResult.Completeness.PARTIAL);
        sync.refresh(project.id()); awaitIdle();
        var cleared = catalog.services(project.id(), null, null, false, null).items().getFirst().offers().getFirst();
        assertNull(cleared.price()); assertNull(cleared.durationSeconds());
        assertTrue(cleared.warnings().contains("PRICE_UNKNOWN")); assertFalse(cleared.warnings().contains("PRICE_NOT_OBSERVED"));
    }
    @Test void invalidCatalogReferenceCannotApplyAnyOfTheSnapshot() throws Exception {
        var project = bind(connection()); awaitIdle();
        var before = sync.state(project.id());
        provider.catalogReader = access -> catalogSnapshot(List.of(catalogService("s1")),
                List.of(offer("missing-master","s1","100",600)), CrmReadResult.Completeness.COMPLETE);
        sync.refresh(project.id()); awaitIdle();
        assertEquals("CRM_INVALID_RESPONSE", sync.state(project.id()).errorCode());
        assertEquals(before.lastSuccessAt(), sync.state(project.id()).lastSuccessAt());
        assertEquals("initial one", first(project.id()).displayName());
        assertEquals(0, jdbc.queryForObject("select count(*) from crm.service", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from crm.masterservice", Integer.class));
    }
    @Test void serviceCursorIsBoundToFiltersAndCatalogRevision() throws Exception {
        var source = java.util.stream.IntStream.range(0, 101).mapToObj(i -> catalogService("s"+i)).toList();
        var offers = source.stream().map(row -> offer("m1", row.externalId(), "100",600)).toList();
        provider.catalogReader = access -> catalogSnapshot(source, offers, CrmReadResult.Completeness.COMPLETE);
        var project = bind(connection()); awaitIdle();
        var first = catalog.services(project.id(), null, null, false, null);
        assertEquals(100, first.items().size()); assertTrue(first.hasMore()); assertNotNull(first.nextCursor());
        var second = catalog.services(project.id(), null, null, false, first.nextCursor());
        assertEquals(1, second.items().size()); assertFalse(second.hasMore());
        assertTrue(first.items().stream().noneMatch(row -> row.id().equals(second.items().getFirst().id())));
        assertEquals("INVALID_REQUEST", assertThrows(CrmException.class,
                () -> catalog.services(project.id(), null, null, true, first.nextCursor())).code());
        sync.refresh(project.id()); awaitIdle();
        assertEquals("CRM_CONTEXT_CHANGED", assertThrows(CrmException.class,
                () -> catalog.services(project.id(), null, null, false, first.nextCursor())).code());
    }
    @Test void legacyMastersSuccessDoesNotClaimThatServicesHaveBeenLoaded() throws Exception {
        var project = bind(connection()); awaitIdle();
        jdbc.update("update crm.syncstate set dataset='MASTERS' where projectid=? and dataset='CATALOG'", project.id());
        assertEquals("SUCCEEDED", sync.state(project.id()).status());
        assertEquals("NEVER_LOADED", catalog.services(project.id(), null, null, false, null).dataState().freshness());
        sync.refresh(project.id(), "CATALOG"); awaitIdle();
        assertEquals("SUCCEEDED", catalog.services(project.id(), null, null, false, null).dataState().status());
    }
    @Test void masterCursorRejectsAnotherCatalogSnapshot() throws Exception {
        var initial = java.util.stream.IntStream.rangeClosed(100,200)
                .mapToObj(i -> new CrmMaster("m"+i, "Original "+i, Map.of())).toList();
        provider.catalogReader = access -> new CrmCatalogSnapshot(initial, List.of(), List.of(), Instant.now(),
                CrmReadResult.Completeness.COMPLETE, "100:CATALOG");
        var project = bind(connection()); awaitIdle();
        var first = service.masters(project.id(), null);
        var second = service.masters(project.id(), first.nextCursor());
        assertEquals(100, first.items().size()); assertEquals(1, second.items().size()); assertFalse(second.hasMore());
        assertTrue(first.items().stream().noneMatch(row -> row.id().equals(second.items().getFirst().id())));
        var updated = new ArrayList<>(initial); updated.add(new CrmMaster("m000", "Inserted before page one", Map.of()));
        provider.catalogReader = access -> new CrmCatalogSnapshot(updated, List.of(), List.of(), Instant.now(),
                CrmReadResult.Completeness.COMPLETE, "100:CATALOG");
        sync.refresh(project.id()); awaitIdle();
        assertEquals("CRM_CONTEXT_CHANGED", assertThrows(CrmException.class,
                () -> service.masters(project.id(), first.nextCursor())).code());
        assertEquals("m000", service.masters(project.id(), null).items().getFirst().externalId());
    }
    private CrmProjectDto manyMasters() throws Exception {
        provider.catalogReader=access -> new CrmCatalogSnapshot(java.util.stream.IntStream.range(0,102)
                .mapToObj(index -> new CrmMaster(String.format("m%03d",index),"Master "+index,Map.of())).toList(),
                List.of(),List.of(),Instant.now(),CrmReadResult.Completeness.COMPLETE,"100:CATALOG");
        var project=bind(connection()); awaitIdle(); return project;
    }
    @Test void masterMappingChangesInvalidateFilteredCursorsWithoutAdvancingSourceObservation() throws Exception {
        var project=manyMasters(); var master=first(project.id());
        var linked=service.linkEmployee(master.id(),target,master.version());
        var page=service.masters(project.id(),null,null,true,true); assertTrue(page.hasMore());
        var observation=sync.state(project.id());
        service.linkEmployee(master.id(),null,linked.version());
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,
                () -> service.masters(project.id(),page.nextCursor(),null,true,true)).code());
        var fresh=service.masters(project.id(),null,null,true,true);
        assertEquals(master.id(),fresh.items().getFirst().id()); assertTrue(fresh.revision()>page.revision());
        assertEquals(observation.lastSuccessAt(),fresh.dataState().lastSuccessAt());
        assertEquals(observation.generation(),fresh.dataState().generation());
    }
    @Test void employeeDeletionAlsoInvalidatesFilteredMasterCursors() throws Exception {
        var project=manyMasters(); var master=first(project.id()); service.linkEmployee(master.id(),target,master.version());
        var page=service.masters(project.id(),null,null,true,true); assertTrue(page.hasMore());
        tx.executeWithoutResult(status -> { var employee=em.find(EmployeeEntity.class,target); deletion.employee(employee); em.remove(employee); });
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,
                () -> service.masters(project.id(),page.nextCursor(),null,true,true)).code());
        assertNull(service.master(master.id()).employeeId());
    }
    @Test void legacyMasterMappingsHaveARevisionWithoutPretendingToLoadTheCatalog() throws Exception {
        var project=manyMasters();
        jdbc.update("update crm.syncstate set dataset='MASTERS' where projectid=? and dataset='CATALOG'",project.id());
        var master=first(project.id()); var page=service.masters(project.id(),null);
        service.linkEmployee(master.id(),target,master.version());
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,() -> service.masters(project.id(),page.nextCursor())).code());
        assertEquals("NEVER_LOADED",catalog.services(project.id(),null,null,false,null).dataState().freshness());
    }
    @Test void legacyMasterObservationSurvivesTheFirstCatalogFailureAndIsReplacedBySuccess() throws Exception {
        var project = bind(connection()); awaitIdle();
        var master = first(project.id()); service.linkEmployee(master.id(), member, master.version());
        jdbc.update("update crm.syncstate set dataset='MASTERS' where projectid=? and dataset='CATALOG'", project.id());
        var legacy = sync.state(project.id());
        provider.catalogReader = access -> { throw new CrmFailure(CrmFailure.Code.INVALID_RESPONSE); };
        sync.refresh(project.id()); awaitIdle();
        var page = service.masters(project.id(), null);
        assertEquals(legacy.lastSuccessAt(), page.dataState().lastSuccessAt());
        assertEquals("STALE", page.dataState().freshness()); assertEquals("FAILED", page.dataState().status());
        assertEquals("COMPLETE", page.dataState().completeness()); assertEquals("CRM_INVALID_RESPONSE", page.dataState().errorCode());
        assertNotNull(page.dataState().lastAttemptAt()); assertTrue(page.dataState().generation() > legacy.generation());
        assertEquals(member, page.items().getFirst().employeeId());
        assertEquals("NEVER_LOADED", sync.state(project.id(), "CATALOG").freshness());
        assertEquals("NEVER_LOADED", sync.state(project.id(), "SERVICES").freshness());
        provider.catalogReader = null;
        sync.refresh(project.id()); awaitIdle();
        var current = sync.state(project.id());
        assertEquals("SUCCEEDED", current.status()); assertEquals("CURRENT", current.freshness()); assertNull(current.errorCode());
        assertTrue(current.lastSuccessAt().isAfter(legacy.lastSuccessAt()));
    }
    @Test void retainedMasterHistoryPaginatesPastFiveThousandWithoutDuplicatesOrGaps() throws Exception {
        var project = bind(connection()); awaitIdle();
        jdbc.update("""
                insert into crm.master(id,version,createdat,updatedat,projectid,externalid,displayname,profile,
                    lastobservedat,lastobservedgeneration,observationstate)
                select gen_random_uuid(),0,clock_timestamp(),clock_timestamp(),?,'history'||n,'Historical master','{}'::jsonb,
                    clock_timestamp(),0,'NOT_OBSERVED' from generate_series(1,5000) n
                """, project.id());
        Set<UUID> seen = new HashSet<>(); String cursor = null; int pages = 0, observed = 0;
        do {
            var page = service.masters(project.id(), cursor); pages++;
            assertTrue(pages <= 60, "Pagination must terminate");
            for (var row : page.items()) {
                assertTrue(seen.add(row.id()), "A master must not repeat between pages");
                if ("OBSERVED".equals(row.observationState())) observed++;
            }
            if (!page.hasMore()) { assertNull(page.nextCursor()); break; }
            assertNotNull(page.nextCursor()); assertNotEquals(cursor, page.nextCursor()); cursor = page.nextCursor();
        } while (true);
        assertEquals(5002, seen.size()); assertEquals(2, observed); assertEquals(51, pages);
    }
    @Test void catalogReadsEnforceWorkspaceAndProjectOfEachFilter() throws Exception {
        provider.catalogReader = access -> catalogSnapshot(List.of(catalogService("s1")),
                List.of(offer("m1","s1","100",600)), CrmReadResult.Completeness.COMPLETE);
        var project = bind(connection()); awaitIdle();
        UUID masterId = first(project.id()).id();
        UUID serviceId = catalog.services(project.id(), null, null, false, null).items().getFirst().id();
        UUID secondWorkspace = tx.execute(status -> {
            var ws = new WorkspaceEntity(em.getReference(OrganizationEntity.class, organization), "second"); em.persist(ws); return ws.getId();
        });
        provider.catalogReader = access -> new CrmCatalogSnapshot(List.of(), List.of(), List.of(), Instant.now(),
                CrmReadResult.Completeness.COMPLETE, "200:CATALOG");
        var secondProject = service.bind(secondWorkspace, new BindCrmProjectRequest(project.connectionId(), "200", null)).project(); awaitIdle();
        assertEquals(404, assertThrows(CrmException.class,
                () -> catalog.services(secondProject.id(), masterId, null, false, null)).status());
        assertEquals(404, assertThrows(CrmException.class,
                () -> catalog.services(secondProject.id(), null, serviceId, false, null)).status());
        actor.set(member);
        assertFalse(catalog.services(project.id(), null, null, false, null).items().isEmpty());
        assertThrows(AccessDeniedException.class, () -> catalog.services(secondProject.id(), null, null, false, null));
        assertEquals(1, jdbc.queryForObject("select count(*) from crm.service", Integer.class));
    }
    @Test void mismatchedCatalogScopePreservesRowsAndLastSuccess() throws Exception {
        var project = bind(connection()); awaitIdle();
        var before = sync.state(project.id());
        provider.reader = access -> new CrmReadResult<>(FakeProvider.masters("wrong").items(), Instant.now(),
                CrmReadResult.Completeness.COMPLETE, "other:MASTERS");
        sync.refresh(project.id()); awaitIdle();
        assertEquals("initial one", first(project.id()).displayName());
        assertEquals(before.lastSuccessAt(), sync.state(project.id()).lastSuccessAt());
        assertEquals("CRM_CONTEXT_MISMATCH", sync.state(project.id()).errorCode());
    }
    @Test void partialCatalogDoesNotClaimCompletenessOrRemoveUnobservedMasters() throws Exception {
        var project = bind(connection()); awaitIdle();
        provider.reader = access -> new CrmReadResult<>(List.of(new CrmMaster("m1", "updated one", Map.of())),
                Instant.now(), CrmReadResult.Completeness.PARTIAL, "100:MASTERS");
        sync.refresh(project.id()); awaitIdle();
        var result = service.masters(project.id(), null);
        assertEquals(List.of("updated one", "initial two"), result.items().stream().map(CrmMasterDto::displayName).toList());
        assertEquals("PARTIAL", result.dataState().status());
        assertEquals("PARTIAL", result.dataState().completeness());
        assertEquals("STALE", result.dataState().freshness());
    }
    @Test void duplicateRefreshCoalescesAndCredentialChangeDiscardsLateRows() throws Exception {
        provider.reader = access -> provider.block("old");
        var connection = connection(); var project = bind(connection);
        assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
        long generation = sync.state(project.id()).generation();
        assertEquals(generation, sync.refresh(project.id()).generation()); assertEquals(1, provider.reads.get());
        var current = service.connection(connection.id());
        service.updateConnection(connection.id(), new UpdateCrmConnectionRequest(current.version(),null,"changed-test-secret",null));
        provider.release.countDown(); awaitIdle();
        assertTrue(service.masters(project.id(), null).items().isEmpty());
        assertEquals("NEVER_LOADED", sync.state(project.id()).freshness());
        assertEquals("CRM_CONTEXT_CHANGED", sync.state(project.id()).errorCode());
    }
    @Test void rebindWithSameCredentialVersionUsesConnectionIdentityAndRejectsOldRead() throws Exception {
        var old = connection();
        provider.reader = access -> access.connectionId().equals(old.id()) ? provider.block("old") : FakeProvider.masters("new");
        var project = bind(old); assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
        var next = connection();
        var rebound = service.changeConnection(project.id(), new ChangeCrmConnectionRequest(next.id(), project.version()));
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (!"SUCCEEDED".equals(sync.state(project.id()).status()) && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals("new one", first(project.id()).displayName());
        provider.release.countDown(); awaitIdle();
        assertEquals(project.id(), rebound.id()); assertEquals(next.id(), rebound.connectionId());
        assertEquals("new one", first(project.id()).displayName());
    }
    @Test void expiredLeaseCannotCommitAfterANewerClaim() throws Exception {
        provider.reader = access -> provider.reads.get() == 1 ? provider.block("expired") : FakeProvider.masters("fresh");
        var project = bind(connection()); assertTrue(provider.entered.await(2, TimeUnit.SECONDS));
        jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()-interval '1 second' where projectid=? and statekind='CONTROL'",project.id());
        sync.refresh(project.id());
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (!"SUCCEEDED".equals(sync.state(project.id()).status()) && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals("fresh one", first(project.id()).displayName());
        provider.release.countDown(); awaitIdle();
        assertEquals("fresh one", first(project.id()).displayName());
    }
    @Test void failedReadPreservesProfilesMappingsAndSuccessfulObservation() throws Exception {
        var project = bind(connection()); awaitIdle();
        var master = first(project.id()); service.linkEmployee(master.id(), target, master.version());
        Instant success = sync.state(project.id()).lastSuccessAt();
        provider.reader = access -> { throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE); };
        sync.refresh(project.id()); awaitIdle();
        assertEquals("FAILED", sync.state(project.id()).status());
        assertEquals("CRM_TEMPORARILY_UNAVAILABLE", sync.state(project.id()).errorCode());
        assertEquals(success, sync.state(project.id()).lastSuccessAt()); assertEquals("STALE", sync.state(project.id()).freshness());
        assertEquals(target, first(project.id()).employeeId());
    }
    @Test void currentDatabaseRolesAndWorkspaceScopesControlEveryAction() throws Exception {
        var connection = connection(); var project = bind(connection); awaitIdle(); var master = first(project.id());
        int inspected = provider.inspections.get();
        assertThrows(AccessDeniedException.class, () -> service.bind(otherWorkspace,new BindCrmProjectRequest(connection.id(),"300",null)));
        assertEquals(inspected, provider.inspections.get());
        actor.set(member);
        assertThrows(AccessDeniedException.class, service::connections);
        assertEquals(1, service.projects(workspace,null).size());
        assertThrows(AccessDeniedException.class, () -> service.linkEmployee(master.id(),target,master.version()));
        jdbc.update("update dbo.employeeworkspace set issupervisor=true where employeeid=? and workspaceid=?",member,workspace);
        assertEquals(member, service.linkEmployee(master.id(),member,master.version()).employeeId());
        jdbc.update("update dbo.users set isenabled=false where userid=(select userid from dbo.employee where employeeid=?)",member);
        assertThrows(AccessDeniedException.class, () -> service.projects(workspace,null));
    }
    @Test void removedEmployeeLinkIsAuditedAndRollsBackWithCoreDelete() throws Exception {
        var project = bind(connection()); awaitIdle(); var master = first(project.id());
        service.linkEmployee(master.id(),target,master.version());
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            deletion.employee(em.find(EmployeeEntity.class,target)); throw new IllegalStateException("core rule failed");
        }));
        assertEquals(target, first(project.id()).employeeId());
        tx.executeWithoutResult(status -> {
            var employee = em.find(EmployeeEntity.class,target); deletion.employee(employee); em.remove(employee);
        });
        var detached = first(project.id());
        assertNull(detached.employeeId()); assertEquals(target,detached.mappingPreviousId());
        assertEquals("TARGET_DELETED", detached.mappingReason());
        assertEquals(0, jdbc.queryForObject("select count(*) from dbo.employee where employeeid=?",Integer.class,target));
        assertEquals(2,service.masters(project.id(),null).items().size());
    }
    @Test void authorReferencesAndEvenDisabledProjectProtectCoreDeletes() throws Exception {
        var project = bind(connection()); awaitIdle();
        var master = first(project.id()); service.linkEmployee(master.id(),target,master.version());
        assertEquals("CRM_RESOURCE_IN_USE", assertThrows(CrmException.class, () -> tx.executeWithoutResult(
                status -> deletion.employee(em.find(EmployeeEntity.class,admin)))).code());
        service.updateProject(project.id(), new UpdateCrmProjectRequest(project.version(),false,null));
        assertEquals("CRM_RESOURCE_IN_USE", assertThrows(CrmException.class, () -> tx.executeWithoutResult(
                status -> deletion.workspace(em.find(WorkspaceEntity.class,workspace)))).code());
    }
    @Test void hibernateUpdatePreservesExistingDboAndCrmAndNamesHaveNoUnderscores() throws Exception {
        var connection = connection(); var project = bind(connection); awaitIdle();
        var mapped = first(project.id()); service.linkEmployee(mapped.id(), target, mapped.version());
        assertEquals(Set.of("connection","project","master","service","masterservice","syncstate","client","workinterval","appointment","appointmentservice","appointmentmaster"),
                new HashSet<>(jdbc.queryForList("select tablename from pg_tables where schemaname='crm'",String.class)));
        for (String sql : List.of("select column_name from information_schema.columns where table_schema='crm'",
                "select indexname from pg_indexes where schemaname='crm'",
                "select c.conname from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='crm'"))
            assertTrue(jdbc.queryForList(sql,String.class).stream().noneMatch(name -> name.contains("_")));
        sync.close(); assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(sync,"executor")).awaitTermination(3,TimeUnit.SECONDS));
        factory.close(); StandardServiceRegistryBuilder.destroy(hibernate);
        // Reproduce the deployed 12.3 shape before allowing Hibernate to add 12.4.1.
        jdbc.execute("drop table crm.appointmentservice"); jdbc.execute("drop table crm.appointmentmaster"); jdbc.execute("drop table crm.appointment");
        jdbc.execute("alter table crm.connection drop column healthgeneration, drop column healthappliedgeneration");
        jdbc.execute("drop table crm.masterservice"); jdbc.execute("drop table crm.service");
        jdbc.execute("drop table crm.client");
        jdbc.execute("drop table crm.workinterval");
        jdbc.execute("alter table crm.master drop column lastobservedgeneration, drop column observationstate");
        assertEquals(4, jdbc.queryForObject("select count(*) from pg_tables where schemaname='crm'", Integer.class));
        factory = buildFactory();
        new CrmStorage(jdbc).initialize();
        assertEquals(0L,jdbc.queryForObject("select healthgeneration from crm.connection where id=?",Long.class,connection.id()));
        assertEquals(0L,jdbc.queryForObject("select healthappliedgeneration from crm.connection where id=?",Long.class,connection.id()));
        assertEquals(1,jdbc.queryForObject("select count(*) from crm.project where id=?",Integer.class,project.id()));
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.workspace where workspaceid=?",Integer.class,workspace));
        assertEquals(target, jdbc.queryForObject("select employeeid from crm.master where id=?", UUID.class, mapped.id()));
        assertEquals("initial one", jdbc.queryForObject("select displayname from crm.master where id=?", String.class, mapped.id()));
        assertEquals(11, jdbc.queryForObject("select count(*) from pg_tables where schemaname='crm'", Integer.class));
    }
    private static CrmClient externalClient(String id, String name) {
        return new CrmClient(id,"PROJECT",name,Map.of("phone","+7 (999) 123-45-67"),Map.of("source","R08"),Set.of("displayName","phone"));
    }
    private static CrmClientPage clientPage(List<CrmClient> items, String next) {
        return new CrmClientPage(items,next,Instant.now(),CrmReadResult.Completeness.UNKNOWN,"100:CLIENTS");
    }
    private void awaitClients() throws Exception {
        var executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(clientSync,"executor");
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (executor.getActiveCount()>0 && System.nanoTime()<until) Thread.sleep(10);
        assertEquals(0,executor.getActiveCount());
    }
    private CrmClientDto importClient() throws Exception {
        var project=bind(connection()); awaitIdle();
        provider.clientReader=cursor -> clientPage(List.of(externalClient("1","External name")),null);
        clientSync.refresh(project.id()); awaitClients();
        assertEquals("PARTIAL",clientSync.state(project.id()).status());
        return clients.clients(project.id(),null,false,null).items().getFirst();
    }
    private UUID coreClient(String name, String phone) {
        return tx.execute(status -> {
            var row=new ClientEntity(name,"Surname",em.getReference(OrganizationEntity.class,organization));
            row.setPhoneList(new ArrayList<>(List.of(phone))); em.persist(row); return row.getId();
        });
    }
    @Test void clientsImportPagesAndFailurePreservesEarlierRowsWithoutClaimingCompleteness() throws Exception {
        var project=bind(connection()); awaitIdle();
        provider.clientReader=cursor -> {
            if (cursor==null) return clientPage(List.of(externalClient("1","First")),"second");
            throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
        };
        clientSync.refresh(project.id()); awaitClients();
        var page=clients.clients(project.id(),null,false,null);
        assertEquals(1,page.items().size()); assertEquals("FAILED",page.syncState().status());
        assertEquals("PARTIAL",page.syncState().completeness()); assertNotNull(page.syncState().lastSuccessAt());
        provider.clientReader=cursor -> clientPage(List.of(externalClient("2","Second")),null);
        clientSync.refresh(project.id()); awaitClients();
        assertEquals(2,clients.clients(project.id(),null,false,null).items().size());
        assertEquals("PARTIAL",clientSync.state(project.id()).status());
    }
    @Test void employeeCanConfirmFirstLinkButOnlySupervisorCanReplaceOrRemoveIt() throws Exception {
        var row=importClient(); var first=coreClient("First","79991234567"); var second=coreClient("Second","11111111111");
        actor.set(member);
        var linked=clients.link(row.id(),first,row.version()); assertEquals(first,linked.clientId());
        assertEquals(member,linked.mappingUpdatedBy());
        assertEquals(linked.version(),clients.link(row.id(),first,linked.version()).version());
        assertThrows(AccessDeniedException.class,()->clients.link(row.id(),second,linked.version()));
        assertThrows(AccessDeniedException.class,()->clients.link(row.id(),null,linked.version()));
        jdbc.update("update dbo.employeeworkspace set issupervisor=true where employeeid=? and workspaceid=?",member,workspace);
        var replaced=clients.link(row.id(),second,linked.version());
        assertEquals(first,replaced.mappingPreviousId()); assertEquals("RELINK",replaced.mappingReason());
        assertEquals("CRM_VERSION_CONFLICT",assertThrows(CrmException.class,()->clients.link(row.id(),first,linked.version())).code());
        var detached=clients.link(row.id(),null,replaced.version()); assertNull(detached.clientId());
        assertEquals(second,detached.mappingPreviousId()); assertEquals("UNLINK",detached.mappingReason());
        jdbc.update("delete from dbo.employeeworkspace where employeeid=?",member);
        assertThrows(AccessDeniedException.class,()->clients.client(row.id()));
        assertThrows(AccessDeniedException.class,()->clients.candidates(row.id(),"First"));
    }
    @Test void clientImportPreservesManualMappingCoreProfileAndMissingContactFields() throws Exception {
        var row=importClient(); var target=coreClient("Confirmed name","12345678901");
        var linked=clients.link(row.id(),target,row.version());
        Instant persistedMappingTime=clients.client(row.id()).mappingUpdatedAt();
        String longName="Long name ".repeat(200);
        provider.clientReader=cursor -> clientPage(List.of(new CrmClient("1","PROJECT",longName,Map.of(),Map.of(),Set.of("displayName"))),null);
        clientSync.refresh(row.projectId()); awaitClients();
        var updated=clients.client(row.id());
        assertEquals(target,updated.clientId()); assertEquals(persistedMappingTime,updated.mappingUpdatedAt());
        assertEquals(longName,updated.displayName()); assertEquals("79991234567",updated.normalizedPhone());
        assertEquals("Confirmed name",jdbc.queryForObject("select firstname from dbo.client where clientid=?",String.class,target));
        provider.clientReader=cursor -> clientPage(List.of(new CrmClient("1","PROJECT",null,Map.of("phone",""),Map.of(),Set.of("phone"))),null);
        clientSync.refresh(row.projectId()); awaitClients();
        assertEquals(longName,clients.client(row.id()).displayName()); assertNull(clients.client(row.id()).normalizedPhone());
    }
    @Test void candidatePhonesAreNormalizedButNeverAutomaticallyLinkedAndExactMatchesPrecedeLimit() throws Exception {
        var row=importClient(); var matched=coreClient("Match","+7 999 123 45 67");
        coreClient("Other country format","89991234567");
        for (int i=0;i<55;i++) coreClient("Match text "+i,"12345678901");
        assertEquals(List.of(matched),clients.candidates(row.id(),null).stream().map(CrmClientCandidateDto::id).toList());
        var candidates=clients.candidates(row.id(),"Match");
        assertEquals(50,candidates.size()); assertEquals(matched,candidates.getFirst().id()); assertTrue(candidates.getFirst().phoneMatch());
        assertNull(clients.client(row.id()).clientId());
    }
    @Test void concurrentCreateAndLinkHasOneWinnerAndNoOrphanClient() throws Exception {
        var row=importClient(); actor.set(member); var barrier=new CyclicBarrier(2);
        try (var workers=Executors.newFixedThreadPool(2)) {
            Callable<String> create=()-> {
                barrier.await(5,TimeUnit.SECONDS);
                try { clients.create(row.id(),new CreateCrmClientRequest("Confirmed","Name",List.of("12345678901"),row.version())); return "created"; }
                catch (CrmException error) { return error.code(); }
            };
            var one=workers.submit(create); var two=workers.submit(create);
            assertEquals(Set.of("created","CRM_VERSION_CONFLICT"),Set.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS)));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.client",Integer.class));
        assertEquals("CREATE",clients.client(row.id()).mappingReason());
    }
    @Test void candidatePhoneSearchNormalizesQueryIndependentlyOfExternalPhone() throws Exception {
        var row=importClient();
        var formatted=coreClient("Target","+7 (999) 765-43-21");
        var unicode=coreClient("Unicode","+7\u00a0(999)\u202f555-44-33");
        coreClient("Extension","+7(999)765-43-21x4");
        assertEquals(List.of(formatted),clients.candidates(row.id(),"79997654321").stream().map(CrmClientCandidateDto::id).toList());
        assertEquals(List.of(formatted),clients.candidates(row.id(),"+7\u202f999\u00a0765 43 21").stream().map(CrmClientCandidateDto::id).toList());
        assertEquals(List.of(unicode),clients.candidates(row.id(),"79995554433").stream().map(CrmClientCandidateDto::id).toList());
        assertFalse(clients.candidates(row.id(),"79997654321").getFirst().phoneMatch());
        jdbc.update("update crm.client set normalizedphone=null where id=?",row.id());
        assertEquals(List.of(formatted),clients.candidates(row.id(),"79997654321").stream().map(CrmClientCandidateDto::id).toList());
    }
    @Test void unicodeFormattingIsIdenticalForAutomaticSqlCandidatesAndPhoneMatchFlag() throws Exception {
        var row=importClient();
        var unicode=coreClient("Unicode","+7\u00a0(999)\u202f123-45-67");
        coreClient("Extension","+79991234567 ext 4");
        coreClient("Other prefix","89991234567");
        var candidates=clients.candidates(row.id(),null);
        assertEquals(List.of(unicode),candidates.stream().map(CrmClientCandidateDto::id).toList());
        assertTrue(candidates.getFirst().phoneMatch());
    }
    @Test void clientDeletionDetachesWithAuditAndCoreForeignKeyFailureRollsEverythingBack() throws Exception {
        var row=importClient(); var target=coreClient("Target","12345678901");
        clients.link(row.id(),target,row.version());
        tx.executeWithoutResult(status -> em.persist(new ClientAccountEntity(em.getReference(ClientEntity.class,target),ChannelType.TELEGRAM,"account","user","12345678901","Account")));
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(status -> {
            var core=em.find(ClientEntity.class,target); deletion.client(core); em.remove(core); em.flush();
        }));
        assertEquals(target,clients.client(row.id()).clientId());
        jdbc.update("delete from dbo.clientaccount where clientid=?",target);
        tx.executeWithoutResult(status -> { var core=em.find(ClientEntity.class,target); deletion.client(core); em.remove(core); });
        var detached=clients.client(row.id()); assertNull(detached.clientId()); assertEquals(target,detached.mappingPreviousId());
        assertEquals("TARGET_DELETED",detached.mappingReason()); assertEquals(admin,detached.mappingUpdatedBy());
        assertEquals("CRM_RESOURCE_IN_USE",assertThrows(CrmException.class,()->tx.executeWithoutResult(status -> deletion.employee(em.find(EmployeeEntity.class,admin)))).code());
    }
    @Test void clientCursorIsBoundToRevisionAndSearchAndNewMappingInvalidatesIt() throws Exception {
        var project=bind(connection()); awaitIdle(); var source=new ArrayList<CrmClient>();
        for (int i=0;i<105;i++) source.add(externalClient(Integer.toString(i),"Needle "+i));
        source.add(externalClient("literal","100%_literal!"));
        provider.clientReader=cursor -> clientPage(source,null);
        clientSync.refresh(project.id()); awaitClients();
        var page=clients.clients(project.id(),"NEEDLE",false,null); assertEquals(100,page.items().size()); assertTrue(page.hasMore());
        assertEquals(5,clients.clients(project.id(),"needle",false,page.nextCursor()).items().size());
        assertEquals(1,clients.clients(project.id(),"%_",false,null).items().size());
        assertThrows(CrmException.class,()->clients.clients(project.id(),null,false,page.nextCursor()));
        var row=page.items().getFirst(); clients.link(row.id(),coreClient("Core","12345678901"),row.version());
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,()->clients.clients(project.id(),"needle",false,page.nextCursor())).code());
        assertEquals(100,clients.clients(project.id(),"needle",true,null).items().size());
    }
    @Test void changedCredentialsDiscardClientPageAndDuplicateRefreshCoalesces() throws Exception {
        var connection=connection(); var project=bind(connection); awaitIdle(); var calls=new AtomicInteger();
        provider.clientReader=cursor -> { calls.incrementAndGet(); provider.block("wait"); return clientPage(List.of(externalClient("1","Late")),null); };
        clientSync.refresh(project.id()); assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        long generation=clientSync.state(project.id()).generation(); assertEquals(generation,clientSync.refresh(project.id()).generation());
        var current=service.connection(connection.id());
        service.updateConnection(connection.id(),new UpdateCrmConnectionRequest(current.version(),null,"changed-secret",null));
        provider.release.countDown(); awaitClients();
        assertEquals(1,calls.get()); assertTrue(clients.clients(project.id(),null,false,null).items().isEmpty());
        assertEquals("CRM_CONTEXT_CHANGED",clientSync.state(project.id()).errorCode());
    }
    @Test void repeatedClientCursorFailsAndImportLimitIsExplicit() throws Exception {
        var project=bind(connection()); awaitIdle(); var calls=new AtomicInteger();
        provider.clientReader=cursor -> clientPage(List.of(externalClient(Integer.toString(calls.incrementAndGet()),"Row")),"same");
        clientSync.refresh(project.id()); awaitClients();
        assertEquals(2,calls.get()); assertEquals("FAILED",clientSync.state(project.id()).status());
        calls.set(0);
        provider.clientReader=cursor -> { int i=calls.incrementAndGet(); return clientPage(List.of(externalClient(Integer.toString(i),"Row")),Integer.toString(i)); };
        clientSync.refresh(project.id()); awaitClients();
        assertEquals(100,calls.get()); assertEquals("PARTIAL",clientSync.state(project.id()).status());
        assertEquals("CRM_SYNC_LIMIT_REACHED",clientSync.state(project.id()).errorCode());
    }
    @Test void leaseExpiringDuringIntermediateClientPageRollsBackThatPage() throws Exception {
        var project=bind(connection()); awaitIdle();
        var expire=new AtomicBoolean(true);
        afterJpaCall=(method,args) -> {
            if (method.getName().equals("flush") && Thread.currentThread().getName().equals("crm-clients")
                    && expire.compareAndSet(true,false))
                jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()-interval '1 second' where projectid=? and dataset='WRITER:CLIENTS'",project.id());
        };
        provider.clientReader=cursor -> clientPage(List.of(externalClient("1","Must roll back")),"second");
        clientSync.refresh(project.id()); awaitClients();
        assertFalse(expire.get());
        assertTrue(clients.clients(project.id(),null,false,null).items().isEmpty());
        assertNotEquals("CURRENT",clientSync.state(project.id()).freshness());
    }
    @Test void timeLimitReachedDuringPageWriteKeepsRowsAndDoesNotReadAnotherPage() throws Exception {
        var project=bind(connection()); awaitIdle(); var calls=new AtomicInteger();
        provider.clientReader=cursor -> {
            calls.incrementAndGet(); clientNanoTime.set(TimeUnit.MINUTES.toNanos(5)-TimeUnit.SECONDS.toNanos(1));
            return clientPage(List.of(externalClient("1","Accepted page")),"second");
        };
        var advanceOnce=new AtomicBoolean(true);
        afterJpaCall=(method,args) -> {
            if (method.getName().equals("flush") && Thread.currentThread().getName().equals("crm-clients")
                    && advanceOnce.compareAndSet(true,false)) clientNanoTime.addAndGet(TimeUnit.SECONDS.toNanos(2));
        };
        clientSync.refresh(project.id()); awaitClients();
        assertFalse(advanceOnce.get()); assertEquals(1,calls.get());
        var page=clients.clients(project.id(),null,false,null);
        assertEquals(1,page.items().size()); assertEquals("PARTIAL",page.syncState().status());
        assertEquals("CRM_SYNC_LIMIT_REACHED",page.syncState().errorCode()); assertNotNull(page.syncState().lastSuccessAt());
        assertEquals(1,jdbc.queryForObject("select (attemptcoverage->>'pages')::int from crm.syncstate where projectid=? and dataset='CLIENTS'",Integer.class,project.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from crm.syncstate where projectid=? and claimtoken is not null",Integer.class,project.id()));
    }
    @Test void timeLimitReachedWhileAcquiringRenewalLocksDoesNotCallProvider() throws Exception {
        var project=bind(connection()); awaitIdle(); var calls=new AtomicInteger(); var refreshes=new AtomicInteger();
        provider.clientReader=cursor -> { calls.incrementAndGet(); return clientPage(List.of(),null); };
        afterJpaCall=(method,args) -> {
            if (method.getName().equals("refresh") && args[0] instanceof CrmProjectEntity
                    && Thread.currentThread().getName().equals("crm-clients") && refreshes.incrementAndGet()==2)
                clientNanoTime.set(TimeUnit.MINUTES.toNanos(5));
        };
        clientSync.refresh(project.id()); awaitClients();
        assertTrue(refreshes.get()>=2); assertEquals(0,calls.get());
        var state=clientSync.state(project.id()); assertEquals("PARTIAL",state.status());
        assertEquals("CRM_SYNC_LIMIT_REACHED",state.errorCode()); assertEquals("NEVER_LOADED",state.freshness());
        assertEquals(0,jdbc.queryForObject("select count(*) from crm.syncstate where projectid=? and claimtoken is not null",Integer.class,project.id()));
    }
    private static final LocalDate SCHEDULE_DAY=LocalDate.of(2026,11,1);
    private static CrmWorkInterval shift(String master,int day,int hour) {
        LocalDateTime start=SCHEDULE_DAY.plusDays(day).atTime(hour,0);
        return new CrmWorkInterval(master,start,start.plusHours(1),"WORK",null,Map.of("source","test"));
    }
    private void awaitSchedule() throws Exception {
        var executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(scheduleSync,"executor");
        // A SynchronousQueue handoff can return before the worker becomes active.
        awaitCondition(() -> jdbc.queryForObject("""
                select count(*) from crm.syncstate where dataset='WRITER:SCHEDULE'
                    and claimtoken is not null and claimuntil>clock_timestamp()
                """,Integer.class)==0 && executor.getActiveCount()==0);
    }
    private void loadSchedule(UUID project,UUID master,LocalDate from,LocalDate to,CrmReadResult.Completeness completeness,List<CrmWorkInterval> rows) throws Exception {
        provider.scheduleReader=query -> new CrmScheduleSnapshot(rows,Instant.now(),completeness,"100",query);
        scheduleSync.refresh(project,master,from,to); awaitSchedule();
    }
    private CrmWorkIntervalPageDto schedulePage(UUID project,UUID master,LocalDate from,LocalDate to) {
        return scheduleReader.intervals(project,master,from,to,null);
    }
    private void dueSchedule(UUID project,UUID master,LocalDate from,LocalDate to) {
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where projectid=? and dataset='WORK_INTERVALS' and scopekey=?",
                project,master+"/"+from+"/"+to);
    }
    @Test void periodicRefreshServesOldestProjectsAndOnlyOneScopePerProjectBeforeApplyingCapacity() throws Exception {
        var project=bind(connection()); awaitIdle();
        var requests=new ArrayList<>(List.of(first(project.id()),
                additionalProjectMaster(project,UUID.randomUUID(),"101"),additionalProjectMaster(project,UUID.randomUUID(),"102")));
        provider.scheduleReader=query -> new CrmScheduleSnapshot(List.of(),Instant.now(),CrmReadResult.Completeness.COMPLETE,
                query.externalMasterId().equals("m1") ? "100" : query.externalMasterId(),query);
        for (var request : requests) {
            scheduleSync.refresh(request.projectId(),request.id(),SCHEDULE_DAY,SCHEDULE_DAY); awaitSchedule();
        }
        requests.sort(Comparator.comparing(request -> request.projectId().toString()));
        var oldest=requests.getLast(); LocalDate anotherDay=SCHEDULE_DAY.plusDays(1);
        scheduleSync.refresh(oldest.projectId(),oldest.id(),anotherDay,anotherDay); awaitSchedule();
        long generation=scheduleSync.state(oldest.projectId(),oldest.id(),anotherDay,anotherDay).generation();
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where dataset='WORK_INTERVALS'");
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '1 day' where projectid=? and dataset='WORK_INTERVALS' and scopekey=?",
                oldest.projectId(),oldest.id()+"/"+anotherDay+"/"+anotherDay);
        assertEquals(2,scheduleSync.refreshDue(300000)); awaitSchedule();
        assertTrue(scheduleSync.state(oldest.projectId(),oldest.id(),anotherDay,anotherDay).generation()>generation,
                "The longest waiting project must be selected even when its UUID sorts last");
        assertEquals(1,scheduleSync.state(oldest.projectId(),oldest.id(),SCHEDULE_DAY,SCHEDULE_DAY).generation(),
                "The first pass must select at most one scope from each project");
        assertEquals(2,scheduleSync.refreshDue(300000)); awaitSchedule();
        for (var request : requests) assertTrue(scheduleSync.state(request.projectId(),request.id(),SCHEDULE_DAY,SCHEDULE_DAY).generation()>1,
                "Every due project must be served before repeating an already refreshed scope");
        assertEquals(0,scheduleSync.refreshDue(300000));
    }
    @Test void periodicRefreshUpdatesOverlappingPeriodsFromTheSourceAndPublishesAnInvalidation() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1),CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10),shift("m1",1,10)));
        loadSchedule(project.id(),master,SCHEDULE_DAY.plusDays(1),SCHEDULE_DAY.plusDays(1),CrmReadResult.Completeness.UNKNOWN,List.of(shift("m1",1,12)));
        // No extra stale marker: the next regular source read refreshes this saved period.
        var before=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        assertEquals("CURRENT",before.dataState().freshness()); assertEquals(3,before.items().size());
        assertEquals(0,scheduleSync.refreshDue(300000));
        dueSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        provider.scheduleReader=query -> new CrmScheduleSnapshot(List.of(shift("m1",0,10),shift("m1",1,12)),Instant.now(),
                CrmReadResult.Completeness.COMPLETE,"100",query);
        clearInvocations(events);
        assertEquals(1,scheduleSync.refreshDue(300000)); awaitSchedule();
        var current=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        assertEquals(2,current.items().size()); assertEquals(12,current.items().getLast().localStart().getHour());
        assertEquals("CURRENT",current.dataState().freshness()); assertTrue(current.dataState().generation()>before.dataState().generation());
        verify(events).workspace(any(),eq("crm.invalidated")); assertEquals(0,scheduleSync.refreshDue(300000));
        assertFalse(current.dataState().scope().containsKey("refreshActorId"));
    }
    @Test void periodicRefreshSurvivesRestartAndUsesSavedAccessWithoutRequestAuthentication() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10)));
        // Requests saved before automatic polling existed have no refresh actor yet.
        jdbc.update("update crm.syncstate set scope=scope-'refreshActorId' where projectid=? and dataset='WORK_INTERVALS'",project.id());
        scheduleSync.close(); assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(scheduleSync,"executor")).awaitTermination(5,TimeUnit.SECONDS));
        scheduleSync=new CrmScheduleSync(store,jdbc,tx.getTransactionManager(),new CrmRegistry(List.of(provider)),cipher,events,new CrmScheduleWriter(store));
        dueSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        provider.scheduleReader=query -> new CrmScheduleSnapshot(List.of(shift("m1",0,12)),Instant.now(),CrmReadResult.Completeness.COMPLETE,"100",query);
        actor.set(null);
        try { assertEquals(1,scheduleSync.refreshDue(300000)); awaitSchedule(); }
        finally { actor.set(admin); }
        assertEquals(12,schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).items().getFirst().localStart().getHour());
    }
    @Test void periodicRefreshSkipsRevokedActorsAndDisabledProjectsOrConnections() throws Exception {
        var connection=connection(); var project=bind(connection); awaitIdle(); UUID master=first(project.id()).id();
        actor.set(member);
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10)));
        actor.set(admin); dueSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY); int reads=provider.scheduleReads.get();
        jdbc.update("delete from dbo.employeeworkspace where employeeid=? and workspaceid=?",member,workspace);
        assertEquals(0,scheduleSync.refreshDue(300000)); assertEquals(reads,provider.scheduleReads.get());
        tx.executeWithoutResult(status -> em.persist(new EmployeeWorkspaceEntity(em.find(EmployeeEntity.class,member),em.find(WorkspaceEntity.class,workspace))));
        var disabled=service.updateProject(project.id(),new UpdateCrmProjectRequest(project.version(),false,null));
        assertEquals(0,scheduleSync.refreshDue(300000));
        service.updateProject(project.id(),new UpdateCrmProjectRequest(disabled.version(),true,null));
        var current=service.connection(connection.id());
        service.updateConnection(current.id(),new UpdateCrmConnectionRequest(current.version(),null,null,false));
        assertEquals(0,scheduleSync.refreshDue(300000)); assertEquals(reads,provider.scheduleReads.get());
    }
    @Test void partialPeriodicRefreshPreservesThePreviousSuccessfulCoverage() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10)));
        var previous=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        dueSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        provider.scheduleReader=query -> new CrmScheduleSnapshot(List.of(shift("m1",0,12)),Instant.now(),CrmReadResult.Completeness.UNKNOWN,"100",query);
        assertEquals(1,scheduleSync.refreshDue(300000)); awaitSchedule();
        var current=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(2,current.items().size()); assertEquals("PARTIAL",current.dataState().status());
        assertEquals(previous.dataState().successfulCoverage(),current.dataState().successfulCoverage());
    }
    @Test void partialScheduleRetainsOldRowsAndRepeatedImportKeepsIdsAndEmployeeLinks() throws Exception {
        var project=bind(connection()); awaitIdle(); var master=first(project.id());
        service.linkEmployee(master.id(),target,master.version());
        var first=shift("m1",0,10);
        loadSchedule(project.id(),master.id(),SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(first));
        var initial=schedulePage(project.id(),master.id(),SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals("CURRENT",initial.dataState().freshness()); assertEquals("COMPLETE",initial.dataState().completeness());
        loadSchedule(project.id(),master.id(),SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.UNKNOWN,List.of(first,shift("m1",0,12)));
        var partial=schedulePage(project.id(),master.id(),SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(2,partial.items().size()); assertEquals(initial.items().getFirst().id(),partial.items().getFirst().id());
        assertEquals("PARTIAL",partial.dataState().status()); assertEquals("UNKNOWN",partial.dataState().completeness());
        assertEquals(initial.dataState().successfulCoverage(),partial.dataState().successfulCoverage());
        assertEquals("2026-11-01",partial.dataState().scope().get("dateFrom"));
        loadSchedule(project.id(),master.id(),SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.UNKNOWN,List.of());
        assertEquals(2,schedulePage(project.id(),master.id(),SCHEDULE_DAY,SCHEDULE_DAY).items().size());
        assertEquals(target,service.master(master.id()).employeeId());
    }
    @Test void completeScheduleReplacesOnlyItsMasterAndStartDatesAndCanBeEmpty() throws Exception {
        var project=bind(connection()); awaitIdle(); var masters=service.masters(project.id(),null).items();
        UUID one=masters.getFirst().id(),two=masters.getLast().id();
        loadSchedule(project.id(),one,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1),CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10),shift("m1",1,10)));
        loadSchedule(project.id(),two,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m2",0,12)));
        tx.executeWithoutResult(status -> {
            var block=new CrmWorkIntervalEntity(); block.setProject(em.find(CrmProjectEntity.class,project.id()));
            block.setMaster(em.find(CrmMasterEntity.class,one)); block.setStartLocal(SCHEDULE_DAY.atTime(9,0));
            block.setEndLocal(SCHEDULE_DAY.atTime(10,0)); block.setKind("BLOCK"); block.setLastObservedAt(Instant.now());
            em.persist(block);
        });
        loadSchedule(project.id(),one,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of());
        var cleared=schedulePage(project.id(),one,SCHEDULE_DAY,SCHEDULE_DAY);
        assertTrue(cleared.items().isEmpty()); assertEquals("SUCCEEDED",cleared.dataState().status());
        assertEquals(1,schedulePage(project.id(),one,SCHEDULE_DAY.plusDays(1),SCHEDULE_DAY.plusDays(1)).items().size());
        assertEquals(1,schedulePage(project.id(),two,SCHEDULE_DAY,SCHEDULE_DAY).items().size());
        assertEquals(1,jdbc.queryForObject("select count(*) from crm.workinterval where kind='BLOCK'",Integer.class));
    }
    @Test void invalidOrFailedScheduleDoesNotChangeRowsOrSuccessfulCoverage() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        var row=shift("m1",0,10);
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(row));
        var original=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(1,original.items().size());
        provider.scheduleReader=query -> { throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE); };
        scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY); awaitSchedule();
        var failed=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals("FAILED",failed.dataState().status()); assertEquals(original.items(),failed.items());
        assertEquals(original.dataState().lastSuccessAt(),failed.dataState().lastSuccessAt());
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(row,row));
        var invalid=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals("CRM_INVALID_RESPONSE",invalid.dataState().errorCode()); assertEquals(original.items(),invalid.items());
        assertEquals(original.dataState().successfulCoverage(),invalid.dataState().successfulCoverage());
    }
    @Test void scheduleRefreshCoalescesOnlyTheSameScopeAndExpiredWorkerCannotOverwriteNewClaim() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        provider.scheduleReader=query -> {
            int call=provider.scheduleReads.get();
            if (call==1) provider.block("old schedule");
            return new CrmScheduleSnapshot(List.of(shift("m1",0,call==1 ? 10 : 12)),Instant.now(),CrmReadResult.Completeness.COMPLETE,"100",query);
        };
        var first=scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        assertEquals(first.generation(),scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).generation());
        assertEquals(1,provider.scheduleReads.get());
        dueSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(0,scheduleSync.refreshDue(300000));
        assertEquals(429,assertThrows(CrmException.class,() -> scheduleSync.refresh(project.id(),master,SCHEDULE_DAY.plusDays(1),SCHEDULE_DAY.plusDays(1))).status());
        jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()-interval '1 second' where projectid=? and dataset='WRITER:SCHEDULE'",project.id());
        assertEquals("CRM_REFRESH_EXPIRED",scheduleSync.state(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).errorCode());
        scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        awaitCondition(() -> "SUCCEEDED".equals(scheduleSync.state(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).status()));
        provider.release.countDown(); awaitSchedule();
        var current=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(1,current.items().size()); assertEquals(12,current.items().getFirst().localStart().getHour());
    }
    @Test void scheduleLeaseExpiringAfterFlushRollsBackTheWholeSnapshot() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10)));
        var previous=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(1,previous.items().size());
        var once=new AtomicBoolean(true);
        afterJpaCall=(method,args) -> {
            if (method.getName().equals("flush") && Thread.currentThread().getName().equals("crm-schedule") && once.compareAndSet(true,false))
                jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()-interval '1 second' where projectid=? and dataset='WRITER:SCHEDULE'",project.id());
        };
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,12)));
        assertFalse(once.get());
        var result=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(previous.items(),result.items()); assertEquals(previous.dataState().successfulCoverage(),result.dataState().successfulCoverage());
        assertEquals("FAILED",result.dataState().status());
    }
    @Test void timezoneChangeRejectsTheOldScheduleWithoutDestroyingSavedData() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10)));
        provider.scheduleReader=query -> { provider.block("old context"); return new CrmScheduleSnapshot(List.of(),Instant.now(),CrmReadResult.Completeness.COMPLETE,"100",query); };
        scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY); assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        var current=service.projects(workspace,null).getFirst();
        service.updateProject(project.id(),new UpdateCrmProjectRequest(current.version(),null,"Europe/Moscow"));
        provider.release.countDown(); awaitSchedule(); awaitIdle();
        var saved=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(1,saved.items().size()); assertEquals("STALE",saved.dataState().freshness());
        assertEquals("CRM_CONTEXT_CHANGED",saved.dataState().errorCode());
    }
    @Test void scheduleChecksCurrentWorkspaceAccessBeforeReadingAndBeforeApplying() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        actor.set(target);
        assertThrows(AccessDeniedException.class,() -> scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY));
        assertThrows(AccessDeniedException.class,() -> schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY));
        assertEquals(0,provider.scheduleReads.get());
        actor.set(member);
        provider.scheduleReader=query -> { provider.block("revoked"); return new CrmScheduleSnapshot(List.of(shift("m1",0,10)),Instant.now(),CrmReadResult.Completeness.COMPLETE,"100",query); };
        scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY); assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        jdbc.update("delete from dbo.employeeworkspace where employeeid=? and workspaceid=?",member,workspace);
        provider.release.countDown(); awaitSchedule(); actor.set(admin);
        assertTrue(schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).items().isEmpty());
        assertEquals("ACCESS_DENIED",scheduleSync.state(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).errorCode());
    }
    @Test void aDifferentConnectionWithTheSameCredentialVersionDiscardsAnOldSchedule() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10)));
        provider.scheduleReader=query -> { provider.block("old access"); return new CrmScheduleSnapshot(List.of(),Instant.now(),CrmReadResult.Completeness.COMPLETE,"100",query); };
        scheduleSync.refresh(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY); assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        var next=connection(); var current=service.projects(workspace,null).getFirst();
        service.changeConnection(project.id(),new ChangeCrmConnectionRequest(next.id(),current.version()));
        provider.release.countDown(); awaitSchedule(); awaitIdle();
        var page=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(1,page.items().size()); assertEquals("CRM_CONTEXT_CHANGED",page.dataState().errorCode());
    }
    @Test void scheduleResourceIdsCannotMixOrganizationsOrProjects() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        UUID otherAdmin=tx.execute(status -> employee(em.find(WorkspaceEntity.class,otherWorkspace).getOrganization(),UserRole.SUPER_ADMIN,"schedule-other-admin").getId());
        actor.set(otherAdmin);
        var otherProject=service.bind(otherWorkspace,new BindCrmProjectRequest(connection().id(),"100",null)).project(); awaitIdle();
        UUID otherMaster=first(otherProject.id()).id(); actor.set(admin);
        assertEquals(404,assertThrows(CrmException.class,() -> schedulePage(otherProject.id(),otherMaster,SCHEDULE_DAY,SCHEDULE_DAY)).status());
        assertEquals(404,assertThrows(CrmException.class,() -> scheduleSync.refresh(project.id(),otherMaster,SCHEDULE_DAY,SCHEDULE_DAY)).status());
        assertEquals(404,assertThrows(CrmException.class,() -> schedulePage(project.id(),otherMaster,SCHEDULE_DAY,SCHEDULE_DAY)).status());
        assertTrue(schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY).items().isEmpty());
        assertEquals(0,provider.scheduleReads.get());
    }
    @Test void scheduleCursorBindsMasterPeriodAndDomainRevisionAndHandlesBadDatesSafely() throws Exception {
        var project=bind(connection()); awaitIdle(); UUID master=first(project.id()).id();
        var rows=new ArrayList<CrmWorkInterval>();
        for (int i=0;i<101;i++) {
            var start=SCHEDULE_DAY.atTime(8,0).plusMinutes(i*3);
            rows.add(new CrmWorkInterval("m1",start,start.plusMinutes(1),"WORK",null,Map.of()));
        }
        loadSchedule(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,rows);
        var page=schedulePage(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY);
        assertEquals(100,page.items().size()); assertTrue(page.hasMore());
        var next=scheduleReader.intervals(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,page.nextCursor());
        assertEquals(1,next.items().size()); assertFalse(next.hasMore());
        assertNotEquals(page.items().getLast().id(),next.items().getFirst().id());
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,() -> scheduleReader.intervals(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1),page.nextCursor())).code());
        String[] values=new String(Base64.getUrlDecoder().decode(page.nextCursor()),java.nio.charset.StandardCharsets.UTF_8).split("\\|");
        for (String invalid : List.of("bad-date","+999999999-12-31T00:00")) {
            values[3]=invalid;
            String bad=Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|",values).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(400,assertThrows(CrmException.class,() -> scheduleReader.intervals(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,bad)).status());
        }
        loadSchedule(project.id(),master,SCHEDULE_DAY.plusDays(1),SCHEDULE_DAY.plusDays(1),CrmReadResult.Completeness.UNKNOWN,List.of());
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,() -> scheduleReader.intervals(project.id(),master,SCHEDULE_DAY,SCHEDULE_DAY,page.nextCursor())).code());
    }
    private static CrmAppointment appointment(String id,int day,int hour,String client) {
        var start=SCHEDULE_DAY.plusDays(day).atTime(hour,0);
        return new CrmAppointment(id,"APPOINTMENT",client==null ? null : new CrmClientReference("PROJECT",client),
                Map.of("name","Historical client"),start,start.plusHours(1),"SCHEDULED","CONFIRMED","NOT_RECORDED",
                Map.of("status_id","1"),"Original note",List.of(
                    new CrmAppointmentService("s1","l1","Historical service",3600L,new BigDecimal("1200.0000"),"RUB"),
                    new CrmAppointmentService("s2","l2","Additional service",1800L,null,null)),
                List.of(new CrmAppointmentMaster("m1","Historical master",start,start.plusHours(1),3600L,null,null),
                    new CrmAppointmentMaster("m2","Second master",start,start.plusMinutes(30),1800L,null,null)),
                true,true,Set.of("time","kind","client","comment","appointmentState","confirmationState","attendanceState"),"JOURNAL");
    }
    private static CrmAppointment details(CrmAppointment source,String sourceKind,List<CrmAppointmentService> services,
            List<CrmAppointmentMaster> masters,boolean complete,Set<String> fields) {
        return new CrmAppointment(source.externalId(),source.kind(),source.client(),source.clientSnapshot(),source.localStart(),source.localEnd(),
                source.appointmentState(),source.confirmationState(),source.attendanceState(),source.providerState(),source.comment(),services,masters,complete,complete,fields,sourceKind);
    }
    private static CrmAppointmentFilter appointmentFilter() {
        return new CrmAppointmentFilter(SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1),null,null,null,null,null,null,null);
    }
    private void awaitAppointments() throws Exception {
        var executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(appointmentSync,"executor");
        awaitCondition(() -> executor.getActiveCount()==0 && jdbc.queryForObject(
                "select count(*) from crm.syncstate where dataset='WRITER:APPOINTMENTS' and claimuntil>clock_timestamp()",Integer.class)==0);
    }
    private void loadAppointments(UUID project,CrmReadResult.Completeness completeness,List<CrmAppointment> rows) throws Exception {
        provider.appointmentsReader=request -> new CrmAppointmentPage(rows,null,Instant.now(),completeness,request.project(),request.query());
        appointmentSync.refresh(project,"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        var state=appointmentSync.state(project,"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        assertNull(state.errorCode(),state.toString());
    }
    private CrmAppointmentPageDto appointments(UUID project) { return appointmentReader.appointments(project,appointmentFilter(),null); }
    @Test void appointmentsKeepHistoricalTermsIndependentStatesStableIdsAndFinancialOwnership() throws Exception {
        var project=bind(connection()); awaitIdle();
        var source=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(source));
        var initial=appointments(project.id()).items().getFirst();
        assertEquals(2,initial.services().size()); assertEquals(2,initial.masters().size());
        assertEquals("1200",initial.services().getFirst().price()); assertNull(initial.services().getLast().price());
        assertEquals("Historical master",initial.masters().getFirst().displayName()); assertEquals("UNKNOWN",initial.paymentState());
        assertEquals("SCHEDULED",initial.appointmentState()); assertEquals("CONFIRMED",initial.confirmationState());
        assertNull(initial.startAt()); assertTrue(initial.warnings().contains("TIMEZONE_UNKNOWN"));
        jdbc.update("update crm.appointment set paymentstate='PAID',financialgeneration=7,financialsnapshot='{\"paid\":\"100\"}'::jsonb where id=?",initial.id());
        provider.catalogReader=access -> catalogSnapshot(List.of(catalogService("s1")),List.of(offer("m1","s1","999",60)),CrmReadResult.Completeness.COMPLETE);
        sync.refresh(project.id()); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(source));
        var current=appointments(project.id()).items().getFirst();
        assertEquals(initial.id(),current.id()); assertEquals(initial.services().getFirst().id(),current.services().getFirst().id());
        assertNotNull(current.services().getFirst().serviceId()); assertEquals("Historical service",current.services().getFirst().displayName());
        assertEquals("1200",current.services().getFirst().price()); assertEquals("PAID",current.paymentState());
        assertEquals(7L,jdbc.queryForObject("select financialgeneration from crm.appointment where id=?",Long.class,current.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from dbo.client",Integer.class));
    }
    @Test void appointmentCompleteScopeMarksMissingWithoutInventingCancellationAndUnknownRetainsIt() throws Exception {
        var project=bind(connection()); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("a1",0,10,"1"),appointment("a2",1,10,"2")));
        var before=appointments(project.id()); var oldCoverage=before.coverage().getFirst().scopes().getFirst().successfulCoverage();
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of());
        var partial=appointments(project.id()); assertTrue(partial.items().stream().allMatch(row -> "OBSERVED".equals(row.observationState())));
        assertEquals(oldCoverage,partial.coverage().getFirst().scopes().getFirst().successfulCoverage());
        provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY); awaitAppointments();
        var rows=appointments(project.id()).items();
        assertEquals("NOT_OBSERVED",rows.getFirst().observationState()); assertTrue(rows.getFirst().requiresVerification());
        assertEquals("SCHEDULED",rows.getFirst().appointmentState()); assertEquals("OBSERVED",rows.getLast().observationState());
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointment("a1",0,11,"1")));
        assertEquals("OBSERVED",appointments(project.id()).items().getFirst().observationState());
    }
    @Test void appointmentPageFailurePreservesAcceptedPagesAndDoesNotMarkMissing() throws Exception {
        var project=bind(connection()); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("old",0,9,"1")));
        var coverage=appointments(project.id()).coverage().getFirst().scopes().getFirst().successfulCoverage();
        provider.appointmentsReader=request -> {
            if (request.cursor()!=null) throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
            return new CrmAppointmentPage(List.of(appointment("new",0,10,"1")),"next",Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        };
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        var page=appointments(project.id()); assertEquals(2,page.items().size());
        assertTrue(page.items().stream().allMatch(row -> "OBSERVED".equals(row.observationState())));
        assertEquals("FAILED",page.coverage().getFirst().scopes().getFirst().status());
        assertEquals(coverage,page.coverage().getFirst().scopes().getFirst().successfulCoverage());
        assertEquals(1,page.coverage().getFirst().scopes().getFirst().attemptCoverage().get("pages"));
    }
    @Test void appointmentAllPagesMustBeCompleteBeforeAnyNegativeObservation() throws Exception {
        var project=bind(connection()); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("old",0,9,"1")));
        provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(),request.cursor()==null ? "next" : null,Instant.now(),
                request.cursor()==null ? CrmReadResult.Completeness.UNKNOWN : CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        assertEquals("OBSERVED",appointments(project.id()).items().getFirst().observationState());
        assertNotEquals("COMPLETE",appointments(project.id()).coverage().getFirst().scopes().getFirst().completeness());
        provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(),request.cursor()==null ? "next" : null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        assertEquals("NOT_OBSERVED",appointments(project.id()).items().getFirst().observationState());
    }
    @Test void appointmentChildrenPreserveOmittedFieldsClearExplicitNullAndHandleMasterPositionGaps() throws Exception {
        var project=bind(connection()); awaitIdle(); var original=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(original));
        var missing=new CrmAppointmentService("s1","l1","Renamed history",null,null,null,Set.of("displayName"));
        var oneMaster=List.of(original.masters().getLast());
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(details(original,"JOURNAL",List.of(missing),oneMaster,true,original.presentFields())));
        assertEquals("1200",appointments(project.id()).items().getFirst().services().getFirst().price());
        var extended=new ArrayList<>(oneMaster); extended.add(new CrmAppointmentMaster("m3","Third",null,null,null,null,null));
        var explicit=new CrmAppointmentService("s1","l1",null,null,null,null,Set.of("price"));
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(details(original,"JOURNAL",List.of(explicit),extended,true,original.presentFields())));
        var row=appointments(project.id()).items().getFirst(); assertEquals(2,row.masters().size());
        assertNull(row.services().getFirst().price()); assertEquals("Renamed history",row.services().getFirst().displayName());
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(details(original,"JOURNAL",List.of(),List.of(),false,Set.of("time"))));
        assertEquals(2,appointments(project.id()).items().getFirst().masters().size()); assertEquals(1,appointments(project.id()).items().getFirst().services().size());
        var partial=appointments(project.id()).items().getFirst();
        assertFalse(partial.servicesComplete()); assertFalse(partial.mastersComplete()); assertTrue(partial.requiresVerification());
        assertTrue(partial.warnings().containsAll(List.of("SERVICES_PARTIAL","MASTERS_PARTIAL")));
    }
    @Test void weakAppointmentHistoryCannotOverwriteTheAuthoritativeJournalOrRefreshItsObservation() throws Exception {
        var project=bind(connection()); awaitIdle(); var source=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(source));
        var initial=appointments(project.id()).items().getFirst();
        var weak=appointment("a1",0,12,"2");
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(details(weak,"HISTORY",List.of(),List.of(),true,weak.presentFields())));
        var current=appointments(project.id()).items().getFirst();
        assertEquals(initial.localStart(),current.localStart()); assertEquals(initial.externalClientId(),current.externalClientId());
        assertEquals(initial.lastObservedAt(),current.lastObservedAt()); assertEquals(initial.services(),current.services()); assertEquals("JOURNAL",current.source());
    }
    @Test void appointmentTimeZoneRevisionSuppressesOldUtcUntilRecalculationWithoutRefreshingSource() throws Exception {
        var project=bind(connection()); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("a1",0,10,"1")));
        var initial=appointments(project.id()).items().getFirst();
        service.updateProject(project.id(),new UpdateCrmProjectRequest(project.version(),null,"Europe/Moscow"));
        var pending=appointmentReader.appointment(initial.id()); assertNull(pending.startAt()); assertTrue(pending.warnings().contains("TIMEZONE_RECALC_REQUIRED"));
        assertEquals(1,appointmentTimes.recalculate());
        var current=appointmentReader.appointment(initial.id()); assertEquals(Instant.parse("2026-11-01T07:00:00Z"),current.startAt());
        assertEquals(initial.lastObservedAt(),current.lastObservedAt()); assertEquals(initial.localStart(),current.localStart());
        assertEquals(0,appointmentTimes.recalculate());
    }
    @Test void appointmentPartialClientNameDoesNotClearIdentityOrKnownKind() throws Exception {
        var project=bind(connection()); awaitIdle(); var source=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(source));
        var partial=new CrmAppointment("a1","UNKNOWN",null,Map.of("name","Updated history"),source.localStart(),source.localEnd(),
                "UNKNOWN","UNKNOWN","UNKNOWN",Map.of(),null,List.of(),List.of(),false,false,Set.of("time","clientName"),"JOURNAL");
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(partial));
        var row=appointments(project.id()).items().getFirst(); assertEquals("1",row.externalClientId()); assertEquals("APPOINTMENT",row.kind());
        assertEquals("Updated history",row.clientSnapshot().get("name")); assertEquals("SCHEDULED",row.appointmentState());
    }
    @Test void appointmentDstGapsAndOverlapsStayUnresolved() {
        assertNull(CrmAppointmentTimes.instant(LocalDateTime.parse("2026-03-29T02:30"),"Europe/Berlin"));
        assertNull(CrmAppointmentTimes.instant(LocalDateTime.parse("2026-10-25T02:30"),"Europe/Berlin"));
        assertEquals(Instant.parse("2026-10-25T02:30:00Z"),CrmAppointmentTimes.instant(LocalDateTime.parse("2026-10-25T03:30"),"Europe/Berlin"));
    }
    @Test void appointmentHistoryResolvesLateClientImportAndCurrentMappingWithoutCreatingCoreObjects() throws Exception {
        var project=bind(connection()); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointment("a1",0,10,"1")));
        var initial=appointments(project.id()).items().getFirst(); assertNull(initial.crmClientId());
        provider.clientReader=cursor -> clientPage(List.of(externalClient("1","Current client")),null);
        clientSync.refresh(project.id()); awaitClients();
        var imported=clients.clients(project.id(),null,false,null).items().getFirst();
        UUID core=coreClient("Core client","70000000000");
        var linked=clients.link(imported.id(),core,imported.version());
        var history=appointmentReader.history(core,null,appointmentFilter(),null);
        assertEquals(1,history.items().size()); assertEquals(initial.id(),history.items().getFirst().id());
        assertEquals(imported.id(),history.items().getFirst().crmClientId()); assertEquals("Historical client",history.items().getFirst().clientSnapshot().get("name"));
        clients.link(imported.id(),null,linked.version());
        assertTrue(appointmentReader.history(core,null,appointmentFilter(),null).items().isEmpty()); assertNull(appointmentReader.appointment(initial.id()).clientId());
    }
    @Test void appointmentHistoryCoverageAndRowsAreLimitedToAccessibleWorkspaces() throws Exception {
        var imported=importClient(); UUID core=coreClient("Core","70000000000"); clients.link(imported.id(),core,imported.version());
        var project=service.projects(workspace,null).getFirst();
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointment("a1",0,10,"1")));
        var other=additionalProjectMaster(project,UUID.randomUUID(),"101");
        tx.executeWithoutResult(status -> {
            var client=new CrmClientEntity(); client.setProject(em.find(CrmProjectEntity.class,other.projectId())); client.setIdentityKind("PROJECT"); client.setExternalId("1");
            client.setClient(em.find(ClientEntity.class,core)); client.setLastObservedAt(Instant.now()); em.persist(client);
        });
        loadAppointments(other.projectId(),CrmReadResult.Completeness.UNKNOWN,List.of(appointment("secret",0,11,"1")));
        assertEquals(2,appointmentReader.history(core,null,appointmentFilter(),null).items().size());
        actor.set(member); var page=appointmentReader.history(core,null,appointmentFilter(),null);
        assertEquals(1,page.items().size()); assertEquals(1,page.coverage().size()); assertEquals(project.id(),page.coverage().getFirst().projectId());
        assertThrows(AccessDeniedException.class,() -> appointments(other.projectId()));
        assertThrows(AccessDeniedException.class,() -> appointmentReader.appointment(jdbc.queryForObject("select id from crm.appointment where externalid='secret'",UUID.class)));
        jdbc.update("delete from dbo.employeeworkspace where employeeid=?",member);
        assertTrue(appointmentReader.history(core,null,appointmentFilter(),null).coverage().isEmpty());
        assertThrows(AccessDeniedException.class,() -> appointmentReader.history(core,workspace,appointmentFilter(),null));
    }
    @Test void appointmentClientHistoryAndJournalShareOneLeaseButClientHistoryMissingScopeIsExact() throws Exception {
        var client=importClient(); var project=service.projects(workspace,null).getFirst();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("one",0,10,"1"),appointment("two",0,11,"2")));
        provider.appointmentsReader=request -> {
            assertTrue(request.history()); assertEquals(new CrmClientReference("PROJECT","1"),request.query().client());
            provider.block("history"); return new CrmAppointmentPage(List.of(),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        };
        var state=appointmentSync.refresh(project.id(),"CLIENT_HISTORY",client.id(),SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        assertEquals(state.generation(),appointmentSync.refresh(project.id(),"CLIENT_HISTORY",client.id(),SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)).generation());
        assertEquals(429,assertThrows(CrmException.class,() -> appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1))).status());
        provider.release.countDown(); awaitAppointments();
        var rows=appointments(project.id()).items(); assertEquals("NOT_OBSERVED",rows.getFirst().observationState()); assertEquals("OBSERVED",rows.getLast().observationState());
    }
    @Test void appointmentExpiredLeaseCannotCommitAfterANewerClaim() throws Exception {
        var project=bind(connection()); awaitIdle();
        provider.appointmentsReader=request -> {
            if (provider.appointmentReads.get()==1) provider.block("old");
            return new CrmAppointmentPage(List.of(appointment("a1",0,provider.appointmentReads.get()==1 ? 10 : 12,"1")),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        };
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()-interval '1 second' where dataset='WRITER:APPOINTMENTS'");
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        awaitCondition(() -> jdbc.queryForObject("select count(*) from crm.appointment",Integer.class)==1);
        provider.release.countDown(); awaitAppointments(); assertEquals(12,appointments(project.id()).items().getFirst().localStart().getHour());
    }
    @Test void appointmentLeaseIsRecheckedAfterFlushAndRollsBackAWholePage() throws Exception {
        var project=bind(connection()); awaitIdle();
        AtomicBoolean once=new AtomicBoolean();
        afterJpaCall=(method,args) -> {
            if (method.getName().equals("flush") && Thread.currentThread().getName().startsWith("crm-appointments") && once.compareAndSet(false,true))
                jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()-interval '1 second' where dataset='WRITER:APPOINTMENTS'");
        };
        provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(appointment("a1",0,10,"1")),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        assertTrue(once.get()); assertTrue(appointments(project.id()).items().isEmpty());
    }
    @Test void appointmentRevocationDiscardsRunningResponses() throws Exception {
        var project=bind(connection()); awaitIdle(); actor.set(member);
        provider.appointmentsReader=request -> {
            provider.block("revoked"); return new CrmAppointmentPage(List.of(appointment("a1",0,10,"1")),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        };
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        actor.set(admin); jdbc.update("delete from dbo.employeeworkspace where employeeid=?",member);
        provider.release.countDown(); awaitAppointments(); assertTrue(appointments(project.id()).items().isEmpty());
        assertEquals("ACCESS_DENIED",appointmentSync.state(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)).errorCode());
    }
    @Test void appointmentInvalidResponseAndCursorLoopsCannotReplaceSuccessfulCoverage() throws Exception {
        var project=bind(connection()); awaitIdle(); loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("old",0,9,"1")));
        var before=appointments(project.id());
        provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(appointment("invalid",5,10,"1")),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        assertEquals(before.items(),appointments(project.id()).items());
        provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(),"loop",Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        var state=appointments(project.id()).coverage().getFirst().scopes().getFirst();
        assertEquals("CRM_INVALID_RESPONSE",state.errorCode()); assertEquals(before.coverage().getFirst().scopes().getFirst().successfulCoverage(),state.successfulCoverage());
    }
    @Test void appointmentPaginationIsStableAndInvalidatedBySourceMappingsAndAccess() throws Exception {
        var imported=importClient(); UUID core=coreClient("Core","70000000000"); var mapped=clients.link(imported.id(),core,imported.version());
        var project=service.projects(workspace,null).getFirst();
        var rows=new ArrayList<CrmAppointment>(); for (int i=0;i<101;i++) rows.add(appointment("a"+i,0,10,"1"));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,rows);
        var page=appointments(project.id()); assertTrue(page.hasMore()); assertEquals(100,page.items().size());
        var next=appointmentReader.appointments(project.id(),appointmentFilter(),page.nextCursor()); assertEquals(1,next.items().size());
        assertTrue(page.items().stream().noneMatch(row -> row.id().equals(next.items().getFirst().id())));
        var history=appointmentReader.history(core,null,appointmentFilter(),null); assertTrue(history.hasMore());
        clients.link(imported.id(),null,mapped.version());
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,() -> appointmentReader.history(core,null,appointmentFilter(),history.nextCursor())).code());
        assertEquals("CRM_CONTEXT_CHANGED",assertThrows(CrmException.class,() -> appointmentReader.appointments(project.id(),appointmentFilter(),page.nextCursor())).code());
        assertEquals(400,assertThrows(CrmException.class,() -> appointmentReader.appointments(project.id(),appointmentFilter(),"invalid")).status());
        int reads=provider.appointmentReads.get(); appointments(project.id()); assertEquals(reads,provider.appointmentReads.get());
    }
    @Test void appointmentPollingSurvivesRestartSkipsRevokedAccessAndRefreshesTheDatabase() throws Exception {
        var project=bind(connection()); awaitIdle(); actor.set(member);
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("a1",0,10,"1")));
        appointmentSync.close(); assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(appointmentSync,"executor")).awaitTermination(3,TimeUnit.SECONDS));
        appointmentSync=new CrmAppointmentSync(store,jdbc,tx.getTransactionManager(),new CrmRegistry(List.of(provider)),cipher,events,new CrmAppointmentWriter(store));
        provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(appointment("a1",0,12,"1")),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where dataset='APPOINTMENTS'"); actor.set(null);
        assertEquals(1,appointmentSync.refreshDue(300000)); awaitAppointments(); actor.set(admin);
        assertEquals(12,appointments(project.id()).items().getFirst().localStart().getHour());
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where dataset='APPOINTMENTS'");
        jdbc.update("delete from dbo.employeeworkspace where employeeid=?",member); assertEquals(0,appointmentSync.refreshDue(300000));
    }
    @Test void appointmentChangedCredentialsTimeZoneConnectionAndEnablementDiscardLateResponses() throws Exception {
        var project=bind(connection()); awaitIdle();
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointment("baseline",0,9,"1")));
        for (int mode=0;mode<4;mode++) {
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
            provider.appointmentsReader=request -> {
                entered.countDown(); await(release);
                return new CrmAppointmentPage(List.of(appointment("late",0,12,"1")),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
            };
            appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); await(entered);
            try {
                var current=service.projects(workspace,null).getFirst();
                if (mode==0) {
                    var connection=service.connection(current.connectionId());
                    service.updateConnection(connection.id(),new UpdateCrmConnectionRequest(connection.version(),null,"updated-secret",null));
                } else if (mode==1) service.updateProject(project.id(),new UpdateCrmProjectRequest(current.version(),null,"Europe/Moscow"));
                else if (mode==2) service.changeConnection(project.id(),new ChangeCrmConnectionRequest(connection().id(),current.version()));
                else {
                    var disabled=service.updateProject(project.id(),new UpdateCrmProjectRequest(current.version(),false,null));
                    service.updateProject(project.id(),new UpdateCrmProjectRequest(disabled.version(),true,null));
                }
            } finally { release.countDown(); }
            awaitAppointments(); awaitIdle();
            var rows=appointments(project.id()).items(); assertEquals(1,rows.size()); assertEquals("baseline",rows.getFirst().externalId());
        }
    }
    @Test void appointmentBusyRetryIsFastWhileRemoteRateLimitRetainsItsNormalDelay() throws Exception {
        var project=bind(connection()); awaitIdle();
        for (var code : List.of(CrmFailure.Code.CONNECTION_BUSY,CrmFailure.Code.RATE_LIMITED)) {
            provider.appointmentsReader=request -> { throw new CrmFailure(code); };
            appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
            jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '11 seconds' where dataset='APPOINTMENTS'");
            provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(),null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,request.project(),request.query());
            assertEquals(code==CrmFailure.Code.CONNECTION_BUSY ? 1 : 0,appointmentSync.refreshDue(300000)); awaitAppointments();
        }
    }
    @Test void appointmentExecutorSaturationIsPersistedAsLocalBusyAndIsRetried() throws Exception {
        var project=bind(connection()); awaitIdle();
        var executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(appointmentSync,"executor");
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        for (int i=0;i<2;i++) executor.execute(() -> { entered.countDown(); await(release); });
        await(entered);
        try {
            var failure=assertThrows(CrmException.class,() -> appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)));
            assertEquals(503,failure.status()); assertEquals("CRM_CONNECTION_BUSY",failure.code());
            assertEquals("CRM_CONNECTION_BUSY",appointmentSync.state(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)).errorCode());
        } finally { release.countDown(); }
        awaitAppointments();
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '11 seconds' where dataset='APPOINTMENTS'");
        assertEquals(1,appointmentSync.refreshDue(300000)); awaitAppointments(); assertEquals(1,provider.appointmentReads.get());
    }
    @Test void appointmentPollingSelectsTheOldestHistoryOrJournalScopeUnderTheSharedWriter() throws Exception {
        var client=importClient(); UUID project=client.projectId();
        loadAppointments(project,CrmReadResult.Completeness.COMPLETE,List.of(appointment("a1",0,10,"1")));
        appointmentSync.refresh(project,"CLIENT_HISTORY",client.id(),SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where dataset='APPOINTMENTS'");
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '20 minutes' where dataset='CLIENT_HISTORY'");
        var calls=new CopyOnWriteArrayList<Boolean>();
        provider.appointmentsReader=request -> {
            calls.add(request.history());
            return new CrmAppointmentPage(List.of(),null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,request.project(),request.query());
        };
        assertEquals(1,appointmentSync.refreshDue(300000)); awaitAppointments(); assertEquals(List.of(true),calls);
        assertEquals(1,appointmentSync.refreshDue(300000)); awaitAppointments(); assertEquals(List.of(true,false),calls);
        assertEquals(0,appointmentSync.refreshDue(300000));
    }
    @Test void jpaRestartPreservesImportedHistoryAndMappingsAndRecoversAbandonedWriters() throws Exception {
        var client=importClient(); UUID project=client.projectId();
        var master=first(project); service.linkEmployee(master.id(),target,master.version());
        UUID core=coreClient("Confirmed client","123"); clients.link(client.id(),core,client.version());
        loadSchedule(project,master.id(),SCHEDULE_DAY,SCHEDULE_DAY,CrmReadResult.Completeness.COMPLETE,List.of(shift("m1",0,10)));
        loadAppointments(project,CrmReadResult.Completeness.COMPLETE,List.of(appointment("a1",0,10,"1")));
        var saved=appointments(project).items().getFirst();
        var appointmentCoverage=appointmentSync.state(project,"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)).successfulCoverage();
        var scheduleCoverage=scheduleSync.state(project,master.id(),SCHEDULE_DAY,SCHEDULE_DAY).successfulCoverage();
        // Model a crash after one accepted client page, before the next page completes.
        provider.clientReader=cursor -> {
            if (cursor!=null) throw new CrmFailure(CrmFailure.Code.TEMPORARY_FAILURE);
            return clientPage(List.of(externalClient("2","Accepted page")),"50");
        };
        clientSync.refresh(project); awaitClients();
        assertEquals(2,clients.clients(project,null,false,null).items().size());
        assertEquals("FAILED",clientSync.state(project).status());
        assertEquals("CRM_TEMPORARILY_UNAVAILABLE",clientSync.state(project).errorCode());
        UUID abandoned=UUID.randomUUID();
        jdbc.update("""
                update crm.syncstate set status='RUNNING',claimtoken=?,generation=generation+1,
                    claimuntil=clock_timestamp()+interval '120 seconds'
                where projectid=? and dataset in ('WRITER:SCHEDULE','WORK_INTERVALS','WRITER:APPOINTMENTS','APPOINTMENTS')
                """,abandoned,project);
        jdbc.update("update crm.syncstate set lastattemptat=clock_timestamp()-interval '10 minutes' where projectid=?",project);
        stopWorkers(); factory.close(); StandardServiceRegistryBuilder.destroy(hibernate);
        factory=buildFactory(); startRuntime();
        // The new EntityManager, repositories, workers and cipher must all use the preserved database.
        assertEquals(target,first(project).employeeId());
        var reloadedClient=clients.clients(project,null,false,null).items().stream().filter(row -> row.id().equals(client.id())).findFirst().orElseThrow();
        assertEquals(core,reloadedClient.clientId());
        assertEquals(2,clients.clients(project,null,false,null).items().size());
        var reloaded=appointmentReader.history(core,null,appointmentFilter(),null).items().getFirst();
        assertEquals(saved.id(),reloaded.id()); assertEquals(saved.services(),reloaded.services());
        assertEquals(saved.lastObservedAt(),reloaded.lastObservedAt());
        assertEquals(appointmentCoverage,appointmentSync.state(project,"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)).successfulCoverage());
        assertEquals(scheduleCoverage,scheduleSync.state(project,master.id(),SCHEDULE_DAY,SCHEDULE_DAY).successfulCoverage());
        actor.set(null);
        try {
            assertEquals(0,scheduleSync.refreshDue(300000)); assertEquals(0,appointmentSync.refreshDue(300000));
            assertEquals(0,provider.scheduleReads.get()); assertEquals(0,provider.appointmentReads.get());
            jdbc.update("update crm.syncstate set claimuntil=clock_timestamp()-interval '1 second' where projectid=? and claimtoken=?",project,abandoned);
            provider.projectDisplayName="Name after restart";
            assertEquals(1,sync.refreshDue(300000)); awaitIdle();
            provider.scheduleReader=query -> new CrmScheduleSnapshot(List.of(shift("m1",0,12)),Instant.now(),CrmReadResult.Completeness.COMPLETE,"100",query);
            provider.appointmentsReader=request -> new CrmAppointmentPage(List.of(appointment("a1",0,12,"1")),null,Instant.now(),CrmReadResult.Completeness.COMPLETE,request.project(),request.query());
            assertEquals(1,scheduleSync.refreshDue(300000)); awaitSchedule();
            assertEquals(1,appointmentSync.refreshDue(300000)); awaitAppointments();
            assertEquals(0,scheduleSync.refreshDue(300000)); assertEquals(0,appointmentSync.refreshDue(300000));
        } finally { actor.set(admin); }
        assertEquals("Name after restart",service.projects(workspace,null).getFirst().displayName());
        assertEquals(target,first(project).employeeId());
        assertEquals(12,schedulePage(project,master.id(),SCHEDULE_DAY,SCHEDULE_DAY).items().getFirst().localStart().getHour());
        var updated=appointmentReader.history(core,null,appointmentFilter(),null).items().getFirst();
        assertEquals(saved.id(),updated.id()); assertEquals(12,updated.localStart().getHour());
        assertEquals(saved.services().stream().map(CrmAppointmentServiceDto::id).toList(),updated.services().stream().map(CrmAppointmentServiceDto::id).toList());
        provider.clientReader=cursor -> clientPage(List.of(externalClient("1","Refreshed profile"),externalClient("2","Accepted page")),null);
        clientSync.refresh(project); awaitClients();
        assertEquals(2,clients.clients(project,null,false,null).items().size());
        assertEquals(core,clients.clients(project,null,false,null).items().stream().filter(row -> row.id().equals(client.id())).findFirst().orElseThrow().clientId());
        assertEquals(0,jdbc.queryForObject("select count(*) from crm.syncstate where projectid=? and claimtoken=?",Integer.class,project,abandoned));
        verify(events,atLeast(4)).workspace(any(),eq("crm.invalidated"));
    }
    private static CrmAppointment appointmentCopy(CrmAppointment s,List<CrmAppointmentService> services,List<CrmAppointmentMaster> masters,
            boolean servicesComplete,boolean mastersComplete,String comment,Set<String> fields,String source) {
        return new CrmAppointment(s.externalId(),s.kind(),s.client(),s.clientSnapshot(),s.localStart(),s.localEnd(),
                s.appointmentState(),s.confirmationState(),s.attendanceState(),s.providerState(),comment,services,masters,
                servicesComplete,mastersComplete,fields,source);
    }
    @Test void appointmentServiceReorderingPreservesLineIdsAndUnobservedHistoricalTerms() throws Exception {
        var project=bind(connection()); awaitIdle(); var original=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(original));
        var before=appointments(project.id()).items().getFirst();
        var reordered=List.of(new CrmAppointmentService("s2","l2","Additional service",null,null,null,Set.of("displayName")),
                new CrmAppointmentService("s1","l1","Historical service",null,null,null,Set.of("displayName")));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(original,reordered,original.masters(),true,true,original.comment(),original.presentFields(),"JOURNAL")));
        var lines=appointments(project.id()).items().getFirst().services();
        assertEquals(List.of("l2","l1"),lines.stream().map(CrmAppointmentServiceDto::externalLineId).toList());
        assertEquals(before.services().getFirst().id(),lines.getLast().id()); assertEquals(before.services().getLast().id(),lines.getFirst().id());
        assertEquals("1200",lines.getLast().price()); assertEquals(3600L,lines.getLast().durationSeconds()); assertEquals("RUB",lines.getLast().currency());
    }
    @Test void appointmentPartialServicesUpdateKnownLinesAndAppendNewIdentitiesWithoutRemovingOthers() throws Exception {
        var project=bind(connection()); awaitIdle(); var original=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(original));
        var observed=List.of(new CrmAppointmentService("s1","l1",null,null,new BigDecimal("1300"),null,Set.of("price")),
                new CrmAppointmentService("s3","l3","Third",600L,new BigDecimal("200"),"RUB"));
        var partial=appointmentCopy(original,observed,original.masters(),false,true,original.comment(),original.presentFields(),"JOURNAL");
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(partial));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(partial));
        var lines=appointments(project.id()).items().getFirst().services(); assertEquals(3,lines.size());
        assertEquals("1300",lines.getFirst().price()); assertEquals("Historical service",lines.getFirst().displayName());
        assertEquals("l2",lines.get(1).externalLineId()); assertEquals("l3",lines.getLast().externalLineId()); assertEquals("200",lines.getLast().price());
    }
    @Test void appointmentRepeatedServiceCanBeReorderedByLineIdWhileAmbiguousPartialRowsKeepTheirPreviousTerms() throws Exception {
        var project=bind(connection()); awaitIdle(); var original=appointment("a1",0,10,"1");
        var duplicates=List.of(new CrmAppointmentService("s1","l1","First",600L,new BigDecimal("100"),"RUB"),
                new CrmAppointmentService("s1","l2","Second",1200L,new BigDecimal("200"),"RUB"));
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointmentCopy(original,duplicates,original.masters(),true,true,original.comment(),original.presentFields(),"JOURNAL")));
        var reversed=List.of(new CrmAppointmentService("s1","l2",null,null,null,null,Set.of()),new CrmAppointmentService("s1","l1",null,null,null,null,Set.of()));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(original,reversed,original.masters(),true,true,original.comment(),original.presentFields(),"JOURNAL")));
        assertEquals(List.of("200","100"),appointments(project.id()).items().getFirst().services().stream().map(CrmAppointmentServiceDto::price).toList());
        var ambiguous=List.of(new CrmAppointmentService("s1",null,null,null,new BigDecimal("999"),null,Set.of("price")));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(original,ambiguous,original.masters(),false,true,original.comment(),original.presentFields(),"JOURNAL")));
        assertEquals(List.of("200","100"),appointments(project.id()).items().getFirst().services().stream().map(CrmAppointmentServiceDto::price).toList());
    }
    @Test void appointmentPartialMasterTimeInvalidatesTheOtherCoordinateInsteadOfRollingBackThePage() throws Exception {
        var project=bind(connection()); awaitIdle(); var original=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(original));
        var start=original.localStart().plusHours(2);
        var partial=new CrmAppointment(original.externalId(),original.kind(),original.client(),original.clientSnapshot(),start,start.plusHours(1),
                original.appointmentState(),original.confirmationState(),original.attendanceState(),original.providerState(),original.comment(),original.services(),
                List.of(new CrmAppointmentMaster("m1",null,start,null,null,null,null,Set.of("localStart"))),true,false,original.presentFields(),"JOURNAL");
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(partial));
        var dto=appointments(project.id()).items().getFirst(); assertEquals(start,dto.localStart());
        assertEquals(start,dto.masters().getFirst().localStart()); assertNull(dto.masters().getFirst().localEnd());
        var end=start.plusHours(2);
        var endOnly=appointmentCopy(partial,partial.services(),List.of(new CrmAppointmentMaster("m1",null,null,end,null,null,null,Set.of("localEnd"))),true,false,partial.comment(),partial.presentFields(),"JOURNAL");
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(endOnly));
        var master=appointments(project.id()).items().getFirst().masters().getFirst(); assertNull(master.localStart()); assertEquals(end,master.localEnd());
    }
    @Test void appointmentServiceFallbackCannotConsumeALineExplicitlyReferencedLaterInTheResponse() throws Exception {
        var project=bind(connection()); awaitIdle(); var original=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(original));
        UUID identity=appointments(project.id()).items().getFirst().services().getFirst().id();
        var observed=List.of(new CrmAppointmentService("s1",null,"New line",600L,new BigDecimal("50"),"RUB"),
                new CrmAppointmentService("s2","l1","Changed line",1200L,new BigDecimal("60"),"RUB"));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(original,observed,original.masters(),false,true,original.comment(),original.presentFields(),"JOURNAL")));
        var lines=appointments(project.id()).items().getFirst().services(); assertEquals(3,lines.size());
        assertEquals(1,lines.stream().filter(line -> "l1".equals(line.externalLineId())).count());
        var changed=lines.stream().filter(line -> "l1".equals(line.externalLineId())).findFirst().orElseThrow();
        assertEquals(identity,changed.id()); assertEquals("s2",changed.externalServiceId()); assertEquals("60",changed.price());
        assertEquals("50",lines.stream().filter(line -> line.externalLineId()==null).findFirst().orElseThrow().price());
    }
    @Test void appointmentExplicitlyClearedJournalCommentSurvivesPartialJournalAndWeakHistory() throws Exception {
        var project=bind(connection()); awaitIdle(); var original=appointment("a1",0,10,"1");
        loadAppointments(project.id(),CrmReadResult.Completeness.COMPLETE,List.of(appointmentCopy(original,original.services(),original.masters(),true,true,null,original.presentFields(),"JOURNAL")));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(original,List.of(),List.of(),false,false,null,Set.of("time"),"JOURNAL")));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(original,List.of(),List.of(),false,false,"Old note",Set.of("comment"),"HISTORY")));
        assertNull(appointments(project.id()).items().getFirst().comment());
        var absent=appointment("a2",0,11,"1"); var missing=new HashSet<>(absent.presentFields()); missing.remove("comment");
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(absent,absent.services(),absent.masters(),true,true,null,missing,"JOURNAL")));
        loadAppointments(project.id(),CrmReadResult.Completeness.UNKNOWN,List.of(appointmentCopy(absent,List.of(),List.of(),false,false,"History enrichment",Set.of("comment"),"HISTORY")));
        assertEquals("History enrichment",appointments(project.id()).items().getLast().comment());
    }
    @Test void appointmentDeadlineIsRecheckedAfterDatabaseWaitingBeforeCallingTheProvider() throws Exception {
        var project=bind(connection()); awaitIdle(); var contexts=new AtomicInteger();
        afterJpaCall=(method,args) -> {
            if (Thread.currentThread().getName().equals("crm-appointments") && method.getName().equals("refresh")
                    && args!=null && args.length==2 && args[0] instanceof CrmProjectEntity && contexts.incrementAndGet()==2)
                appointmentNanoTime.set(TimeUnit.SECONDS.toNanos(91));
        };
        appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); awaitAppointments();
        assertEquals(0,provider.appointmentReads.get());
        var state=appointmentSync.state(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1));
        assertEquals("PARTIAL",state.status()); assertEquals("CRM_SYNC_LIMIT_REACHED",state.errorCode()); assertNull(state.lastSuccessAt());
    }
    @Test void appointmentOldSuccessAndOldAuthFailureCannotOverwriteANewerConnectionCheck() throws Exception {
        for (boolean olderSuccess : List.of(true,false)) {
            var project=service.projects(workspace,null).stream().findFirst().orElse(null);
            if (project==null) { project=bind(connection()); awaitIdle(); }
            var beforeApply=new CountDownLatch(1); var release=new CountDownLatch(1); var finds=new AtomicInteger();
            afterJpaCall=(method,args) -> {
                if (Thread.currentThread().getName().equals("crm-appointments") && method.getName().equals("find")
                        && args!=null && args.length>=2 && args[0]==CrmConnectionEntity.class && finds.incrementAndGet()==3) {
                    beforeApply.countDown(); await(release);
                }
            };
            provider.appointmentsReader=request -> {
                if (!olderSuccess) throw new CrmFailure(CrmFailure.Code.AUTH_REQUIRED);
                return new CrmAppointmentPage(List.of(),null,Instant.now(),CrmReadResult.Completeness.UNKNOWN,request.project(),request.query());
            };
            appointmentSync.refresh(project.id(),"APPOINTMENTS",null,SCHEDULE_DAY,SCHEDULE_DAY.plusDays(1)); await(beforeApply);
            try {
                provider.catalogReader=olderSuccess ? access -> { throw new CrmFailure(CrmFailure.Code.AUTH_REQUIRED); } : null;
                sync.refresh(project.id()); awaitIdle();
            } finally { release.countDown(); }
            awaitAppointments();
            assertEquals(olderSuccess ? CrmConnectionStatus.REAUTH_REQUIRED : CrmConnectionStatus.CONNECTED,service.connection(project.connectionId()).status());
            service.verify(project.connectionId()); assertEquals(CrmConnectionStatus.CONNECTED,service.connection(project.connectionId()).status());
        }
    }
    @Test void incorrectlyDefinedExistingPartialIndexIsRejected() {
        jdbc.execute("drop index crm.uqprojectdikidiworkspace");
        try {
            jdbc.execute("create index uqprojectdikidiworkspace on crm.project(workspaceid)");
            assertThrows(IllegalStateException.class,storage::initialize);
            assertEquals("CRM_STORAGE_NOT_READY",assertThrows(CrmException.class,storage::requireReady).code());
        } finally {
            jdbc.execute("drop index crm.uqprojectdikidiworkspace"); storage.initialize();
        }
    }
    @Test void disableEnableDoesNotReviveTheOldContext() throws Exception {
        provider.reader = access -> provider.block("old");
        var project = bind(connection()); assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        var disabled = service.updateProject(project.id(),new UpdateCrmProjectRequest(project.version(),false,null));
        var enabled = service.updateProject(project.id(),new UpdateCrmProjectRequest(disabled.version(),true,null));
        assertEquals(project.contextVersion()+2,enabled.contextVersion());
        provider.release.countDown(); awaitIdle();
        assertTrue(service.masters(project.id(),null).items().isEmpty());
        provider.reader = access -> FakeProvider.masters("current");
        sync.refresh(project.id()); awaitIdle();
        assertEquals("current one",first(project.id()).displayName());
    }
    @Test void revokedActorCannotApplyAnAlreadyRunningRead() throws Exception {
        provider.reader = access -> provider.block("revoked");
        var project = bind(connection()); assertTrue(provider.entered.await(2,TimeUnit.SECONDS));
        jdbc.update("update dbo.users set isenabled=false where userid=(select userid from dbo.employee where employeeid=?)",admin);
        provider.release.countDown(); awaitIdle();
        assertEquals(0,jdbc.queryForObject("select count(*) from crm.master where projectid=?",Integer.class,project.id()));
        jdbc.update("update dbo.users set isenabled=true where userid=(select userid from dbo.employee where employeeid=?)",admin);
        assertNotEquals("CURRENT",sync.state(project.id()).freshness());
    }
    @Test void productionSchemaConfigurationBootstrapsCrmEvenWhenFlywayHistoryAlreadyExists() {
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        var properties = Objects.requireNonNull(yaml.getObject());
        assertEquals("dbo",properties.getProperty("spring.jpa.properties.hibernate.default_schema"));
        assertEquals("update",properties.getProperty("spring.jpa.hibernate.ddl-auto"));
        var schemas = properties.getProperty("spring.flyway.schemas").split(",");
        assertTrue(Arrays.asList(schemas).contains("crm"));
        jdbc.execute("create role clientbus login"); jdbc.execute("create database crmbootstrap");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl().replace("/crmfoundation","/crmbootstrap"),postgres.getUsername(),postgres.getPassword());
        var bootJdbc = new JdbcTemplate(source);
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schemas).defaultSchema("system")
                .createSchemas(true).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0").load();
        UUID marker = UUID.randomUUID();
        for (int pass=0; pass<2; pass++) {
            flyway.migrate();
            assertEquals("crm",bootJdbc.queryForObject("select schema_name from information_schema.schemata where schema_name='crm'",String.class));
            var registry = new StandardServiceRegistryBuilder().applySetting("hibernate.connection.datasource",source)
                    .applySetting("hibernate.default_schema","dbo").applySetting("hibernate.hbm2ddl.auto","update")
                    .applySetting("hibernate.hbm2ddl.halt_on_error",true).build();
            try {
                var metadata = new MetadataSources(registry);
                for (var type : List.of(OrganizationEntity.class,UserEntity.class,EmployeeEntity.class,WorkspaceEntity.class,
                        CrmConnectionEntity.class,CrmProjectEntity.class,CrmMasterEntity.class,CrmServiceEntity.class,
                        CrmMasterServiceEntity.class,CrmSyncStateEntity.class)) metadata.addAnnotatedClass(type);
                try (var orm = metadata.buildMetadata().buildSessionFactory()) { new CrmStorage(bootJdbc).initialize(); }
            } finally { StandardServiceRegistryBuilder.destroy(registry); }
            if (pass==0) {
                bootJdbc.execute("create table dbo.existingmarker(id uuid primary key)");
                bootJdbc.update("insert into dbo.existingmarker(id) values (?)",marker);
                bootJdbc.execute("drop schema crm cascade"); // Only this isolated Testcontainers database.
            } else assertEquals(marker,bootJdbc.queryForObject("select id from dbo.existingmarker",UUID.class));
        }
    }
}
