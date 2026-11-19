package kit.penny.clientbus.server.crm;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmSyncCoordinatorTest {
    @Test void expiredQueueOwnershipNeverCallsTheProvider(){
        var queue=mock(CrmSyncQueue.class);var clients=mock(CrmClientSync.class);
        var coordinator=coordinator(queue,clients);var job=job();
        try{
            when(queue.heartbeat(job)).thenReturn(false);
            ReflectionTestUtils.invokeMethod(coordinator,"execute",job);
            verifyNoInteractions(clients);
            verify(queue).finish(job,"CRM_REFRESH_EXPIRED",false);
        }finally{coordinator.close();}
    }
    @Test void fatalWorkerErrorsNeverRecordASuccess(){
        var queue=mock(CrmSyncQueue.class);var clients=mock(CrmClientSync.class);
        var coordinator=coordinator(queue,clients);var job=job();
        try{
            when(queue.heartbeat(job)).thenReturn(true);
            when(clients.runBackground(job.project(),job.organization(),false,null)).thenThrow(new AssertionError("test failure"));
            assertThrows(AssertionError.class,()->ReflectionTestUtils.invokeMethod(coordinator,"execute",job));
            verify(queue).finish(job,"CRM_TEMPORARILY_UNAVAILABLE",false);
        }finally{coordinator.close();}
    }
    private static CrmSyncQueue.Job job(){return new CrmSyncQueue.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),CrmSyncProperties.Dataset.CLIENTS,"ALL",null,null,null,null,UUID.randomUUID(),0,false,null,false,null);}
    private static CrmSyncCoordinator coordinator(CrmSyncQueue queue,CrmClientSync clients){return new CrmSyncCoordinator(new CrmSyncProperties(),queue,mock(JdbcTemplate.class),mock(CrmStorage.class),mock(CrmCatalogSync.class),clients,mock(CrmAppointmentSync.class),mock(CrmScheduleSync.class),mock(CrmFinanceService.class),mock(CrmAppointmentTimes.class),mock(CrmAccessGuard.class));}
}
