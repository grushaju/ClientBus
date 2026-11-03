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
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(CrmService.class); sync = mock(CrmCatalogSync.class); catalog = mock(CrmCatalogReader.class);
        mvc = MockMvcBuilders.standaloneSetup(new CrmController(service,sync), new CrmCatalogController(catalog))
                .setControllerAdvice(new CrmRequestErrors(), new GlobalExceptionHandler()).build();
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
        mvc.perform(post("/api/crm/projects/"+id+"/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"dataset\":\"APPOINTMENTS\"}"))
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
