package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.dto.employee.EmployeeWorkspaceDto;
import kit.penny.clientbus.common.dto.workspace.WorkspaceStatisticsDto;
import kit.penny.clientbus.server.security.policy.AccessPolicy;
import kit.penny.clientbus.server.security.policy.AccessSubject;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models for directory screens: a fixed number of requests regardless of directory size. */
@Service
@Transactional(readOnly = true)
public class WorkspaceDirectoryService {
    private static final String ADMIN = """
            exists(select 1 from dbo.employee actor join dbo.users au on au.userid=actor.userid
                where actor.employeeid=:actor and actor.organizationid=:organization and au.isenabled and au.role='SUPER_ADMIN')
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessPolicy access;

    public WorkspaceDirectoryService(NamedParameterJdbcTemplate jdbc, AccessPolicy access) {
        this.jdbc = jdbc; this.access = access;
    }

    public List<EmployeeWorkspaceDto> organizationMemberships(AccessSubject subject, UUID organization) {
        adminOrganization(subject, organization);
        return jdbc.query("""
                select m.employeeid,m.workspaceid,m.issupervisor from dbo.employeeworkspace m
                join dbo.employee e on e.employeeid=m.employeeid join dbo.workspace w on w.workspaceid=m.workspaceid
                where e.organizationid=:organization and w.organizationid=:organization and
                """ + ADMIN, Map.of("actor", subject.employeeId(), "organization", organization),
                (rs, row) -> new EmployeeWorkspaceDto(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)));
    }

    public List<EmployeeWorkspaceDto> workspaceMemberships(AccessSubject subject, UUID workspace) {
        access.require(subject, AccessPolicy.Resource.WORKSPACE_MANAGEMENT, workspace);
        return jdbc.query("""
                select m.employeeid,m.workspaceid,m.issupervisor from dbo.employeeworkspace m
                join dbo.workspace w on w.workspaceid=m.workspaceid join dbo.employee e on e.employeeid=m.employeeid
                join dbo.employee actor on actor.employeeid=:actor join dbo.users au on au.userid=actor.userid
                where w.workspaceid=:workspace and e.organizationid=w.organizationid and actor.organizationid=w.organizationid
                    and au.isenabled and (au.role='SUPER_ADMIN' or (au.role='EMPLOYEE' and exists(
                        select 1 from dbo.employeeworkspace am where am.employeeid=actor.employeeid
                            and am.workspaceid=w.workspaceid and am.issupervisor)))
                """, Map.of("actor", subject.employeeId(), "workspace", workspace),
                (rs, row) -> new EmployeeWorkspaceDto(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)));
    }

    public List<WorkspaceStatisticsDto> statistics(AccessSubject subject) {
        UUID organization = currentAdminOrganization(subject);
        return jdbc.query("""
                with scope as (select w.workspaceid from dbo.workspace w where w.organizationid=:organization and
                """ + ADMIN + """
                ), people as (
                    select m.workspaceid,count(*) as total from dbo.employeeworkspace m join scope s on s.workspaceid=m.workspaceid
                    join dbo.employee e on e.employeeid=m.employeeid join dbo.users u on u.userid=e.userid
                    where e.organizationid=:organization and u.role<>'SUPER_ADMIN' group by m.workspaceid
                ), channels as (
                    select c.workspaceid,count(*) as total from dbo.channel c join scope s on s.workspaceid=c.workspaceid group by c.workspaceid
                ), conversations as (
                    select c.workspaceid,count(*) as total,coalesce(sum(c.unreadcount),0) as unread,max(c.lastmessageat) as activity
                    from dbo.conversation c join scope s on s.workspaceid=c.workspaceid group by c.workspaceid
                ) select s.workspaceid,coalesce(p.total,0),coalesce(ch.total,0),coalesce(c.total,0),coalesce(c.unread,0),c.activity
                from scope s left join people p on p.workspaceid=s.workspaceid left join channels ch on ch.workspaceid=s.workspaceid
                left join conversations c on c.workspaceid=s.workspaceid order by s.workspaceid
                """, Map.of("actor", subject.employeeId(), "organization", organization),
                (rs, row) -> new WorkspaceStatisticsDto(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()));
    }

    private UUID currentAdminOrganization(AccessSubject subject) {
        if (subject.expired(Instant.now())) throw new AccessDeniedException("Access denied");
        return jdbc.query("""
                select e.organizationid from dbo.employee e join dbo.users u on u.userid=e.userid
                where e.employeeid=:actor and u.isenabled and u.role='SUPER_ADMIN'
                """, Map.of("actor", subject.employeeId()), (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Administrator access required"));
    }

    private void adminOrganization(AccessSubject subject, UUID organization) {
        if (!currentAdminOrganization(subject).equals(organization)) throw new AccessDeniedException("Access denied");
    }
}
