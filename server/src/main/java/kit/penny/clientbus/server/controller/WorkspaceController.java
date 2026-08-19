package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.service.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
@Tag(name = "Рабочие пространства", description = "API для управления рабочими пространствами")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(
            WorkspaceService workspaceService
    ) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    public ResponseEntity<WorkspaceDto> createWorkspace(
            @RequestBody CreateWorkspaceRequest request
    ) {

        WorkspaceDto created =
                workspaceService.createWorkspace(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkspaceDto> getWorkspace(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                workspaceService.getWorkspace(id)
        );
    }

    @GetMapping("/all")
    public ResponseEntity<List<WorkspaceDto>> getAllWorkspaces() {

        return ResponseEntity.ok(
                workspaceService.getAllWorkspaces()
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkspaceDto> updateWorkspace(
            @PathVariable UUID id,
            @RequestBody UpdateWorkspaceRequest request
    ) {

        return ResponseEntity.ok(
                workspaceService.updateWorkspace(id, request)
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkspace(
            @PathVariable UUID id
    ) {

        workspaceService.deleteWorkspace(id);

        return ResponseEntity.noContent().build();
    }
}
