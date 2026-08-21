package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.organization.CreateOrganizationRequest;
import kit.penny.clientbus.common.dto.organization.OrganizationDto;
import kit.penny.clientbus.common.dto.organization.UpdateOrganizationRequest;
import kit.penny.clientbus.server.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizations")
@Tag(
        name = "Организации",
        description = "API для управления организациями"
)
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(
            OrganizationService organizationService
    ) {
        this.organizationService = organizationService;
    }

    @PostMapping
    public ResponseEntity<OrganizationDto> createOrganization(
            @Valid
            @RequestBody CreateOrganizationRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        organizationService.createOrganization(
                                request
                        )
                );
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationDto> getOrganization(
            @PathVariable UUID id
    ) {

        return ResponseEntity.ok(
                organizationService.getOrganization(id)
        );
    }

    @GetMapping("/all")
    public ResponseEntity<List<OrganizationDto>>
    getAllOrganizations() {

        return ResponseEntity.ok(
                organizationService.getAllOrganizations()
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrganizationDto> updateOrganization(
            @PathVariable UUID id,
            @Valid
            @RequestBody UpdateOrganizationRequest request
    ) {

        return ResponseEntity.ok(
                organizationService.updateOrganization(
                        id,
                        request
                )
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrganization(
            @PathVariable UUID id
    ) {

        organizationService.deleteOrganization(id);

        return ResponseEntity.noContent().build();
    }
}