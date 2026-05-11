# Irion — Retail Execution Engine 实施计划

> **For Hermes:** 按 Phase 分派 subagent 实现，每个 Phase 一个独立 subagent，按依赖顺序执行。

**Goal:** 构建 CommerceIQ 的平替产品 — 从"海量电商数据"到"自动化业务决策"的确定性闭环 SaaS 基础版。

**Architecture:** 三层数据架构 (Bronze→Silver→Gold)，DuckDB 本地 OLAP + Parquet 列式存储，规则引擎驱动的自动化动作中心。全部功能通过 RESTful API 暴露，为 AI Agent 转型预埋 Hook。

**Tech Stack:** Java 21, Spring Boot 3.5, DuckDB 1.3.1, Apache Parquet 1.15, H2 (决策日志), Prometheus + Grafana (可观测性), Logstash JSON 日志, OpenAPI/Swagger

**CommerceIQ 对标分析:**
CommerceIQ 核心四条产品线：
1. **Digital Shelf Analytics** — 库存健康度、Buy Box 占有率、内容评分、搜索排名、竞品监控
2. **Retail Media Management** — 广告 ROAS、媒体预算分配优化
3. **eCommerce Sales Management** — 销售计划 vs 实际 gap 分析、库存预测
4. **Content Optimization** — PDP 内容优化、PIM 同步

Irion V1 聚焦 #1 + #2 的核心指标层 (WOS, ROAS, Buy Box)，V2+ 可扩展 #3 和 #4。

---

## 项目目录结构 (完成后的形态)

```
~/repos/irion/
├── pom.xml
├── docker/
│   ├── docker-compose.yml          # Prometheus + Grafana
│   ├── prometheus.yml
│   └── grafana/
│       └── dashboards/
├── data/                           # 本地数据湖 (gitignore)
│   ├── bronze/                     # 原始 CSV/JSON
│   ├── silver/                     # 中间 Parquet
│   └── gold/                       # 聚合结果
├── src/main/java/com/irion/
│   ├── IrionApplication.java
│   ├── config/
│   │   ├── DuckDbConfig.java       # DuckDB DataSource bean
│   │   ├── DataSourceConfig.java   # H2 DataSource bean
│   │   ├── OpenApiConfig.java
│   │   └── JacksonConfig.java
│   ├── domain/
│   │   ├── SkuData.java            # SKU 销售/库存数据
│   │   ├── SkuMetrics.java         # 计算后的指标 (WOS, ROAS...)
│   │   ├── InventoryRisk.java      # 库存风险评估
│   │   ├── BusinessAction.java     # 业务动作 (PAUSE_AD, PRICE_ALERT...)
│   │   ├── DecisionLog.java        # 决策日志 (含决策依据)
│   │   ├── BuyBoxStatus.java       # Buy Box 状态
│   │   └── IngestionResult.java    # 数据摄入结果
│   ├── infra/
│   │   └── DuckDbTemplate.java     # DuckDB SQL 执行模板 (类似 JdbcTemplate)
│   ├── ingestion/
│   │   ├── DataIngestionService.java   # 摄入入口
│   │   ├── SchemaDetector.java         # CSV/JSON Schema 自动检测
│   │   └── ParquetConverter.java       # CSV/JSON → Parquet 转换
│   ├── analytics/
│   │   ├── InventoryService.java       # WOS + OOS Risk 计算
│   │   ├── AdEfficiencyService.java    # ROAS 计算
│   │   └── BuyBoxService.java          # Buy Box Win Rate
│   ├── engine/
│   │   ├── RuleEngine.java             # 规则引擎核心
│   │   ├── ActionService.java          # 动作执行器
│   │   └── rule/
│   │       ├── Rule.java               # 规则接口
│   │       ├── WosRule.java            # IF WOS<1.5 → PAUSE_AD
│   │       └── BuyBoxRule.java         # IF BuyBox Lost → PRICE_ALERT
│   ├── api/
│   │   ├── AnalyticsController.java    # /api/v1/analytics/*
│   │   ├── ActionController.java       # /api/v1/actions/*
│   │   └── IngestionController.java    # /api/v1/ingestion/*
│   └── exception/
│       ├── DataNotFoundException.java
│       ├── IngestionException.java
│       └── GlobalExceptionHandler.java
└── src/test/java/com/irion/
    ├── infra/DuckDbTemplateTest.java
    ├── ingestion/DataIngestionServiceTest.java
    ├── analytics/InventoryServiceTest.java
    ├── analytics/AdEfficiencyServiceTest.java
    ├── analytics/BuyBoxServiceTest.java
    ├── engine/RuleEngineTest.java
    └── api/AnalyticsControllerTest.java
```

