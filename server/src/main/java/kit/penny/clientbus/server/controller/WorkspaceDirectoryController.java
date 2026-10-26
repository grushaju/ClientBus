package kit.penny.clientbus.server.controller;

import kit.penny.clientbus.common.dto.employee.EmployeeWorkspaceDto;
import kit.penny.clientbus.common.dto.workspace.WorkspaceStatisticsDto;
import kit.penny.clientbus.server.security.policy.HttpAccessPolicy;
import kit.penny.clientbus.server.service.WorkspaceDirectoryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class WorkspaceDirectoryController {
    private final WorkspaceDirectoryService directory;
    private final HttpAccessPolicy access;
    public WorkspaceDirectoryController(WorkspaceDirectoryService directory, HttpAccessPolicy access) {
        this.directory = directory; this.access = access;
    }

    @GetMapping("/api/employees/organization/{id}/memberships")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<EmployeeWorkspaceDto>> organizationMemberships(@PathVariable UUID id) {
        return ok(directory.organizationMemberships(access.sessionSubject(), id));
    }

    @GetMapping("/api/workspaces/{id}/memberships")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','EMPLOYEE')")
    public ResponseEntity<List<EmployeeWorkspaceDto>> workspaceMemberships(@PathVariable UUID id) {
        return ok(directory.workspaceMemberships(access.sessionSubject(), id));
    }

    @GetMapping("/api/workspaces/statistics")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<WorkspaceStatisticsDto>> statistics() {
        return ok(directory.statistics(access.sessionSubject()));
    }

    private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
