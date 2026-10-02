package com.lytspeed.orka.security;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebaseAuthenticationFilterTest {
    @Test void certificateServiceFailureIsUnavailableRatherThanBadCredentials() throws Exception {
        var auth = mock(FirebaseAuth.class);
        var failure = mock(FirebaseAuthException.class);
        when(failure.getAuthErrorCode()).thenReturn(AuthErrorCode.CERTIFICATE_FETCH_FAILED);
        when(auth.verifyIdToken("test-token")).thenThrow(failure);
        var request = new MockHttpServletRequest("GET", "/api/app-users/me");
        request.setServletPath("/api/app-users/me");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        try (var instance = mockStatic(FirebaseAuth.class)) {
            instance.when(FirebaseAuth::getInstance).thenReturn(auth);
            new FirebaseAuthenticationFilter().doFilter(request, response, chain);
        }
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_UNAVAILABLE"));
        assertNull(response.getErrorMessage());
        verifyNoInteractions(chain);
    }
}
