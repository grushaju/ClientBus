package kit.penny.clientbus.server.notification.push;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class PushDeliveryStoreTest {
    @Test void exponentialBackoffHasBoundedJitterAndSixtySecondCap(){
        assertEquals(800,PushDeliveryStore.retryDelayMillis(1,null,0,300_000));
        assertEquals(1000,PushDeliveryStore.retryDelayMillis(1,null,0.5,300_000));
        assertEquals(1200,PushDeliveryStore.retryDelayMillis(1,null,1,300_000));
        assertEquals(1600,PushDeliveryStore.retryDelayMillis(2,null,0,300_000));
        assertEquals(2400,PushDeliveryStore.retryDelayMillis(2,null,1,300_000));
        for(double entropy:new double[]{0,0.5,1}){
            long capped=PushDeliveryStore.retryDelayMillis(100,null,entropy,300_000);
            assertTrue(capped>=48_000 && capped<=60_000);
        }
    }
    @Test void retryAfterIsAMinimumAndCannotExtendTheOriginalTtl(){
        for(double entropy:new double[]{0,0.5,1})
            assertEquals(5000,PushDeliveryStore.retryDelayMillis(1,Duration.ofSeconds(5),entropy,300_000));
        assertEquals(5001,PushDeliveryStore.retryDelayMillis(1,Duration.ofMillis(5000).plusNanos(1),0,300_000));
        assertEquals(2000,PushDeliveryStore.retryDelayMillis(1,Duration.ofSeconds(5),0,2000));
        assertEquals(2000,PushDeliveryStore.retryDelayMillis(1,Duration.ofSeconds(Long.MAX_VALUE),0,2000));
        assertEquals(0,PushDeliveryStore.retryDelayMillis(1,Duration.ofSeconds(5),0,0));
    }
}
