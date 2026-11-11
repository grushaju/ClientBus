package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.*;
import kit.penny.clientbus.common.enums.*;
import kit.penny.clientbus.server.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Wire validation/errors. Actual database authorization is covered by CrmFoundationIntegrationTest. */
class CrmHttpContractTest {
    private CrmService service;
    private CrmCatalogSync sync;
    private CrmCatalogReader catalog;
    private CrmClientSync clientSync;
    private CrmClientService clients;
    private CrmScheduleSync schedules;
    private CrmScheduleReader scheduleReader;
    private CrmAppointmentSync appointments;
    private CrmAppointmentReader appointmentReader;
    private CrmOperationService operations;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(CrmService.class); sync = mock(CrmCatalogSync.class); catalog = mock(CrmCatalogReader.class);
        clientSync = mock(CrmClientSync.class); clients = mock(CrmClientService.class);
        schedules=mock(CrmScheduleSync.class); scheduleReader=mock(CrmScheduleReader.class);
        appointments=mock(CrmAppointmentSync.class); appointmentReader=mock(CrmAppointmentReader.class);
        operations=mock(CrmOperationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CrmController(service,sync,clientSync,schedules,appointments), new CrmCatalogController(catalog), new CrmClientController(clients),new CrmScheduleController(scheduleReader),new CrmAppointmentController(appointmentReader),new CrmOperationController(operations))
                .setControllerAdvice(new CrmRequestErrors(), new GlobalExceptionHandler()).build();
    }
    @Test void appointmentCommandsRequireADurableKeyAndValidateTheirIntentBeforeAdmission() throws Exception {
        UUID project=UUID.randomUUID(),key=UUID.randomUUID(),id=UUID.randomUUID();
        var op=new CrmOperationDto(id,project,"CREATE",null,UUID.randomUUID(),key,1,1,"REQUESTED",java.time.Instant.now(),java.time.Instant.now(),null,null);
        when(operations.create(eq(project),eq(key),any())).thenReturn(op);
        String route="/api/crm/projects/"+project+"/appointments";
        String body="{\"contextVersion\":1,\"zoneRevision\":1,\"crmClientId\":\""+UUID.randomUUID()+"\",\"masterId\":\""+UUID.randomUUID()+"\",\"offers\":[{\"id\":\""+UUID.randomUUID()+"\",\"expectedVersion\":0}],\"localStart\":\"2030-01-10T12:00:00\"}";
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(route).header("Idempotency-Key","invalid").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(route).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(operations);
        mvc.perform(post(route).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.status").value("REQUESTED"));
        verify(operations).create(eq(project),eq(key),any());
        when(operations.create(eq(project),eq(key),any())).thenReturn(new CrmOperationDto(id,project,"CREATE",null,op.initiatorEmployeeId(),key,1,1,"CONFIRMED",op.createdAt(),op.updatedAt(),null,"external-id"));
        mvc.perform(post(route).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
    @Test void operationRecoveryEndpointsAreReadsAndReconciliationIsExplicit() throws Exception {
        UUID project=UUID.randomUUID(),key=UUID.randomUUID(),id=UUID.randomUUID();
        mvc.perform(get("/api/crm/projects/"+project+"/operations/by-key/"+key)).andExpect(status().isOk()); verify(operations).byKey(project,key);
        mvc.perform(get("/api/crm/projects/"+project+"/operations").param("active","true")).andExpect(status().isOk()); verify(operations).list(project,true,true,null);
        mvc.perform(get("/api/crm/operations/"+id)).andExpect(status().isOk()); verify(operations).get(id);
        mvc.perform(post("/api/crm/operations/"+id+"/reconcile")).andExpect(status().isOk()); verify(operations).reconcile(id);
        verifyNoMoreInteractions(operations);
    }
    @Test void appointmentHistoryAndPendingCreationsAreReadOnlyAndForwardTheCardScope() throws Exception {
        UUID appointment=UUID.randomUUID(),project=UUID.randomUUID();
        mvc.perform(get("/api/crm/appointments/"+appointment+"/history")).andExpect(status().isOk());
        verify(operations).history(appointment,true,null);
        mvc.perform(get("/api/crm/appointments/"+appointment+"/history").param("own","false").param("cursor","next")).andExpect(status().isOk());
        verify(operations).history(appointment,false,"next");
        mvc.perform(get("/api/crm/projects/"+project+"/pending-creations").param("cursor","next")).andExpect(status().isOk());
        verify(operations).pendingCreates(project,true,"next");
        mvc.perform(get("/api/crm/projects/"+project+"/pending-creations").param("own","false")).andExpect(status().isOk());
        verify(operations).pendingCreates(project,false,null);verifyNoMoreInteractions(operations);
    }
    @Test void moveAvailabilityRequiresAnAppointmentVersionAndUsesItsOwnReadEndpoint() throws Exception {
        UUID id=UUID.randomUUID();String route="/api/crm/appointments/"+id+"/availability";
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{\"contextVersion\":1,\"zoneRevision\":1,\"expectedVersion\":-1,\"date\":\"2030-01-10\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(operations);
        var request=new CrmMoveAvailabilityRequest(1L,1L,0L,java.time.LocalDate.of(2030,1,10));
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{\"contextVersion\":1,\"zoneRevision\":1,\"expectedVersion\":0,\"date\":\"2030-01-10\"}")).andExpect(status().isOk());
        verify(operations).moveAvailability(id,request);verifyNoMoreInteractions(operations);
    }
    @Test void detachingAProjectRequiresAValidVersionAndIsASeparateOperationFromDisabling() throws Exception {
        UUID id=UUID.randomUUID(); String route="/api/crm/projects/"+id+"/detach";
        mvc.perform(post(route)).andExpect(status().isBadRequest());
        for (String version : List.of("-1","invalid")) mvc.perform(post(route).param("expectedVersion",version)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(post(route).param("expectedVersion","0")).andExpect(status().isOk());
        verify(service).detachProject(id,0); verifyNoMoreInteractions(service);
    }
    @Test void restorationRejectsANegativeArchiveVersionBeforeCallingTheService() throws Exception {
        mvc.perform(post("/api/crm/workspaces/"+UUID.randomUUID()+"/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"connectionId\":\""+UUID.randomUUID()+"\",\"externalProjectId\":\"100\",\"expectedArchiveVersion\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void projectDiscoveryRequiresATargetWorkspaceAndCallsOnlyTheReadService() throws Exception {
        UUID connection=UUID.randomUUID(), workspace=UUID.randomUUID(); String route="/api/crm/connections/"+connection+"/projects";
        mvc.perform(get(route)).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("workspaceId","invalid")).andExpect(status().isBadRequest()); verifyNoInteractions(service);
        when(service.availableProjects(connection,workspace)).thenReturn(List.of(new CrmProjectCheckDto("100","Salon",null,null)));
        mvc.perform(get(route).param("workspaceId",workspace.toString())).andExpect(status().isOk()).andExpect(jsonPath("$[0].displayName").value("Salon"));
        verify(service).availableProjects(connection,workspace); verifyNoMoreInteractions(service);
    }
    @Test void clientBusCrmLinksAreLocalReadsWithAnOptionalWorkspaceScope() throws Exception {
        UUID id=UUID.randomUUID(),workspace=UUID.randomUUID();
        String route="/api/crm/clientbus-clients/"+id+"/links";
        when(clients.links(id,null)).thenReturn(List.of()); when(clients.links(id,workspace)).thenReturn(List.of());
        mvc.perform(get(route)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(route).param("workspaceId",workspace.toString())).andExpect(status().isOk());
        mvc.perform(get(route).param("workspaceId","invalid")).andExpect(status().isBadRequest());
        verify(clients).links(id,null); verify(clients).links(id,workspace); verifyNoMoreInteractions(clients);
        verifyNoInteractions(service,appointments,schedules,sync,clientSync);
    }
    @Test void deletingAConnectionRequiresAValidVersionAndReturnsNoContentOrAnInUseConflict() throws Exception {
        UUID id=UUID.randomUUID(); String route="/api/crm/connections/"+id;
        mvc.perform(delete(route)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String version : List.of("-1","invalid")) mvc.perform(delete(route).param("expectedVersion",version))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
        mvc.perform(delete(route).param("expectedVersion","0")).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).deleteConnection(id,0);
        doThrow(new CrmException(409,"CRM_CONNECTION_IN_USE","К аккаунту привязаны проекты.")).when(service).deleteConnection(id,1);
        mvc.perform(delete(route).param("expectedVersion","1")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CRM_CONNECTION_IN_USE"));
    }
    @Test void appointmentRefreshValidatesPeriodAndProjectClientBeforeCallingWorkers() throws Exception {
        UUID project=UUID.randomUUID(),client=UUID.randomUUID();
        String route="/api/crm/projects/"+project+"/refresh";
        for (String body : List.of("{\"dataset\":\"APPOINTMENTS\"}",
                "{\"dataset\":\"CLIENT_HISTORY\",\"dateFrom\":\"2026-11-01\",\"dateTo\":\"2026-11-02\"}",
                "{\"dataset\":\"APPOINTMENTS\",\"dateFrom\":\"2026-11-01\",\"dateTo\":\"2026-12-02\"}",
                "{\"dataset\":\"APPOINTMENTS\",\"crmClientId\":\""+client+"\",\"dateFrom\":\"2026-11-01\",\"dateTo\":\"2026-11-02\"}"))
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service,appointments);
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"CLIENT_HISTORY\",\"crmClientId\":\""+client+"\",\"dateFrom\":\"2026-11-01\",\"dateTo\":\"2026-11-02\"}"))
                .andExpect(status().isAccepted());
        verify(appointments).refresh(project,"CLIENT_HISTORY",client,java.time.LocalDate.parse("2026-11-01"),java.time.LocalDate.parse("2026-11-02"));
        verifyNoInteractions(sync,clientSync,schedules);
    }
    @Test void appointmentReadRoutesValidateDatesAndStatesAndDoNotStartRefresh() throws Exception {
        UUID project=UUID.randomUUID(),client=UUID.randomUUID(),id=UUID.randomUUID();
        String route="/api/crm/projects/"+project+"/appointments";
        mvc.perform(get(route).param("dateFrom","2026-11-01").param("dateTo","2026-11-02").param("crmClientId",client.toString()))
                .andExpect(status().isOk());
        verify(appointmentReader).appointments(eq(project),eq(new CrmAppointmentFilter(java.time.LocalDate.parse("2026-11-01"),java.time.LocalDate.parse("2026-11-02"),client,null,null,null,null,null,null)),isNull());
        mvc.perform(get(route).param("dateFrom","2026-11-01").param("dateTo","2026-11-02").param("appointmentState","PAID"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(route).param("dateFrom","2026-11-01").param("dateTo","2026-12-02")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/crm/appointments/"+id)).andExpect(status().isOk()); verify(appointmentReader).appointment(id);
        mvc.perform(get("/api/clients/"+client+"/crm/appointments").param("dateFrom","2026-11-01").param("dateTo","2026-11-02"))
                .andExpect(status().isOk()); verify(appointmentReader).history(eq(client),isNull(),any(),isNull());
        verifyNoInteractions(service,appointments,schedules,sync,clientSync);
    }
    @Test void forceIsExplicitAndCooldownIncludesMachineReadableRetryTime() throws Exception {
        UUID project=UUID.randomUUID();String endpoint="/api/crm/projects/"+project+"/refresh";
        var from=java.time.LocalDate.of(2026,11,1);var retry=java.time.Instant.now().plusSeconds(120);
        when(appointments.refresh(project,"APPOINTMENTS",null,from,from,true)).thenThrow(new CrmException(429,"CRM_COOLDOWN","Pause",retry));
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{\"dataset\":\"APPOINTMENTS\",\"dateFrom\":\"2026-11-01\",\"dateTo\":\"2026-11-01\",\"force\":true}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.retryAt").value(retry.toString()));
        verify(service).requireProjectAccess(project);verify(appointments).refresh(project,"APPOINTMENTS",null,from,from,true);
    }
    @Test void appointmentClientsAndExternalReferenceFiltersAreLocalValidatedReads() throws Exception {
        UUID project=UUID.randomUUID(); var from=java.time.LocalDate.parse("2026-11-01");
        String route="/api/crm/projects/"+project+"/appointment-clients";
        mvc.perform(get(route).param("dateFrom",from.toString()).param("dateTo",from.toString()).param("q","Иван")).andExpect(status().isOk());
        verify(appointmentReader).clients(project,from,from,"Иван",null,null);
        mvc.perform(get(route).param("dateFrom",from.toString()).param("dateTo","2026-12-02")).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("dateFrom",from.toString()).param("dateTo",from.toString()).param("q","x".repeat(161))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/crm/projects/"+project+"/appointments").param("dateFrom",from.toString()).param("dateTo",from.toString()).param("externalClientId","client-1")).andExpect(status().isOk());
        verify(appointmentReader).appointments(project,new CrmAppointmentFilter(from,from,null,null,null,null,null,null,null,"client-1"),null);
        mvc.perform(get("/api/crm/projects/"+project+"/appointments").param("dateFrom",from.toString()).param("dateTo",from.toString())
                .param("externalClientId","client-1").param("crmClientId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(service,appointments,schedules,sync,clientSync);
    }
    @Test void appointmentStateRequiresAnExplicitDatasetAndScope() throws Exception {
        UUID project=UUID.randomUUID();
        String route="/api/crm/projects/"+project+"/sync-state";
        var from=java.time.LocalDate.parse("2026-11-01");
        when(appointments.state(project,"APPOINTMENTS",null,from,from)).thenReturn(new CrmSyncStateDto("APPOINTMENTS","IDLE",null,null,"NEVER_LOADED","UNKNOWN",null,0));
        mvc.perform(get(route).param("dataset","APPOINTMENTS").param("dateFrom",from.toString()).param("dateTo",from.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].dataset").value("APPOINTMENTS"));
        verify(appointments).state(project,"APPOINTMENTS",null,from,from);
        mvc.perform(get(route).param("dataset","UNSUPPORTED")).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("dataset","CLIENT_HISTORY").param("dateFrom",from.toString()).param("dateTo",from.toString())).andExpect(status().isBadRequest());
        mvc.perform(get(route).param("dataset","APPOINTMENTS").param("dateFrom",from.toString())).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(appointments);
        verifyNoInteractions(sync,clientSync,schedules);
    }
    @Test void scheduleRefreshRequiresOneMasterAndAnInclusiveBoundedPeriod() throws Exception {
        UUID id=UUID.randomUUID(), master=UUID.randomUUID();
        for (String fields : List.of("", ",\"masterId\":\""+master+"\"", ",\"masterId\":\""+master+"\",\"dateFrom\":\"2026-11-01\",\"dateTo\":\"2026-12-02\"")) {
            mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dataset\":\"WORK_INTERVALS\""+fields+"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(schedules,service);
        mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"WORK_INTERVALS\",\"masterId\":\""+master+"\",\"dateFrom\":\"2026-11-01\",\"dateTo\":\"2026-11-30\"}"))
                .andExpect(status().isAccepted());
        verify(service).requireProjectAccess(id);
        verify(schedules).refresh(id,master,java.time.LocalDate.parse("2026-11-01"),java.time.LocalDate.parse("2026-11-30"));
        verifyNoInteractions(sync,clientSync);
    }
    @Test void scheduleReadUsesLocalMasterAndDatesAndRejectsBadPeriods() throws Exception {
        UUID id=UUID.randomUUID(), master=UUID.randomUUID();
        String route="/api/crm/projects/"+id+"/work-intervals";
        mvc.perform(get(route).param("masterId",master.toString()).param("dateFrom","2026-11-01").param("dateTo","2026-11-02"))
                .andExpect(status().isOk());
        verify(scheduleReader).intervals(id,master,java.time.LocalDate.parse("2026-11-01"),java.time.LocalDate.parse("2026-11-02"),null);
        for (String to : List.of("invalid","2026-10-31","2026-12-02"))
            mvc.perform(get(route).param("masterId",master.toString()).param("dateFrom","2026-11-01").param("dateTo",to))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(route).param("dateFrom","2026-11-01").param("dateTo","2026-11-02")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(scheduleReader);
    }
    @Test void clientRefreshAndMutationsHaveExplicitValidatedContracts() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"CLIENTS\"}")).andExpect(status().isAccepted());
        verify(service).requireProjectAccess(id); verify(clientSync).refresh(id); verifyNoInteractions(sync);
        mvc.perform(delete("/api/crm/clients/"+id+"/client-link")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/crm/clients/"+id+"/client-link?expectedVersion=-1")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/crm/clients/"+id+"/client-link").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/crm/clients/"+id+"/client").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"\",\"lastName\":\"Name\",\"phoneList\":[],\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clients);
        mvc.perform(post("/api/crm/clients/"+id+"/client").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Confirmed\",\"lastName\":\"Name\",\"phoneList\":[],\"expectedVersion\":0}"))
                .andExpect(status().isCreated());
        verify(clients).create(eq(id),any());
    }
    @Test void scheduleStateEchoesThePeriodAndPreservesTheLegacyStateList() throws Exception {
        UUID project=UUID.randomUUID(), master=UUID.randomUUID();
        var state=new CrmSyncStateDto("CATALOG","IDLE",null,null,"NEVER_LOADED","UNKNOWN",null,0);
        when(sync.state(project)).thenReturn(state); when(sync.state(project,"CATALOG")).thenReturn(state); when(clientSync.state(project)).thenReturn(state);
        var from=java.time.LocalDate.parse("2026-11-01"); var to=from.plusDays(1);
        when(schedules.state(project,master,from,to)).thenReturn(new CrmSyncStateDto("WORK_INTERVALS","PARTIAL",null,null,"STALE","UNKNOWN",null,1,
                Map.of("masterId",master.toString(),"dateFrom",from.toString(),"dateTo",to.toString()),Map.of(),Map.of("completeness","UNKNOWN")));
        mvc.perform(get("/api/crm/projects/"+project+"/sync-state")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/crm/projects/"+project+"/sync-state").param("masterId",master.toString()).param("dateFrom",from.toString()).param("dateTo",to.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[3].scope.masterId").value(master.toString()))
                .andExpect(jsonPath("$[3].scope.dateTo").value(to.toString())).andExpect(jsonPath("$[3].attemptCoverage.completeness").value("UNKNOWN"));
        mvc.perform(get("/api/crm/projects/"+project+"/sync-state").param("dateFrom",from.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(scheduleReader);
    }
    @Test void onlyAvailableTypesAreReturned() throws Exception {
        when(service.types()).thenReturn(AvailableCrmType.types());
        mvc.perform(get("/api/crm/types")).andExpect(status().isOk()).andExpect(content().json("[\"DIKIDI\"]"));
    }
    @Test void unknownTypeAndMissingVersionAreSafeBadRequests() throws Exception {
        mvc.perform(post("/api/crm/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"UNKNOWN\",\"login\":\"70000000000\",\"secret\":\"do-not-echo\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-echo"))));
        mvc.perform(patch("/api/crm/connections/"+UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"do-not-echo\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service,sync);
    }
    @Test void unsupportedKnownTypeAndProviderAuthenticationUse409NotLocal401() throws Exception {
        when(service.createConnection(any())).thenThrow(new CrmException(409,"CRM_UNSUPPORTED","Unavailable"));
        mvc.perform(post("/api/crm/connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"AMO\",\"login\":\"70000000000\",\"secret\":\"test-secret\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CRM_UNSUPPORTED"));
        UUID id = UUID.randomUUID();
        when(service.verify(id)).thenThrow(new CrmException(409,"CRM_REAUTH_REQUIRED","Check CRM credentials"));
        mvc.perform(post("/api/crm/connections/"+id+"/verify")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CRM_REAUTH_REQUIRED"));
    }
    @Test void repeatBindingReturns200AndNewBindingReturns201() throws Exception {
        UUID id = UUID.randomUUID(); String request = "{\"connectionId\":\""+UUID.randomUUID()+"\",\"externalProjectId\":\"100\"}";
        when(service.bind(eq(id),any())).thenReturn(new CrmService.Binding(null,true),new CrmService.Binding(null,false));
        mvc.perform(post("/api/crm/workspaces/"+id+"/projects").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated());
        mvc.perform(post("/api/crm/workspaces/"+id+"/projects").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
    }
    @Test void unimplementedRefreshDoesNotCallProviderAndChecksScopeFirst() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"dataset\":\"FINANCIAL_CONTEXT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CRM_CAPABILITY_UNVERIFIED"));
        verify(service).requireProjectAccess(id); verifyNoInteractions(sync);
    }
    @Test void unlinkRequiresExplicitNonnegativeVersion() throws Exception {
        mvc.perform(delete("/api/crm/masters/"+UUID.randomUUID()+"/employee-link")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }
    @Test void catalogRefreshPreservesTheLegacyMastersAlias() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"CATALOG\"}")).andExpect(status().isAccepted());
        verify(service).requireProjectAccess(id); verify(sync).refresh(id, "CATALOG");
        mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"SERVICES\"}")).andExpect(status().isAccepted());
        verify(sync).refresh(id, "SERVICES");
        mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataset\":\"MASTERS\"}")).andExpect(status().isAccepted());
        verify(sync).refresh(id);
    }
    @Test void catalogPricesAreDecimalStringsAndFiltersAreExplicit() throws Exception {
        UUID project = UUID.randomUUID(), master = UUID.randomUUID(), id = UUID.randomUUID();
        var offer = new CrmServiceOfferDto(UUID.randomUUID(), master,"m1",id,"123.4500","RUB",1800L,Map.of("online",true),Map.of(),
                "OBSERVED",java.time.Instant.parse("2026-10-28T00:00:00Z"),List.of(),0,"Master");
        when(catalog.services(project, master, id, true, null, null)).thenReturn(new CrmServicePageDto(List.of(
                new CrmServiceDto(id,project,"s1","Service",null,Map.of(),List.of(offer),"OBSERVED",offer.lastObservedAt(),0)), null,false,null));
        mvc.perform(get("/api/crm/projects/"+project+"/services").param("masterId",master.toString()).param("serviceId",id.toString())
                .param("includeNotObserved","true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].offers[0].price").isString()).andExpect(jsonPath("$.items[0].offers[0].price").value("123.4500"))
                .andExpect(jsonPath("$.items[0].offers[0].durationSeconds").value(1800));
        verifyNoInteractions(sync);
    }
    @Test void catalogControllerUsesTheSameSafeRequestErrors() throws Exception {
        mvc.perform(get("/api/crm/projects/"+UUID.randomUUID()+"/services").param("masterId","not-an-id"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(catalog);
    }
}
