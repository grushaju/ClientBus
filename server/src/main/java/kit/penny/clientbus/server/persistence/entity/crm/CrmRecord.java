package kit.penny.clientbus.server.persistence.entity.crm;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class CrmRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Version @Column(name = "version", nullable = false)
    private long version;
    @CreationTimestamp @Column(name = "createdat", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updatedat", nullable = false)
    private Instant updatedAt;
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

