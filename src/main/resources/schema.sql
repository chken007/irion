-- Irion PostgreSQL schema
-- Creates retailer, product_catalog, decision_log, and business_action tables.
-- Spring Boot executes this automatically when spring.sql.init.mode=always.

CREATE TABLE IF NOT EXISTS retailer (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    region VARCHAR(50),
    active BOOLEAN DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS product_catalog (
    id BIGSERIAL PRIMARY KEY,
    retailer_id BIGINT NOT NULL REFERENCES retailer(id),
    sku_code VARCHAR(100) NOT NULL,
    asin_upc VARCHAR(50),
    product_name VARCHAR(500) NOT NULL,
    category VARCHAR(200),
    sub_category VARCHAR(200),
    brand VARCHAR(200),
    size VARCHAR(50),
    color VARCHAR(50),
    UNIQUE(retailer_id, sku_code)
);

CREATE TABLE IF NOT EXISTS decision_log (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES product_catalog(id),
    rule_name VARCHAR(100) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    context JSONB NOT NULL DEFAULT '{}',
    decided_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business_action (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES product_catalog(id),
    rule_name VARCHAR(100) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    executed BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_decision_product ON decision_log(product_id);
CREATE INDEX IF NOT EXISTS idx_decision_time ON decision_log(decided_at);
CREATE INDEX IF NOT EXISTS idx_action_product ON business_action(product_id);

-- Multi-tenant support
CREATE TABLE IF NOT EXISTS tenants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    industry VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(200),
    role VARCHAR(50) NOT NULL DEFAULT 'VIEWER',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Seed default tenant and admin user
INSERT INTO tenants (id, name, industry) VALUES (1, 'Default Brand', 'CPG') ON CONFLICT DO NOTHING;
-- Password: admin123 (BCrypt)
INSERT INTO users (tenant_id, email, password_hash, full_name, role)
VALUES (1, 'admin@irion.io', '$2b$10$qDamAfT5/tNSpc2.aozPrOfxcGX0Jhoy5VQvbzEhFIFHZUWcEBbBW', 'Admin User', 'ADMIN')
ON CONFLICT DO NOTHING;
