package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Рабочие пространства",
        description = "API для управления рабочими пространствами"
)
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(
            WorkspaceService workspaceService
    ) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<WorkspaceDto> createWorkspace(
            @Valid
            @RequestBody CreateWorkspaceRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        workspaceService.createWorkspace(
                                request
                        )
                );
    }

    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<WorkspaceDto>>
    getCurrentUserWorkspaces() {

        return ResponseEntity.ok(
                workspaceService.getCurrentUserWorkspaces()
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkspaceDto> getWorkspace(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                workspaceService.getWorkspace(id)
        );
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<WorkspaceDto>>
    getAllWorkspaces() {

        return ResponseEntity.ok(
                workspaceService.getAllWorkspaces()
        );
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<WorkspaceDto>>
    getWorkspacesByOrganization(
            @PathVariable UUID organizationId
    ) {

        return ResponseEntity.ok(
                workspaceService.getWorkspacesByOrganization(
                        organizationId
                )
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<WorkspaceDto> updateWorkspace(
            @PathVariable UUID id,
            @Valid
            @RequestBody UpdateWorkspaceRequest request
    ) {

        return ResponseEntity.ok(
                workspaceService.updateWorkspace(
                        id,
                        request
                )
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteWorkspace(
            @PathVariable UUID id
    ) {

        workspaceService.deleteWorkspace(id);

        return ResponseEntity.noContent().build();
    }
}