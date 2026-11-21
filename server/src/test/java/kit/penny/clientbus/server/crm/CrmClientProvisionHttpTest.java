package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.common.dto.crm.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrmClientProvisionHttpTest {
    @Test void absentOperationIsAnExplicitJsonEnvelopeAndPostRequiresAnIdempotencyKey() throws Exception {
        var service=mock(CrmClientProvisioning.class);
        var mvc=MockMvcBuilders.standaloneSetup(new CrmClientProvisionController(service)).setControllerAdvice(new CrmRequestErrors()).build();
        UUID project=UUID.randomUUID(),client=UUID.randomUUID(),key=UUID.randomUUID();
        mvc.perform(get("/api/crm/projects/"+project+"/client-provisions/latest").param("clientId",client.toString()))
                .andExpect(status().isOk()).andExpect(content().json("{\"operation\":null}"));
        String body="{\"clientId\":\""+client+"\",\"contextVersion\":1,\"firstName\":\"Anna\",\"lastName\":\"\",\"phone\":\"79991234567\",\"differentPerson\":false}";
        mvc.perform(post("/api/crm/projects/"+project+"/client-provisions").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verify(service,never()).create(any(),any(),any());
        when(service.create(eq(project),eq(key),any())).thenReturn(new CrmClientProvisionDto(UUID.randomUUID(),project,client,"REQUESTED",null,null,Instant.now()));
        mvc.perform(post("/api/crm/projects/"+project+"/client-provisions").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("REQUESTED"));
    }
}
