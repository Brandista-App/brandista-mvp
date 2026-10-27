package com.brandista.brandista.config;

import com.brandista.brandista.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {
        boolean unavailable = authException instanceof com.brandista.brandista.privilege.CurrentAccountUnavailableException;
        int status = unavailable ? HttpServletResponse.SC_SERVICE_UNAVAILABLE : HttpServletResponse.SC_UNAUTHORIZED;
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                status,
                unavailable ? "Service Unavailable" : "Unauthorized",
                unavailable ? "Shopping service is temporarily unavailable. Retry shortly."
                        : "Authentication is required or the bearer token is invalid.",
                request.getRequestURI(),
                List.of()));
    }
}
