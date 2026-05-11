# Irion — 补齐计划 v2.0

> 基于 CommerceIQ 真实产品调研（5 张截图 + 2 篇 Blog + 招聘 JD）

## 当前状态 vs 目标差距

| 维度 | 当前 irion | CommerceIQ 真实产品 | 差距 |
|------|----------|-------------------|------|
| 数据管道 | Kafka pipeline 代码写完但没跑通 | ✅ Airflow + Kafka 实时 ETL | 端到端跑通 |
| 数据量 | SampleDataInitializer 已删，当前 0 行 | 数万 SKU × 200 天 | 跑模拟器 |
| 分析指标 | WOS、ROAS、BuyBox Win Rate | +ACOS、Share of Voice、cannibalization | 补 4 个新指标 |
| 规则引擎 | WOS→PAUSE_AD、BuyBox→PRICE_ALERT | +REORDER_ALERT、INCREMENTALITY_CHECK、WASTED_SPEND | 补 3 条新规则 |
| 前端 UI | 暗色主题，简单 grid | 亮色双栏、sparkline、平台切换器 | 重建 |
| 多租户 | ❌ | ✅ 品牌方多账户 | 新建 |
| 广告分析 | 基础 ROAS | AMC 曝光组重叠、ACOS、增量分析 | 补模块 |

---

## Phase 1: 端到端数据流水线（最高优先）

### 目标
让 Kafka Simulator → Pipeline → Parquet → DuckDB → API → 前端 全链路通畅。

### Task 1.1: 启动 Kafka + 跑通 Simulator
```bash
docker compose -f docker/docker-compose.yml up -d kafka
cd scripts && python kafka_data_simulator.py --duration 180
```
- 验证：Kafka UI (localhost:8081) 能看到 5 个 topic 的消息
- 验证：data/silver/ 下出现 *.parquet 文件

### Task 1.2: 启动 Pipeline Service
```bash
cd irion-pipeline && uv run irion-pipeline
```
- 验证：Pipeline 消费 Kafka 消息，写入 Parquet
- 验证：silver 目录下持续增长 timestamped Parquet 文件

### Task 1.3: 启动 API + 验证数据
```bash
cd irion && mvn spring-boot:run
curl http://localhost:8080/api/v1/analytics/wos
```
- 验证：返回 250 个 SKU（而非之前的 8 个）
- 验证：WOS 值合理（基于真实 sales + inventory 数据）
- 验证：ROAS、BuyBox、Actions 端点正常

### Task 1.4: 前端展示真实数据
- 验证：Dashboard 显示 250 SKU 的统计
- 验证：图表不再只是 8 条柱

---

## Phase 2: 广告分析模块

### 2.1: 新增指标

**ACOS (Advertising Cost of Sales)**
```sql
-- ACOS = ad_spend / attributed_sales * 100
-- 已有的 ad_spend 表可直接计算
```

**Share of Voice (SOV)**
```sql
-- SOV = 本品牌搜索结果展示次数 / 总搜索结果展示次数
-- 需要 impressions 数据（ad_spend 表已有）
```

**Cannibalization Score**
```sql
-- 同一用户被多个广告形式触达的比例
-- 需要 user_id 或 session_id 维度
-- V1: 按日期统计同一天多个 campaign_type 的 overlap
```

**Incrementality**
```sql
-- 增量 ROAS = (广告带来的增量销售) / ad_spend
-- V1: ROAS 减去自然销售基准线
```

### 2.2: 新增规则

**WastedSpendRule**: IF (inventory_on_hand == 0 AND ad_spend > 0) → PAUSE_AD + ALERT
**CannibalizationRule**: IF (cannibalization_score > 0.5) → CONSOLIDATE_CAMPAIGNS
**UnderperformingRule**: IF (ROAS < 1.0 AND days_running > 14) → REVIEW_CREATIVE

---

## Phase 3: 前端重建（对标真实截图）

### 3.1: 色调 → 亮色主题
- 主背景: #F5F6FA
- 侧边栏: #1A222C (暗色不变)
- 卡片: #FFFFFF + 阴影
- 强调色: #007BFF 蓝

### 3.2: 布局 → 双栏模式
- 左侧: Widget Configuration 面板 (可折叠)
- 右侧: 数据展示区
- 全局: 面包屑 Header (Home > Dashboard)

### 3.3: 新增组件
- **SparklineCard**: 指标卡片内嵌迷你趋势图
- **RetailerSelector**: 侧边栏顶部 "Amazon.com" 下拉
- **DateRangePicker**: Header 日历选择器
- **KPOIndicator**: 彩色边框状态卡片

### 3.4: 侧边栏重构
- 分组导航: MY WORKSPACE / DIGITAL SHELF / RETAIL MEDIA
- 真实图标 → 用 SVG icon 库 (lucide-react)
- 平台选择器

---

## Phase 4: 多租户 + 用户系统

### 4.1: PostgreSQL 新增表
```sql
tenants (id, name, industry, created_at)
users (id, tenant_id, email, password_hash, role, created_at)
```

### 4.2: Spring Security + JWT
- POST /api/auth/login → JWT token
- 所有 API 需要 Authorization header
- 数据隔离: 每个 tenant 只能看到自己的数据

### 4.3: 前端登录页
- Login 表单
- Token 存储 + 自动附带
- 角色区分: Admin / Editor / Viewer

---

## 执行顺序

```
Phase 1 (端到端管道) — 2-3 小时
  ↓
Phase 2 (广告分析) — 1-2 小时
  ↓
Phase 3 (UI 重建) — 3-4 小时
  ↓
Phase 4 (多租户) — 2-3 小时
```

> **For Hermes:** 按 Phase 顺序依次执行。每个 Phase 完成后验证再进入下一个。Phase 3 可以与 Phase 2 并行。
