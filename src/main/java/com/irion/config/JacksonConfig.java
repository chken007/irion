package com.irion.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson JSON serialization configuration.
 * <p>
 * Registers the {@code JavaTimeModule} so that {@code java.time} types
 * are serialised as ISO-8601 strings instead of timestamps, and enables
 * pretty-print for development convenience.
 * </p>
 */
@Slf4j
@Configuration
public class JacksonConfig {

    /**
     * Customises the auto-configured {@code ObjectMapper} builder.
     * <p>
     * The settings applied here are additive to what is configured in
     * {@code spring.jackson.*} in {@code application.yml}.
     * </p>
     *
     * @return a {@link Jackson2ObjectMapperBuilderCustomizer} bean
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        log.info("Registering Jackson JavaTimeModule and custom serialization settings");
        return builder -> {
            builder.modules(new JavaTimeModule());
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.featuresToEnable(SerializationFeature.INDENT_OUTPUT);
        };
    }
}
