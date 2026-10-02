package com.lytspeed.orka.security;

import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductAccessFilterTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AccessScopeService scopes = mock(AccessScopeService.class);
    private final ProductAccessFilter filter = new ProductAccessFilter(users, scopes);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-firebase-user", null, List.of()));
    }

    private MockHttpServletResponse request(String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test void missingAuthenticationReturnsJson401WithoutErrorDispatch() throws Exception {
        var response = request("/api/app-users/me");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
        assertTrue(response.getContentType().startsWith("application/json"));
        assertNull(response.getErrorMessage());
        verifyNoInteractions(users, chain);
    }

    @Test void verifiedIdentityWithoutProfileGetsDistinct403() throws Exception {
        authenticate();
        when(users.findByFirebaseUid("test-firebase-user")).thenReturn(Optional.empty());
        var response = request("/api/app-users/me");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("PROFILE_MISSING"));
        assertNull(response.getErrorMessage());
        verifyNoInteractions(chain);
    }

    @Test void pendingProfileCanReadOwnApplicationButNotOperations() throws Exception {
        authenticate();
        var actor = new AppUser();
        actor.setStatus(AppUserStatus.PENDING_APPROVAL);
        actor.setActive(true);
        when(users.findByFirebaseUid("test-firebase-user")).thenReturn(Optional.of(actor));
        assertEquals(200, request("/api/app-users/me").getStatus());
        verify(chain).doFilter(any(), any());
        clearInvocations(chain);
        var denied = request("/api/requests");
        assertEquals(403, denied.getStatus());
        assertTrue(denied.getContentAsString().contains("ACCESS_DENIED"));
        verifyNoInteractions(chain);
    }
}