---

## Phase 1: 基础设施层 (Infrastructure)

**目标:** 搭建 DuckDB 连接、多数据源、JSON 日志、OpenAPI、Prometheus 端点，让项目可以启动并暴露 /actuator/prometheus 和 /swagger-ui.html。

### Task 1.1: 创建 DuckDbConfig — DuckDB DataSource

**Files:**
- Create: `src/main/java/com/irion/config/DuckDbConfig.java`
- Modify: `src/main/resources/application.yml` (已配置)

**Implementation:**
```java
package com.irion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration
public class DuckDbConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.duckdb")
    public DataSource duckDbDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    public JdbcTemplate duckDbJdbcTemplate(DataSource duckDbDataSource) {
        return new JdbcTemplate(duckDbDataSource);
    }
}
```

### Task 1.2: 创建 DataSourceConfig — H2 数据源 (决策日志)

**Files:**
- Create: `src/main/java/com/irion/config/DataSourceConfig.java`

**Implementation:**
```java
package com.irion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.h2")
    public DataSource h2DataSource() {
        return DataSourceBuilder.create().build();
    }

    @Primary
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource h2DataSource) {
        return new JdbcTemplate(h2DataSource);
    }
}
```

### Task 1.3: 创建 OpenApiConfig

**Files:**
- Create: `src/main/java/com/irion/config/OpenApiConfig.java`

```java
package com.irion.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI irionOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Irion — Retail Execution Engine API")
                .version("1.0.0")
                .description("CommerceIQ Alternative: Automated retail analytics & actions")
                .contact(new Contact().name("Heng").email("cuiken007@gmail.com")));
    }
}
```

### Task 1.4: 验证启动

**Verification:**
```bash
cd ~/repos/irion && mvn spring-boot:run
# 然后访问 http://localhost:8080/swagger-ui.html
# 然后访问 http://localhost:8080/actuator/prometheus
```

---

## Phase 2: 领域模型 + 数据访问层 (Domain + Data)

**目标:** 定义所有领域对象，实现 DuckDbTemplate 工具类，建 H2 决策日志表。

### Task 2.1: 创建 SkuData 领域对象

**Files:**
- Create: `src/main/java/com/irion/domain/SkuData.java`

```java
package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SkuData {
    private String skuId;
    private String asin;
    private String productName;
    private String category;
    private LocalDate date;
    private int inventoryOnHand;
    private BigDecimal salesUnits;
    private BigDecimal revenue;
    private BigDecimal adSpend;
    private boolean hasBuyBox;
    private BigDecimal myPrice;
    private BigDecimal competitorPrice;
}
```

### Task 2.2: 创建 SkuMetrics (计算结果)

**Files:**
- Create: `src/main/java/com/irion/domain/SkuMetrics.java`

```java
package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SkuMetrics {
    private String skuId;
    private double wos;                    // Weeks of Supply
    private double oosRiskIndex;           // 0-100, higher = more risk
    private BigDecimal roas;               // Return on Ad Spend
    private double buyBoxWinRate;          // 0-100 percentage
    private String buyBoxStatus;           // "WON", "LOST", "SHARED"
    private int totalObservations;
}
```

### Task 2.3: 创建 BusinessAction, DecisionLog, InventoryRisk, BuyBoxStatus, IngestionResult

**Files:**
- Create: `src/main/java/com/irion/domain/BusinessAction.java`
- Create: `src/main/java/com/irion/domain/DecisionLog.java`
- Create: `src/main/java/com/irion/domain/InventoryRisk.java`
- Create: `src/main/java/com/irion/domain/BuyBoxStatus.java`
- Create: `src/main/java/com/irion/domain/IngestionResult.java`

**BusinessAction.java:**
```java
package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BusinessAction {
    public enum ActionType { PAUSE_AD, RESUME_AD, PRICE_ALERT, REORDER_ALERT }
    private Long id;
    private String skuId;
    private String ruleName;
    private ActionType actionType;
    private String reason;
    private LocalDateTime createdAt;
    private boolean executed;
}
```

