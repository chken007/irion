# Irion — Retail Execution Engine

CommerceIQ-style retail analytics platform with real-time Kafka pipeline, DuckDB OLAP, and React dashboard.

## Architecture

```
Kaggle CSVs → Simulator → Kafka → Pipeline → Parquet → DuckDB → API → React UI
                  ↑                    ↑                  ↑
            (irion/)            (irion-pipeline/)     (irion/)
```

| Component | Stack | Port |
|-----------|-------|------|
| API | Spring Boot 3.5, Java 21, DuckDB, PostgreSQL | 8080 |
| UI | React 19, Vite, Tailwind 4, Recharts | 5173 |
| Pipeline | Python 3.13, uv, PyArrow | — |
| Simulator | Python 3.13, kafka-python | — |
| Kafka | KRaft (no Zookeeper) | 9092 |
| PostgreSQL | 16 Alpine | 5432 |

## Quick Start (Docker — one command)

```bash
# 1. Clone all three repos as siblings
git clone https://github.com/chken007/irion.git
git clone https://github.com/chken007/irion-ui.git
git clone https://github.com/chken007/irion-pipeline.git

# 2. Start everything
cd irion/docker
docker compose -f docker-compose.full.yml up -d

# 3. Open browser
open http://localhost:5173
# Login: admin@irion.io / admin123
```

That's it. Datasets are included (via Git LFS). The simulator generates continuous
e-commerce data, the pipeline writes Parquet files, and the API serves real-time
analytics. It takes ~60 seconds for the first data to appear on the dashboard.

## What's inside `docker-compose.full.yml`

| Service | Description |
|---------|-------------|
| `kafka` | Confluent Kafka (KRaft, no Zookeeper) |
| `postgres` | PostgreSQL 16 (tenants, users, action log) |
| `irion-api` | Spring Boot REST API + DuckDB analytics |
| `irion-pipeline` | Python Kafka consumer → Parquet writer |
| `irion-simulator` | Generates 5-20 e-commerce orders/sec |
| `irion-ui` | React dashboard served via nginx |

Optional monitoring (add `--profile monitoring`):
- `prometheus` — metrics scraping
- `grafana` — dashboards (admin/admin)

## Quick Start (Local Dev)

Use this when you want to hack on the code with hot-reload.

```bash
# Infrastructure only
cd irion/docker && docker compose up -d kafka postgres

# API (terminal 2)
cd irion && mvn spring-boot:run

# Pipeline (terminal 3)
cd irion-pipeline && uv run irion-pipeline

# Simulator (terminal 4)
cd irion && python3 scripts/kafka_data_simulator.py

# UI (terminal 5)
cd irion-ui && npm install && npm run dev
```

## API Endpoints

```bash
# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@irion.io","password":"admin123"}'

# Analytics (use token from login)
curl http://localhost:8080/api/v1/analytics/wos \
  -H 'Authorization: Bearer <token>'

curl http://localhost:8080/api/v1/analytics/roas \
  -H 'Authorization: Bearer <token>'

curl "http://localhost:8080/api/v1/analytics/wos?retailerId=1" \
  -H 'Authorization: Bearer <token>'
```

## Repos

| Repo | Description |
|------|-------------|
| [irion](https://github.com/chken007/irion) | Spring Boot API + Simulator + Docker config |
| [irion-ui](https://github.com/chken007/irion-ui) | React dashboard |
| [irion-pipeline](https://github.com/chken007/irion-pipeline) | Python Kafka→Parquet pipeline |
