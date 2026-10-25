package com.brandista.brandista.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** A host-local monitoring listener; application-port authorization is unchanged. */
@Configuration(proxyBeanMethods = false)
@Profile("production")
public class ProductionManagementSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain localManagement(HttpSecurity http, Environment environment) throws Exception {
        int configured = environment.getProperty("management.server.port", Integer.class, 9091);
        int application = environment.getProperty("server.port", Integer.class, 8080);
        if (configured != 0 && configured == application)
            throw new IllegalStateException("Production monitoring requires a separate loopback listener.");
        return http.securityMatcher(request -> {
                    int port = environment.getProperty("local.management.port", Integer.class, configured);
                    String remote = request.getRemoteAddr();
                    return port > 0 && request.getLocalPort() == port
                            && ("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)
                                || "::1".equals(remote));
                })
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/metrics",
                                "/actuator/metrics/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
