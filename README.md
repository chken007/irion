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

## Prerequisites

- Docker
- Java 21 + Maven (or use Docker build)
- Node 25 (or use Docker build)
- Python 3.13 + uv (or use Docker build)

## Quick Start (Docker)

```bash
# 1. Clone all repos as siblings
git clone https://github.com/chken007/irion.git
git clone https://github.com/chken007/irion-ui.git
git clone https://github.com/chken007/irion-pipeline.git

# 2. Place Kaggle datasets in irion/data/bronze/
#    Required: Amazon Sale Report.csv, Sale Report.csv, 
#              P L March 2021.csv, Walmart_Sales.csv

# 3. Start everything
cd irion/docker
docker compose -f docker-compose.full.yml up -d

# 4. Open http://localhost:5173
#    Login: admin@irion.io / admin123
```

## Quick Start (Local Dev)

```bash
# Infrastructure
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

## Repos

- [irion](https://github.com/chken007/irion) — Spring Boot API + Simulator + Docker config
- [irion-ui](https://github.com/chken007/irion-ui) — React dashboard
- [irion-pipeline](https://github.com/chken007/irion-pipeline) — Python Kafka→Parquet pipeline
