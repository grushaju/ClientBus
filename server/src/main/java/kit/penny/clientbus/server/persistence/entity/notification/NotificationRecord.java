package kit.penny.clientbus.server.persistence.entity.notification;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import java.time.Instant;
import java.util.UUID;

/** DB clock is shared with stream.ready; createdAt is not a commit/replay cursor. */
@MappedSuperclass
public abstract class NotificationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    @ColumnDefault("clock_timestamp()")
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Instant getCreatedAt() { return createdAt; }
}
