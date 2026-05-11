package com.irion.api;

import com.irion.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public auth endpoints: login and register.
 * These are the only unauthenticated /api/** endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login and registration endpoints")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public AuthController(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * DTO for login request.
     */
    public record LoginRequest(
            @Schema(description = "User email", example = "admin@irion.io")
            String email,
            @Schema(description = "User password", example = "admin123")
            String password) {}

    /**
     * DTO for register request.
     */
    public record RegisterRequest(
            @Schema(description = "User email", example = "user@example.com")
            String email,
            @Schema(description = "User password (min 6 chars)", example = "securePass1")
            String password,
            @Schema(description = "User full name", example = "Jane Doe")
            String fullName,
            @Schema(description = "Tenant/organization name", example = "Acme Corp")
            String tenantName) {}

    @PostMapping("/login")
    @Operation(summary = "Authenticate and get JWT token",
               description = "Validates email/password against stored credentials and returns a signed JWT.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials",
                     content = @Content(schema = @Schema(example = "{\"error\":\"INVALID_CREDENTIALS\"}")))
    })
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        log.info("POST /api/auth/login email={}", request.email());

        // Lookup user
        var users = jdbcTemplate.queryForList(
                "SELECT u.id, u.email, u.password_hash, u.full_name, u.role, u.tenant_id, t.name AS tenant_name " +
                "FROM users u JOIN tenants t ON u.tenant_id = t.id WHERE u.email = ?",
                request.email());

        if (users.isEmpty()) {
            return buildError("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        var user = users.get(0);
        String storedHash = (String) user.get("password_hash");

        if (!passwordEncoder.matches(request.password(), storedHash)) {
            return buildError("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        Long tenantId = ((Number) user.get("tenant_id")).longValue();
        String role = (String) user.get("role");
        String email = (String) user.get("email");
        String fullName = (String) user.get("full_name");
        String tenantName = (String) user.get("tenant_name");

        String token = jwtUtil.generateToken(email, tenantId, role);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("tenantId", tenantId);
        body.put("tenantName", tenantName);
        body.put("role", role);
        body.put("fullName", fullName);
        body.put("email", email);

        log.info("Login successful for user={} tenant={}", email, tenantId);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new user and tenant",
               description = "Creates a new tenant and an ADMIN user within it. Returns a JWT token.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Registration successful"),
        @ApiResponse(responseCode = "409", description = "Email already exists",
                     content = @Content(schema = @Schema(example = "{\"error\":\"EMAIL_EXISTS\"}")))
    })
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        log.info("POST /api/auth/register email={} tenantName={}", request.email(), request.tenantName());

        // Check if email already exists
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, request.email());
        if (count != null && count > 0) {
            return buildError("EMAIL_EXISTS", "A user with this email already exists", HttpStatus.CONFLICT);
        }

        // Validate password
        if (request.password() == null || request.password().length() < 6) {
            return buildError("WEAK_PASSWORD", "Password must be at least 6 characters", HttpStatus.BAD_REQUEST);
        }

        // Create tenant
        jdbcTemplate.update("INSERT INTO tenants (name) VALUES (?)", request.tenantName());
        Long tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, request.tenantName());

        // Create user
        String passwordHash = passwordEncoder.encode(request.password());
        jdbcTemplate.update(
                "INSERT INTO users (tenant_id, email, password_hash, full_name, role) VALUES (?, ?, ?, ?, ?)",
                tenantId, request.email(), passwordHash,
                request.fullName() != null ? request.fullName() : "", "ADMIN");

        // Generate token
        String token = jwtUtil.generateToken(request.email(), tenantId, "ADMIN");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("tenantId", tenantId);
        body.put("tenantName", request.tenantName());
        body.put("role", "ADMIN");
        body.put("fullName", request.fullName());
        body.put("email", request.email());

        log.info("Registered new tenant={} user={}", tenantId, request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private ResponseEntity<Map<String, Object>> buildError(String error, String message, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("status", status.value());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
