package com.lytspeed.orka.security;

import com.lytspeed.orka.entity.AppUser;
import com.lytspeed.orka.entity.enums.AppUserStatus;
import com.lytspeed.orka.repository.AppUserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Enforces product eligibility and capability boundaries independently of the client. */
public class ProductAccessFilter extends OncePerRequestFilter {
    private final AppUserRepository users;
    private final AccessScopeService scopes;
    public ProductAccessFilter(AppUserRepository users, AccessScopeService scopes) { this.users=users; this.scopes=scopes; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String p=req.getServletPath(); String method=req.getMethod();
        if (method.equals("OPTIONS") || p.startsWith("/api/requests/guest/") || p.startsWith("/api/food/guest/") || p.startsWith("/api/branding/guest/") || p.equals("/api/notifications/health")) { chain.doFilter(req,res); return; }
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) { ApiAccessFailure.AUTHENTICATION_REQUIRED.write(res); return; }
        if(p.equals("/api/app-users/register")) {chain.doFilter(req,res);return;}
        AppUser actor=users.findByFirebaseUid(auth.getName()).orElse(null);
        if(actor==null) {ApiAccessFailure.PROFILE_MISSING.write(res);return;}
        boolean ownProfile=method.equals("GET")&&(p.equals("/api/app-users/me")||p.equals("/api/app-users/firebase/"+auth.getName()));
        if(ownProfile){chain.doFilter(req,res);return;}
        if(actor.getStatus()!=AppUserStatus.ACTIVE||!Boolean.TRUE.equals(actor.getActive())||actor.getAccessRole()==null){ApiAccessFailure.ACCESS_DENIED.write(res);return;}
        boolean write=!method.equals("GET");
        boolean admin=scopes.isSuperAdmin(actor)||scopes.isHotelGroupAdmin(actor)||scopes.isHotelAdmin(actor);
        if ((p.startsWith("/api/hotel-groups")&&write&&!scopes.isSuperAdmin(actor))
            ||(p.startsWith("/api/hotels")&&write&&!scopes.isSuperAdmin(actor)&&!scopes.isHotelGroupAdmin(actor))
            ||(p.startsWith("/api/rooms")&&write&&!admin)
            ||(p.startsWith("/api/reports")&&!admin)
            ||(p.equals("/api/app-users")&&!admin)
            ||(p.startsWith("/api/app-users/")&&method.equals("GET")&&!admin)
            ||(p.equals("/api/app-users/pending")&&!admin)
            ||(p.startsWith("/api/app-users/")&&write&&!p.equals("/api/app-users/me/profile")&&!admin)
            ||(p.startsWith("/api/app-users/")&&method.equals("DELETE")&&!scopes.isSuperAdmin(actor))
            ||(p.equals("/api/notifications/test")&&!scopes.isSuperAdmin(actor))
            ||(p.startsWith("/api/requests")&&(method.equals("DELETE")||method.equals("POST")))) {ApiAccessFailure.ACCESS_DENIED.write(res);return;}
        chain.doFilter(req,res);
    }
}
