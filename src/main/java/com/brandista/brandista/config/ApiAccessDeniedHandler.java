package com.brandista.brandista.config;

import com.brandista.brandista.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final com.brandista.brandista.privilege.SecurityAudit audit;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper, com.brandista.brandista.privilege.SecurityAudit audit) {
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (request.getRequestURI().startsWith("/api/v1/super-admin/")) {
            audit.rejectedIndependently(
                    com.brandista.brandista.privilege.SecurityAudit.Operation.PRIVILEGED_ACCESS, null,
                    com.brandista.brandista.privilege.SecurityAudit.Reason.ACCESS_DENIED);
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                HttpServletResponse.SC_FORBIDDEN,
                "Forbidden",
                "Access is forbidden.",
                request.getRequestURI(),
                List.of()));
    }
}
