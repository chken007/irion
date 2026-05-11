package com.irion;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full context integration test — requires PostgreSQL Docker to be running.
 * Run with: docker compose -f docker/docker-compose.yml up -d postgres
 */
@SpringBootTest
@Disabled("Requires PostgreSQL Docker — run 'docker compose up -d postgres' first")
class IrionApplicationTests {

    @Test
    void contextLoads() {
    }
}