**DecisionLog.java:**
```java
package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DecisionLog {
    private Long id;
    private String skuId;
    private String ruleName;
    private String actionType;
    private String context; // JSON: {"wos": 1.2, "competitorPrice": 19.99, ...}
    private LocalDateTime decidedAt;
}
```

**InventoryRisk.java:**
```java
package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryRisk {
    private String skuId;
    private double wos;
    private double oosRiskIndex;
    private String riskLevel; // "CRITICAL", "WARNING", "HEALTHY"
}
```

**BuyBoxStatus.java:**
```java
package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BuyBoxStatus {
    private String skuId;
    private LocalDate date;
    private boolean won;
    private String winner;
    private double winnerPrice;
    private double myPrice;
}
```

**IngestionResult.java:**
```java
package com.irion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IngestionResult {
    private String fileName;
    private int rowsIngested;
    private long durationMs;
    private String parquetPath;
    private String status; // "SUCCESS", "PARTIAL", "FAILED"
}
```

### Task 2.4: 创建 DuckDbTemplate

**Files:**
- Create: `src/main/java/com/irion/infra/DuckDbTemplate.java`

```java
package com.irion.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class DuckDbTemplate {

    private final JdbcTemplate duckDb;

    public DuckDbTemplate(JdbcTemplate duckDbJdbcTemplate) {
        this.duckDb = duckDbJdbcTemplate;
    }

    /** 挂载外部 Parquet 文件为虚拟表 */
    public void mountParquet(String tableName, String parquetPath) {
        duckDb.execute(String.format(
            "CREATE OR REPLACE VIEW %s AS SELECT * FROM read_parquet('%s')",
            tableName, parquetPath));
    }

    /** 执行查询并映射到对象 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return duckDb.query(sql, mapper, args);
    }

    /** 执行查询返回 List<Map> */
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return duckDb.queryForList(sql, args);
    }

    /** 执行单值查询 */
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return duckDb.queryForObject(sql, type, args);
    }

    /** 执行 DDL/DML */
    public void execute(String sql) {
        duckDb.execute(sql);
    }

    /** 计算聚合指标 — 在 Parquet 上直接跑 SQL */
    public <T> List<T> aggregate(String sql, RowMapper<T> mapper) {
        return duckDb.query(sql, mapper);
    }
}
```

### Task 2.5: 建决策日志表 (H2 schema.sql)

**Files:**
- Create: `src/main/resources/schema.sql`

```sql
CREATE TABLE IF NOT EXISTS decision_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id VARCHAR(50) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    context CLOB NOT NULL,
    decided_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS business_action (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id VARCHAR(50) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    executed BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_decision_sku ON decision_log(sku_id);
CREATE INDEX IF NOT EXISTS idx_decision_time ON decision_log(decided_at);
```

---

## Phase 3: 数据摄取层 (Data Ingestion — Bronze Layer)

**目标:** 将 CSV/JSON 原始文件自动检测 Schema 并转换为 Parquet，通过 DuckDB 挂载为虚拟表。

### Task 3.1: 创建 SchemaDetector

**Files:**
- Create: `src/main/java/com/irion/ingestion/SchemaDetector.java`

核心逻辑：读 CSV 第一行做 header 检测，采样前 100 行推断每列类型 (STRING/BIGINT/DOUBLE/DATE)。

### Task 3.2: 创建 ParquetConverter

**Files:**
- Create: `src/main/java/com/irion/ingestion/ParquetConverter.java`

核心逻辑：用 DuckDB 的 `COPY ... TO 'file.parquet' (FORMAT PARQUET)` 语句直接转换，避免手写 Hadoop Parquet Writer。

### Task 3.3: 创建 DataIngestionService

**Files:**
- Create: `src/main/java/com/irion/ingestion/DataIngestionService.java`

核心逻辑：
1. 接收文件路径
2. SchemaDetector 检测结构
3. ParquetConverter 转为 Parquet 存入 `data/silver/`
4. DuckDB 挂载为虚拟视图
5. 返回 IngestionResult

---

## Phase 4: 分析引擎 (Analytics Engine — Gold Layer)

**目标:** 在 DuckDB 上直接对 Parquet 文件执行聚合 SQL，计算 WOS、OOS Risk、ROAS、Buy Box Win Rate。

### Task 4.1: InventoryService — WOS 计算

**Files:**
- Create: `src/main/java/com/irion/analytics/InventoryService.java`

