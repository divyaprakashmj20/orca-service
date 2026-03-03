package com.lytspeed.orka.security;

import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.repository.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticatedAppUserService {
    private final AppUserRepository appUserRepository;

    public AuthenticatedAppUserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    public AppUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Treat anonymous auth (no token sent) the same as no auth at all.
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        return appUserRepository.findByFirebaseUid(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "App user profile missing"));
    }
}
