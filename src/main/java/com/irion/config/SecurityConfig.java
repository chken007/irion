package com.irion.config;

import com.irion.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration.
 * <ul>
 *   <li>Stateless JWT authentication on all /api/** endpoints, except /api/auth/**.</li>
 *   <li>CSRF disabled (REST API).</li>
 *   <li>Public endpoints: /api/auth/**, Swagger UI, Actuator health.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // Public auth endpoints
                    .requestMatchers("/api/auth/**").permitAll()
                    // Swagger / OpenAPI docs
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**", "/v3/api-docs/**").permitAll()
                    // Actuator health (no auth needed for health checks)
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // Prometheus metrics (scraped by monitoring)
                    .requestMatchers("/actuator/prometheus").permitAll()
                    // All other /api/** requires authentication
                    .requestMatchers("/api/**").authenticated()
                    // Everything else permitted
                    .anyRequest().permitAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
