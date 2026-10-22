package kit.penny.clientbus.server.notification;

import kit.penny.clientbus.server.security.policy.*;
import kit.penny.clientbus.server.security.service.CurrentUserService;
import kit.penny.clientbus.server.security.jwt.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AccessPolicyFailureTest {
    @Test void databaseFailureNeverTurnsIntoPermission() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var policy=new AccessPolicy(jdbc);
        assertThrows(DataAccessResourceFailureException.class,()->policy.allows(
                AccessSubject.recipient(UUID.randomUUID()),AccessPolicy.Resource.ORGANIZATION,UUID.randomUUID()));
        assertThrows(DataAccessResourceFailureException.class,()->policy.allowsPush(UUID.randomUUID()));
    }

    @Test void sessionSubjectRetainsVerifiedExpiryWithoutRetainingToken() {
        var current=mock(CurrentUserService.class);
        UUID id=UUID.randomUUID(); when(current.getCurrentEmployeeId()).thenReturn(id);
        var http=new HttpAccessPolicy(current,mock(AccessPolicy.class));
        var authentication=new UsernamePasswordAuthenticationToken("user",null,List.of());
        Instant expiry=Instant.now().plusSeconds(30);
        authentication.setDetails(new JwtAuthenticationFilter.VerifiedSession(expiry));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var subject=http.sessionSubject();
            assertEquals(id,subject.employeeId()); assertEquals(expiry,subject.expiresAt());
            assertTrue(subject.expired(expiry));
            authentication.setDetails(null);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,http::sessionSubject);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
