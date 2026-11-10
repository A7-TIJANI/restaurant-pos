package com.epicit.pos.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** NFR-7: every /api/owner/** endpoint is guarded here on the server; hiding buttons in the UI is not security. */
@Component
public class OwnerAuthInterceptor implements HandlerInterceptor {

    private final OwnerSession ownerSession;

    public OwnerAuthInterceptor(OwnerSession ownerSession) {
        this.ownerSession = ownerSession;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (ownerSession.touchIfActive(request.getSession(false))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"OWNER_REQUIRED\",\"message\":\"Owner PIN required.\"}");
        return false;
    }
}
