package com.lytspeed.orka.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String tokenValue = null;

        // Primary: Authorization: Bearer <token> header (all normal API calls).
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            tokenValue = header.substring(7).trim();
        }

        // Fallback: ?token= query param for SSE connections.
        // EventSource (browser / Capacitor WebView) cannot set custom request headers,
        // so the Firebase ID token is passed as a query parameter instead.
        if ((tokenValue == null || tokenValue.isBlank()) && request.getServletPath().equals("/api/requests/stream")) {
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                tokenValue = queryToken.trim();
            }
        }

        if (tokenValue != null && !tokenValue.isBlank()) {
            try {
                FirebaseToken firebaseToken = FirebaseAuth.getInstance().verifyIdToken(tokenValue);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                firebaseToken.getUid(),
                                null,
                                List.of()
                        );
                authentication.setDetails(firebaseToken);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (FirebaseAuthException failure) {
                SecurityContextHolder.clearContext();
                if (failure.getAuthErrorCode() == AuthErrorCode.CERTIFICATE_FETCH_FAILED) {
                    log.warn("Firebase token verification unavailable: {}", failure.getAuthErrorCode());
                    ApiAccessFailure.AUTHENTICATION_UNAVAILABLE.write(response);
                    return;
                }
                log.debug("Firebase token verification rejected: {}", failure.getAuthErrorCode());
            }
        }

        filterChain.doFilter(request, response);
    }
}
