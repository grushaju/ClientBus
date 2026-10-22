package kit.penny.clientbus.server.notification.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kit.penny.clientbus.common.dto.notification.UiResourceRef;
import kit.penny.clientbus.common.notification.EventOrigin;
import kit.penny.clientbus.server.config.properties.NotificationProperties;
import kit.penny.clientbus.server.notification.contract.UiInvalidation;
import kit.penny.clientbus.server.notification.transport.UiKafkaSubscriber;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class UiOutboxRelayTest {
    @Test void metricsCanSampleTheQueueWhilePublicationIsBlocked() throws Exception {
        var store=mock(UiOutboxStore.class);var publisher=mock(UiOutboxPublisher.class);
        var mapper=JsonMapper.builder().build();var registry=new SimpleMeterRegistry();
        var config=new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("clientbus.notifications",NotificationProperties.class);
        var relay=new UiOutboxRelay(store,publisher,config,mapper,registry);
        var metrics=new UiOutboxMetrics(store,registry);
        var event=new UiInvalidation(UUID.randomUUID(),1,"messages.invalidated",Instant.now(),UUID.randomUUID(),UUID.randomUUID(),
                new UiResourceRef("conversation",UUID.randomUUID()),UiInvalidation.Audience.CONVERSATION,Set.of(),EventOrigin.LIVE,null,null);
        var claim=new UiOutboxStore.Claim(UUID.randomUUID(),event.eventId(),event.organizationId(),UiKafkaSubscriber.TOPIC,
                event.type(),1,mapper.writeValueAsString(event),null,1,1);
        when(store.claim()).thenReturn(Optional.of(claim),Optional.empty());
        when(store.statistics()).thenReturn(Map.of("pending",1,"quarantined",0,"oldest_seconds",10));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return null;})
                .when(publisher).publish(anyString(),anyString());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var publication=executor.submit(relay::tick);
            try {
                assertTrue(entered.await(1,TimeUnit.SECONDS));
                metrics.sample();
                assertEquals(1,registry.get("clientbus.notification.outbox.pending").gauge().value());
                assertEquals(1,registry.get("clientbus.notification.outbox.sample.valid").gauge().value());
                assertFalse(publication.isDone());
            } finally {release.countDown();}
            publication.get(2,TimeUnit.SECONDS);
        }
    }
    @Test void ackTimeoutRetriesWithStableEventAndPermanentErrorsQuarantine() throws Exception {
        var store=mock(UiOutboxStore.class);var publisher=mock(UiOutboxPublisher.class);
        var mapper=JsonMapper.builder().build();var registry=new SimpleMeterRegistry();
        var config=new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("clientbus.notifications",NotificationProperties.class);
        var relay=new UiOutboxRelay(store,publisher,config,mapper,registry);
        var event=new UiInvalidation(UUID.randomUUID(),1,"messages.invalidated",Instant.now(),UUID.randomUUID(),UUID.randomUUID(),
                new UiResourceRef("conversation",UUID.randomUUID()),UiInvalidation.Audience.CONVERSATION,Set.of(),EventOrigin.LIVE,null,null);
        var claim=new UiOutboxStore.Claim(UUID.randomUUID(),event.eventId(),event.organizationId(),UiKafkaSubscriber.TOPIC,
                event.type(),1,mapper.writeValueAsString(event),null,1,1);
        doThrow(new TimeoutException()).doNothing().when(publisher).publish(event.organizationId().toString(),claim.payload());
        relay.deliver(claim);verify(store).failed(claim,false,"TRANSIENT_PUBLISH");verify(store,never()).published(any());
        relay.deliver(claim);verify(store).published(claim);
        doThrow(new org.apache.kafka.common.errors.TopicAuthorizationException(Set.of(UiKafkaSubscriber.TOPIC)))
                .when(publisher).publish(anyString(),anyString());
        relay.deliver(claim);verify(store).failed(claim,true,"PERMANENT_PUBLISH");
    }
}
