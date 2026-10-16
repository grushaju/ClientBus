
package kit.penny.clientbus.server.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kit.penny.clientbus.common.dto.conversation.SaveConversationFilterRequest;
import kit.penny.clientbus.common.dto.conversation.SavedConversationFilterDto;
import kit.penny.clientbus.common.enums.SavedFilterType;
import kit.penny.clientbus.server.service.SavedConversationFilterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversation-filters")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "Сохранённые фильтры диалогов",
        description = "API для личных и системных фильтров Inbox"
)
@PreAuthorize("isAuthenticated()")
public class SavedConversationFilterController {

    private final SavedConversationFilterService filterService;

    public SavedConversationFilterController(
            SavedConversationFilterService filterService
    ) {
        this.filterService = filterService;
    }

    /**
     * Получить сохранённые фильтры выбранного типа.
     *
     * PRIVATE — фильтры текущего сотрудника.
     * SYSTEM — общие фильтры Workspace.
     */
    @GetMapping
    public ResponseEntity<List<SavedConversationFilterDto>> getFilters(
            @PathVariable UUID workspaceId,
            @RequestParam SavedFilterType filterType
    ) {
        return ResponseEntity.ok(
                filterService.getFilters(workspaceId, filterType)
        );
    }

    /**
     * Создать сохранённый фильтр.
     */
    @PostMapping
    public ResponseEntity<SavedConversationFilterDto> createFilter(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody SaveConversationFilterRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(filterService.createFilter(workspaceId, request));
    }

    /**
     * Изменить сохранённый фильтр.
     */
    @PutMapping("/{filterId}")
    public ResponseEntity<SavedConversationFilterDto> updateFilter(
            @PathVariable UUID workspaceId,
            @PathVariable UUID filterId,
            @Valid @RequestBody SaveConversationFilterRequest request
    ) {
        return ResponseEntity.ok(
                filterService.updateFilter(workspaceId, filterId, request)
        );
    }

    /**
     * Удалить сохранённый фильтр.
     */
    @DeleteMapping("/{filterId}")
    public ResponseEntity<Void> deleteFilter(
            @PathVariable UUID workspaceId,
            @PathVariable UUID filterId
    ) {
        filterService.deleteFilter(workspaceId, filterId);
        return ResponseEntity.noContent().build();
    }
}