**SQL 逻辑:**
```sql
WITH rolling_sales AS (
    SELECT sku_id,
           AVG(sales_units) / 7.0 AS avg_daily_sales
    FROM sku_data
    WHERE date >= CURRENT_DATE - INTERVAL '28 days'
    GROUP BY sku_id
),
latest_inventory AS (
    SELECT sku_id, inventory_on_hand
    FROM sku_data
    WHERE date = (SELECT MAX(date) FROM sku_data)
)
SELECT l.sku_id,
       l.inventory_on_hand,
       COALESCE(r.avg_daily_sales, 0.001) AS avg_daily_sales,
       CASE WHEN r.avg_daily_sales > 0
            THEN l.inventory_on_hand / (r.avg_daily_sales * 7.0)
            ELSE 999
       END AS wos
FROM latest_inventory l
LEFT JOIN rolling_sales r ON l.sku_id = r.sku_id
```

**OOS Risk Index 计算:**
```
OOS_RISK = CASE
    WHEN WOS < 1.0 THEN 90 + (1.0 - WOS) * 10
    WHEN WOS < 2.0 THEN 50 + (2.0 - WOS) * 40
    WHEN WOS < 4.0 THEN (4.0 - WOS) * 25
    ELSE 0
END
-- 封顶 100
```

### Task 4.2: AdEfficiencyService — ROAS 计算

**Files:**
- Create: `src/main/java/com/irion/analytics/AdEfficiencyService.java`

**SQL 逻辑:**
```sql
SELECT sku_id,
       SUM(revenue) AS total_revenue,
       SUM(ad_spend) AS total_ad_spend,
       CASE WHEN SUM(ad_spend) > 0
            THEN SUM(revenue) / SUM(ad_spend)
            ELSE NULL
       END AS roas
FROM sku_data
WHERE date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY sku_id
```

### Task 4.3: BuyBoxService — Buy Box Win Rate

**Files:**
- Create: `src/main/java/com/irion/analytics/BuyBoxService.java`

**SQL 逻辑:**
```sql
SELECT sku_id,
       COUNT(*) AS total_observations,
       SUM(CASE WHEN has_buy_box THEN 1 ELSE 0 END) AS buy_box_wins,
       SUM(CASE WHEN has_buy_box THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS win_rate,
       CASE WHEN competitor_price < my_price AND NOT has_buy_box
            THEN true ELSE false
       END AS underpriced_by_competitor
FROM sku_data
WHERE date >= CURRENT_DATE - INTERVAL '7 days'
GROUP BY sku_id
```

---

## Phase 5: 规则引擎 + 动作中心 (Rule Engine + Actions)

**目标:** 实现可扩展的规则引擎，V1 支持两条规则，动作结果写入 H2 + 输出日志。

### Task 5.1: 创建 Rule 接口

**Files:**
- Create: `src/main/java/com/irion/engine/rule/Rule.java`

```java
package com.irion.engine.rule;

import com.irion.domain.SkuMetrics;
import com.irion.domain.BusinessAction;
import java.util.Optional;

@FunctionalInterface
public interface Rule {
    /** Evaluate a SKU's metrics and return an action if the rule fires. */
    Optional<BusinessAction> evaluate(SkuMetrics metrics);
}
```

### Task 5.2: 实现 WosRule

**Files:**
- Create: `src/main/java/com/irion/engine/rule/WosRule.java`

**逻辑:**
```
IF (WOS < 1.5) → Action: PAUSE_AD, reason: "WOS={wos}, risk OOS"
```

### Task 5.3: 实现 BuyBoxRule

**Files:**
- Create: `src/main/java/com/irion/engine/rule/BuyBoxRule.java`

**逻辑:**
```
IF (BuyBox lost AND competitor price < my price) → Action: PRICE_ALERT
```

### Task 5.4: 实现 RuleEngine

**Files:**
- Create: `src/main/java/com/irion/engine/RuleEngine.java`

```java
package com.irion.engine;

import com.irion.domain.SkuMetrics;
import com.irion.domain.BusinessAction;
import com.irion.domain.DecisionLog;
import com.irion.engine.rule.Rule;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class RuleEngine {
    private final List<Rule> rules;
    // ActionService injected for persistence

    public RuleEngine(List<Rule> rules) {
        this.rules = rules;
    }

    public List<BusinessAction> evaluate(SkuMetrics metrics) {
        return rules.stream()
            .map(r -> r.evaluate(metrics))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());
    }
}
```

### Task 5.5: 实现 ActionService

