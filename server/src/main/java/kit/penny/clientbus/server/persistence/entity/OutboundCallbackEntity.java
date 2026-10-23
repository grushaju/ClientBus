package kit.penny.clientbus.server.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Correlates an early connector result with its later persisted send attempt. */
@Entity
@Table(name="outboundsendcallback",schema="dbo",uniqueConstraints={
        @UniqueConstraint(name="outboundsendcallbackpk",columnNames={"id"}),
        @UniqueConstraint(name="outboundcallbackkeyuq",columnNames={"channelaccountid","recipientexternalid","pendingexternalid"})},
        indexes={@Index(name="outboundcallbackretentionidx",columnList="retainuntil")})
public class OutboundCallbackEntity {
    @Id @Column(nullable=false) private UUID id;
    @Column(name="channelaccountid",nullable=false,updatable=false) private UUID channelAccountId;
    @Column(name="recipientexternalid",nullable=false,updatable=false,length=256) private String recipientExternalId;
    @Column(name="pendingexternalid",nullable=false,updatable=false,length=256) private String pendingExternalId;
    @Column(name="messageid") private UUID messageId;
    @Column(name="sendattemptid") private UUID sendAttemptId;
    @Column(name="outcome",length=16) private String outcome;
    @Column(name="finalexternalid",length=256) private String finalExternalId;
    @Column(name="createdat",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="retainuntil",nullable=false) private Instant retainUntil;
    public OutboundCallbackEntity() {}
}
