package kit.penny.clientbus.server.controller;

import kit.penny.clientbus.common.dto.conversation.ConversationSearchRequest;
import kit.penny.clientbus.common.dto.conversation.SaveConversationFilterRequest;
import kit.penny.clientbus.common.dto.conversation.SavedConversationFilterDto;
import kit.penny.clientbus.common.enums.SavedFilterType;
import kit.penny.clientbus.server.service.SavedConversationFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SavedConversationFilterControllerTest {

    @Mock
    private SavedConversationFilterService filterService;

    private SavedConversationFilterController controller;

    private UUID workspaceId;
    private UUID filterId;
    private SaveConversationFilterRequest request;
    private SavedConversationFilterDto filterDto;

    @BeforeEach
    void setUp() {
        controller = new SavedConversationFilterController(filterService);

        workspaceId = UUID.randomUUID();
        filterId = UUID.randomUUID();

        ConversationSearchRequest definition =
                new ConversationSearchRequest(
                        null, null, null, null, null,
                        null, null, null, null, null,
                        0, 20
                );

        request = new SaveConversationFilterRequest(
                "Мои новые диалоги",
                SavedFilterType.PRIVATE,
                definition
        );

        filterDto = new SavedConversationFilterDto(
                filterId,
                request.name(),
                request.filterType(),
                request.filterDefinition(),
                null,
                null
        );
    }

    @Test
    void getFilters_returnsFiltersForRequestedType() {
        List<SavedConversationFilterDto> filters = List.of(filterDto);

        when(filterService.getFilters(workspaceId, SavedFilterType.PRIVATE))
                .thenReturn(filters);

        ResponseEntity<List<SavedConversationFilterDto>> response =
                controller.getFilters(workspaceId, SavedFilterType.PRIVATE);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(filters, response.getBody());

        verify(filterService).getFilters(workspaceId, SavedFilterType.PRIVATE);
    }

    @Test
    void createFilter_returnsCreatedFilter() {
        when(filterService.createFilter(workspaceId, request))
                .thenReturn(filterDto);

        ResponseEntity<SavedConversationFilterDto> response =
                controller.createFilter(workspaceId, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(filterDto, response.getBody());

        verify(filterService).createFilter(workspaceId, request);
    }

    @Test
    void updateFilter_returnsUpdatedFilter() {
        when(filterService.updateFilter(workspaceId, filterId, request))
                .thenReturn(filterDto);

        ResponseEntity<SavedConversationFilterDto> response =
                controller.updateFilter(workspaceId, filterId, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(filterDto, response.getBody());

        verify(filterService).updateFilter(workspaceId, filterId, request);
    }

    @Test
    void deleteFilter_returnsNoContent() {
        ResponseEntity<Void> response =
                controller.deleteFilter(workspaceId, filterId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());

        verify(filterService).deleteFilter(workspaceId, filterId);
    }
}