**Files:**
- Create: `src/main/java/com/irion/engine/ActionService.java`

职责：
1. 接收 RuleEngine 产出的 BusinessAction
2. 写入 H2 `business_action` 表
3. 写入 DecisionLog（带完整决策上下文 JSON）
4. 输出结构化日志 (Logstash JSON)
5. V1 阶段模拟执行（不真正调 Amazon API），标记 executed=true

---

## Phase 6: REST API 层

**目标:** 暴露所有功能为 RESTful API，严格遵循 OpenAPI 规范。

### Task 6.1: AnalyticsController

**Files:**
- Create: `src/main/java/com/irion/api/AnalyticsController.java`

**Endpoint:**
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/analytics/wos` | 查询所有 SKU 的 WOS，支持 `?threshold=2` 过滤 |
| GET | `/api/v1/analytics/wos/{skuId}` | 查询单个 SKU 的 WOS |
| GET | `/api/v1/analytics/roas` | 查询所有 SKU 的 ROAS |
| GET | `/api/v1/analytics/buybox` | 查询所有 SKU 的 Buy Box Win Rate |
| GET | `/api/v1/analytics/risks` | 查询高风险 SKU (OOS Risk) |

### Task 6.2: ActionController

**Files:**
- Create: `src/main/java/com/irion/api/ActionController.java`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/actions/recommendations` | 对所有 SKU 运行规则引擎，返回推荐动作 |
| GET | `/api/v1/actions/history` | 查询历史动作日志 |
| GET | `/api/v1/actions/history/{skuId}` | 查询特定 SKU 的历史动作 |

### Task 6.3: IngestionController

**Files:**
- Create: `src/main/java/com/irion/api/IngestionController.java`

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/ingestion/upload` | 上传 CSV/JSON 文件进行摄入 |
| GET | `/api/v1/ingestion/status` | 查看摄入状态 |

### Task 6.4: GlobalExceptionHandler

**Files:**
- Create: `src/main/java/com/irion/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/com/irion/exception/DataNotFoundException.java`
- Create: `src/main/java/com/irion/exception/IngestionException.java`

返回标准 JSON 错误格式:
```json
{
  "error": "DATA_NOT_FOUND",
  "message": "SKU B0XXXX not found in dataset",
  "timestamp": "2026-05-09T10:00:00Z",
  "status": 404
}
```

---

## Phase 7: 测试 + 可观测性

### Task 7.1: 单元测试

每个 Service 都需要 JUnit 5 + Mockito 测试。使用 DuckDB 内存模式 (`jdbc:duckdb::memory:`) 做测试。

### Task 7.2: Docker Compose — Prometheus + Grafana

**Files:**
- Create: `docker/docker-compose.yml`
- Create: `docker/prometheus.yml`

```yaml
version: '3.8'
services:
  prometheus:
    image: prom/prometheus:latest
    ports: ['9090:9090']
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
  grafana:
    image: grafana/grafana:latest
    ports: ['3000:3000']
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
```

### Task 7.3: 样本数据生成

创建一个 Python 脚本 `scripts/generate_sample_data.py` 或在测试 resources 中提供 1000 行示例 CSV，包含多种场景：
- 健康库存 SKU (WOS > 4)
- 警告库存 SKU (1.5 < WOS < 2)
- 断货风险 SKU (WOS < 1.5)
- Buy Box 赢/输混合场景
- 有/无广告支出场景

---

## 验收标准 (Acceptance Criteria)

- [ ] `GET /api/v1/analytics/wos?threshold=2` 返回 WOS < 2 的所有 SKU
- [ ] DuckDB 处理 100 万行聚合查询 < 500ms
- [ ] 数据集缺失时返回明确 404
- [ ] `/actuator/prometheus` 正常暴露指标
- [ ] `/swagger-ui.html` 可交互测试所有 API
- [ ] 规则引擎自动推荐动作并写入决策日志
- [ ] 日志包含 decisionId, skuId, ruleName 等结构化字段

---

## 执行顺序

```
Phase 1 (基础设施)
  ↓
Phase 2 (领域模型 + DuckDbTemplate)
  ↓
Phase 3 (数据摄取) ←→ Phase 4 (分析引擎) [可并行]
  ↓
Phase 5 (规则引擎) [依赖 Phase 4]
  ↓
Phase 6 (REST API) [依赖 Phase 3+4+5]
  ↓
Phase 7 (测试 + DevOps)
```
