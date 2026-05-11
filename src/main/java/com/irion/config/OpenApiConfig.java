package com.irion.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI configuration for the Irion REST API.
 * <p>
 * Exposes the interactive API documentation at {@code /swagger-ui.html}
 * and the raw OpenAPI spec at {@code /api-docs}.
 * </p>
 */
@Slf4j
@Configuration
public class OpenApiConfig {

    /**
     * Builds the OpenAPI definition bean consumed by SpringDoc.
     *
     * @return a customised {@link OpenAPI} instance
     */
    @Bean
    public OpenAPI irionOpenAPI() {
        log.info("Configuring OpenAPI / Swagger UI");
        return new OpenAPI()
                .info(new Info()
                        .title("Irion — Retail Execution Engine API")
                        .version("1.0.0")
                        .description("CommerceIQ Alternative: Automated retail analytics & actions")
                        .contact(new Contact()
                                .name("Heng")
                                .email("cuiken007@gmail.com")));
    }
}
