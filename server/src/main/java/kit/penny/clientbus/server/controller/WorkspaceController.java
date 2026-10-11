package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.workspace.CreateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.UpdateWorkspaceRequest;
import kit.penny.clientbus.common.dto.workspace.WorkspaceDto;
import kit.penny.clientbus.server.service.WorkspaceService;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
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
    private final IAttachmentStorage attachmentStorage;

    public WorkspaceController(
            WorkspaceService workspaceService,
            IAttachmentStorage attachmentStorage
    ) {
        this.workspaceService = workspaceService;
        this.attachmentStorage = attachmentStorage;
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

    @GetMapping("/{id}/avatar/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InputStreamResource> getWorkspaceAvatar(
            @PathVariable UUID id
    ) {
        WorkspaceDto workspace = workspaceService.getWorkspace(id);
        String storageKey = workspace.avatarUrl();

        if (storageKey == null || storageKey.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        InputStream inputStream = attachmentStorage.load(storageKey);

        return ResponseEntity.ok()
                .contentType(
                        MediaTypeFactory.getMediaType(storageKey)
                                .orElse(MediaType.APPLICATION_OCTET_STREAM)
                )
                .body(new InputStreamResource(inputStream));
    }

    @PutMapping(
            value = "/{id}/avatar",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<WorkspaceDto> uploadWorkspaceAvatar(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(
                workspaceService.uploadWorkspaceAvatar(id, file)
        );
    }

    @DeleteMapping("/{id}/avatar")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<WorkspaceDto> clearWorkspaceAvatar(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(
                workspaceService.clearWorkspaceAvatar(id)
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