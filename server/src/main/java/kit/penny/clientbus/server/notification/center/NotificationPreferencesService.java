package kit.penny.clientbus.server.notification.center;

import kit.penny.clientbus.common.dto.notification.NotificationPreferencesDto;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Self-service only: the recipient always comes from the verified session, never a request ID. */
@Service
public class NotificationPreferencesService {
    private static final String ACTIVE = "u.isenabled and u.role in ('EMPLOYEE','SUPER_ADMIN')";
    private final NamedParameterJdbcTemplate jdbc;
    private final PersonalNotificationEvents events;

    public NotificationPreferencesService(NamedParameterJdbcTemplate jdbc, PersonalNotificationEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesDto get(AccessSubject subject) {
        requireCurrentSession(subject);
        return jdbc.query("""
                select coalesce(e.notificationsoundenabled,true) from dbo.employee e
                join dbo.users u on u.userid=e.userid where e.employeeid=:recipient and
                """ + ACTIVE, Map.of("recipient", subject.employeeId()),
                (rs, row) -> new NotificationPreferencesDto(rs.getBoolean(1))).stream().findFirst()
                .orElseThrow(() -> new NotificationApiException(403, "FORBIDDEN"));
    }

    @Transactional
    public NotificationPreferencesDto update(AccessSubject subject, Boolean enabled) {
        requireCurrentSession(subject);
        if (enabled == null) throw new NotificationApiException(400, "INVALID_REQUEST");
        // One conditional statement serializes concurrent preference changes. Profile-only ORM updates
        // use @DynamicUpdate and cannot write a stale copy of this independently updated column.
        var changed = jdbc.query("""
                update dbo.employee e set notificationsoundenabled=:enabled, updatedat=clock_timestamp()
                from dbo.users u where u.userid=e.userid and e.employeeid=:recipient and
                """ + ACTIVE + " and coalesce(e.notificationsoundenabled,true) is distinct from :enabled returning e.organizationid",
                Map.of("recipient", subject.employeeId(), "enabled", enabled),
                (rs, row) -> rs.getObject(1, UUID.class));
        if (changed.isEmpty()) return get(subject);
        events.publish(changed.getFirst(), subject.employeeId(), null, false);
        return new NotificationPreferencesDto(enabled);
    }

    private void requireCurrentSession(AccessSubject subject) {
        if (subject.expired(Instant.now())) throw new NotificationApiException(401, "UNAUTHENTICATED");
    }
}
