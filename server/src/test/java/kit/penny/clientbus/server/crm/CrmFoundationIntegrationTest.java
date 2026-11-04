package kit.penny.clientbus.server.crm;

import jakarta.persistence.*;
import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.common.notification.UiEventTypes;
import kit.penny.clientbus.crm.api.*;
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
    private CrmStore store;
    private CrmDeletionGuard deletion;
    private FakeProvider provider;
    private CrmSecretCipher cipher;
    private CrmStorage storage;
    private CrmMasterRepository masterRepository;
    private final AtomicReference<UUID> actor = new AtomicReference<>();
    private final AtomicLong clientNanoTime = new AtomicLong();
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
                CrmSyncStateEntity.class, ClientEntity.class, ClientAccountEntity.class, CrmClientEntity.class)) metadata.addAnnotatedClass(type);
        return metadata.buildMetadata().buildSessionFactory();
    }
    @AfterAll static void closeDatabase() {
        if (factory != null) factory.close();
        if (hibernate != null) StandardServiceRegistryBuilder.destroy(hibernate);
    }
    @BeforeEach void setup() {
        jdbc.execute("truncate dbo.organization,dbo.users cascade");
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        afterJpaCall = (method, args) -> {};
        afterMasterPage = () -> {};
        clientNanoTime.set(0);
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
        var events = mock(UiDomainEvents.class);
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
        service = new CrmService(store, registry, cipher, sync, manager, events);
        deletion = new CrmDeletionGuard(store, events);
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
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (executor.getActiveCount() != 0 && System.nanoTime()<deadline) Thread.sleep(5);
        assertEquals(0, executor.getActiveCount(), "CRM worker did not complete");
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
        final AtomicInteger reads = new AtomicInteger(), inspections = new AtomicInteger();
        volatile Function<CrmAccess, CrmReadResult<CrmMaster>> reader = access -> masters("initial");
        volatile Function<CrmAccess, CrmCatalogSnapshot> catalogReader;
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
            if (catalogReader != null) return catalogReader.apply(access);
            var result = reader.apply(access);
            return new CrmCatalogSnapshot(result.items(), List.of(), List.of(), result.observedAt(), result.completeness(),
                    result.scope().replace(":MASTERS", ":CATALOG"));
        }
        public void invalidate(UUID id) {}
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
        assertEquals(Set.of("1200.2500", "1500.0000"),
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
        assertEquals("100.0000", retained.price()); assertEquals(600L, retained.durationSeconds());
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
        assertEquals(Set.of("connection","project","master","service","masterservice","syncstate","client"),
                new HashSet<>(jdbc.queryForList("select tablename from pg_tables where schemaname='crm'",String.class)));
        for (String sql : List.of("select column_name from information_schema.columns where table_schema='crm'",
                "select indexname from pg_indexes where schemaname='crm'",
                "select c.conname from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='crm'"))
            assertTrue(jdbc.queryForList(sql,String.class).stream().noneMatch(name -> name.contains("_")));
        sync.close(); assertTrue(((ThreadPoolExecutor)ReflectionTestUtils.getField(sync,"executor")).awaitTermination(3,TimeUnit.SECONDS));
        factory.close(); StandardServiceRegistryBuilder.destroy(hibernate);
        // Reproduce the deployed 12.3 shape before allowing Hibernate to add 12.4.1.
        jdbc.execute("drop table crm.masterservice"); jdbc.execute("drop table crm.service");
        jdbc.execute("drop table crm.client");
        jdbc.execute("alter table crm.master drop column lastobservedgeneration, drop column observationstate");
        assertEquals(4, jdbc.queryForObject("select count(*) from pg_tables where schemaname='crm'", Integer.class));
        factory = buildFactory();
        new CrmStorage(jdbc).initialize();
        assertEquals(1,jdbc.queryForObject("select count(*) from crm.project where id=?",Integer.class,project.id()));
        assertEquals(1,jdbc.queryForObject("select count(*) from dbo.workspace where workspaceid=?",Integer.class,workspace));
        assertEquals(target, jdbc.queryForObject("select employeeid from crm.master where id=?", UUID.class, mapped.id()));
        assertEquals("initial one", jdbc.queryForObject("select displayname from crm.master where id=?", String.class, mapped.id()));
        assertEquals(7, jdbc.queryForObject("select count(*) from pg_tables where schemaname='crm'", Integer.class));
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
                        CrmConnectionEntity.class,CrmProjectEntity.class,CrmMasterEntity.class,CrmSyncStateEntity.class)) metadata.addAnnotatedClass(type);
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
