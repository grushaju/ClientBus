package kit.penny.clientbus.server.notification.transport;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class UiGatewayPropertiesTest {
    private UiGatewayProperties config(String id,int records,int bytes,boolean cleanup) {
        return new UiGatewayProperties(id,256,4,8,256,Duration.ofSeconds(5),Duration.ofSeconds(2),
                Duration.ofSeconds(2),Duration.ofSeconds(10),records,bytes,cleanup,1800,120,5);
    }
    @Test void generatedInstanceIdsAreIndependentAndConsumerBudgetsAreValidated() {
        var ids=new HashSet<String>();
        for(int i=0;i<100;i++) assertTrue(ids.add(config("",100,1048576,true).instanceId()));
        for(int records:new int[]{0,1001}) assertThrows(IllegalArgumentException.class,()->config("test",records,1048576,true));
        for(int bytes:new int[]{65535,16777217}) assertThrows(IllegalArgumentException.class,()->config("test",100,bytes,true));
        assertThrows(IllegalArgumentException.class,()->config("invalid/name",100,1048576,true));
    }
    @Test void cleanupCanBeDisabledWhenAdministrativePermissionsAreUnavailable() {
        assertFalse(config("managed",100,1048576,false).cleanupGroupOnStop());
        assertTrue(config("automatic",100,1048576,true).cleanupGroupOnStop());
    }
    /**
     * Срок жизни потока, порог «вставшего» потока и Retry-After берутся из конфигурации: раньше
     * получасовой потолок был зашит в код, и абранный клиентом поток держал слот собственного
     * лимита сотрудника полчаса (15.11 — «employee streams 4/4» при одном живом клиенте).
     */
    @Test void streamLifetimeStallDeadlineAndRetryAfterAreConfigurableAndValidated() {
        var configured=config("test",100,1048576,true);
        assertEquals(Duration.ofSeconds(1800),configured.streamTtl());
        assertEquals(Duration.ofSeconds(120),configured.stalledAfter());
        assertEquals(5,configured.retryAfterSeconds());
        for(int ttl:new int[]{0,59,86401}) assertThrows(IllegalArgumentException.class,
                ()->with(5_000,ttl,120,5));
        for(int retry:new int[]{0,301}) assertThrows(IllegalArgumentException.class,()->with(1800,120,retry));
        // Порог вставшего потока короче write-дедлайна означал бы, что поток закрывается раньше,
        // чем успевает состояться честный таймаут записи, и настоящая причина терялась бы.
        assertThrows(IllegalArgumentException.class,()->with(5_000,1800,4,5));
    }
    /** Имена ключей из application.yml обязаны биндиться в компоненты записи. */
    @Test void streamKeysBindFromConfigurationNames() {
        var bound=new Binder(new MapConfigurationPropertySource(Map.of(
                "clientbus.notifications.gateway.stream-ttl-seconds","600",
                "clientbus.notifications.gateway.stalled-after-seconds","30",
                "clientbus.notifications.gateway.retry-after-seconds","9")))
                .bindOrCreate("clientbus.notifications.gateway",UiGatewayProperties.class);
        assertEquals(Duration.ofSeconds(600),bound.streamTtl());
        assertEquals(Duration.ofSeconds(30),bound.stalledAfter());
        assertEquals(9,bound.retryAfterSeconds());
    }
    private static UiGatewayProperties with(int writeMillis,int ttl,int stalled,int retry) {
        return new UiGatewayProperties("test",256,4,8,256,Duration.ofMillis(writeMillis),Duration.ofSeconds(2),
                Duration.ofSeconds(2),Duration.ofSeconds(10),100,1048576,true,ttl,stalled,retry);
    }
    private static UiGatewayProperties with(int ttl,int stalled,int retry) {
        return with(5_000,ttl,stalled,retry);
    }
}
