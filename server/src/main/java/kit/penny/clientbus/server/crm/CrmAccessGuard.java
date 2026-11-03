package kit.penny.clientbus.server.crm;

import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.clientbus.server.security.policy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class CrmAccessGuard {
    private final CurrentUserService current;
    private final AccessPolicy access;
    private final JdbcTemplate jdbc;
    public CrmAccessGuard(CurrentUserService current, AccessPolicy access, JdbcTemplate jdbc) {
        this.current = current; this.access = access; this.jdbc = jdbc;
    }
    public UUID actor() { return current.getCurrentEmployeeId(); }
    public UUID organization() {
        var rows = jdbc.queryForList("select e.organizationid from dbo.employee e join dbo.users u on u.userid=e.userid where e.employeeid=? and u.isenabled and u.role in ('EMPLOYEE','SUPER_ADMIN')", UUID.class, actor());
        if (rows.size() != 1) throw new AccessDeniedException("Access denied");
        return rows.getFirst();
    }
    public UUID admin() {
        UUID organization = organization();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from dbo.employee e join dbo.users u on u.userid=e.userid where e.employeeid=? and e.organizationid=? and u.isenabled and u.role='SUPER_ADMIN')", Boolean.class, actor(), organization)))
            throw new AccessDeniedException("Access denied");
        return organization;
    }
    public void workspace(UUID workspaceId, boolean manage) {
        access.require(AccessSubject.recipient(actor()), manage ? AccessPolicy.Resource.WORKSPACE_MANAGEMENT : AccessPolicy.Resource.WORKSPACE, workspaceId);
    }
    public void workspace(UUID actorId, UUID workspaceId) {
        access.require(AccessSubject.recipient(actorId), AccessPolicy.Resource.WORKSPACE, workspaceId);
    }
    public void employee(UUID employeeId) {
        access.require(AccessSubject.recipient(actor()), AccessPolicy.Resource.EMPLOYEE, employeeId);
    }
    public boolean canReadEmployee(UUID employeeId) {
        return access.allows(AccessSubject.recipient(actor()), AccessPolicy.Resource.EMPLOYEE, employeeId);
    }
}
