package com.lytspeed.orka.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Writes API denials directly so a servlet error dispatch cannot replace their status. */
public enum ApiAccessFailure {
    AUTHENTICATION_REQUIRED(401, "Sign in again to continue"),
    AUTHENTICATION_UNAVAILABLE(503, "Sign-in verification is temporarily unavailable. Please try again"),
    PROFILE_MISSING(403, "App user profile missing"),
    ACCESS_DENIED(403, "This account does not have access to this action");

    private final int status;
    private final String message;

    ApiAccessFailure(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public void write(HttpServletResponse response) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + name() + "\",\"message\":\"" + message + "\"}");
    }
}
