package kit.penny.clientbus.server.controller;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.server.exception.ConversationAssignmentConflictException;
import kit.penny.clientbus.server.exception.GlobalExceptionHandler;
import kit.penny.clientbus.server.service.ConversationGroupService;
import kit.penny.clientbus.server.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InboxErrorResponseTest {
    @Test
    void conflictHasAStable409Code() throws Exception {
        ConversationService service = mock(ConversationService.class);
        UUID id = UUID.randomUUID();
        when(service.assignmentToMe(id)).thenThrow(new ConversationAssignmentConflictException());
        mvc(service).perform(post("/api/conversations/" + id + "/assignment/me"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONVERSATION_ASSIGNMENT_CONFLICT"));
    }

    @Test
    void forbiddenAndMissingConversationsHaveStructuredResponses() throws Exception {
        ConversationService service = mock(ConversationService.class);
        UUID forbidden = UUID.randomUUID(), missing = UUID.randomUUID();
        when(service.getConversation(forbidden)).thenThrow(new AccessDeniedException("No access"));
        when(service.getConversation(missing)).thenThrow(new EntityNotFoundException("Not found"));
        var mvc = mvc(service);
        mvc.perform(get("/api/conversations/" + forbidden)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andExpect(jsonPath("$.message").value("No access"));
        mvc.perform(get("/api/conversations/" + missing)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.MockMvc mvc(ConversationService service) {
        return MockMvcBuilders.standaloneSetup(new ConversationController(service, mock(ConversationGroupService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
