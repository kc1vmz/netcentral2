package com.kc1vmz.netcentral.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI netCentralApi() {
        return new OpenAPI().info(new Info().title("Net Central REST API").version("v2")
            .description("Sign in through /login before using Try it out. Requests use the existing SessionID cookie. "
                + "Swagger UI retrieves a fresh CSRF token for mutations; external clients must GET /api/v1/csrf "
                + "and send its token in the returned headerName with the same session cookie. "
                + "Existing role, ownership and version checks apply: 401 means sign-in is required, "
                + "403 means access denied or invalid CSRF, and 409 commonly indicates a version conflict. "
                + "Try it out executes real operations, including radio transmissions and deletion. "
                + "Schemas are generated from controller models. Generic JSON bodies have limited inferred detail; "
                + "consult the repository docs for operation-specific fields and constraints."))
            .components(new Components().addSecuritySchemes("session", new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("SessionID")
                .description("Obtained by signing in. The browser sends this HttpOnly cookie automatically; do not paste it into Authorize.")))
            .addSecurityItem(new SecurityRequirement().addList("session"));
    }

    @Bean
    OpenApiCustomizer publicAccountOperations() {
        return api -> {
            for (String path : java.util.List.of("/api/v1/csrf", "/api/v1/login", "/api/v1/users/register")) {
                var item = api.getPaths().get(path);
                if (item != null) item.readOperations().forEach(operation -> operation.setSecurity(java.util.List.of()));
            }
        };
    }
}
