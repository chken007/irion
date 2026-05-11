#!/usr/bin/env python3
"""
Kafka Data Simulator for Irion — E-Commerce Data Pipeline

Reads real-world CSV datasets from data/bronze/ to extract realistic category,
SKU, and price distributions, then simulates 5 live data streams into Kafka.

Datasets used:
  - data/bronze/Amazon Sale Report.csv    (129K rows: orders, SKU, category, amount)
  - data/bronze/Sale Report.csv           (9K rows: SKU code, stock, category, size)
  - data/bronze/P L March 2021.csv        (1.3K rows: cross-platform MRPs)
  - data/bronze/Walmart_Sales.csv         (6.4K rows: weekly sales, holiday flag)

Kafka Topics (5 streams):
  irion.sales.transactions     — 5–20 orders/sec
  irion.inventory.snapshots    — every 10 sec
  irion.ad.spend               — every 30 sec
  irion.buybox.events          — every 15 sec
  irion.competitor.prices      — every 60 sec

Usage:
  python scripts/kafka_data_simulator.py [--kafka-broker localhost:9092] [--duration 3600]
"""

import argparse
import json
import math
import os
import random
import sys
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import pandas as pd

try:
    from kafka import KafkaProducer
    from kafka.errors import NoBrokersAvailable
except ImportError:
    print("ERROR: kafka-python not installed. Run: pip install kafka-python")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent  # irion/
DATA_DIR = Path(os.environ.get("IRION_DATA_DIR", str(PROJECT_ROOT / "data" / "bronze")))

RETAILERS: list[dict[str, Any]] = [
    {"id": 1, "name": "Amazon", "fulfillment_weights": {"FBA": 0.6, "FBM": 0.4}},
    {"id": 2, "name": "Walmart", "fulfillment_weights": {"WFS": 0.5, "FBM": 0.5}},
    {"id": 3, "name": "Target", "fulfillment_weights": {"TFS": 0.5, "FBM": 0.5}},
    {"id": 4, "name": "Instacart", "fulfillment_weights": {"IC_FULFILLED": 0.7, "FBM": 0.3}},
    {"id": 5, "name": "Flipkart", "fulfillment_weights": {"F_ASSURED": 0.55, "FBM": 0.45}},
]

TOPICS = {
    "sales": "irion.sales.transactions",
    "inventory": "irion.inventory.snapshots",
    "ad_spend": "irion.ad.spend",
    "buybox": "irion.buybox.events",
    "competitor": "irion.competitor.prices",
}

# Base price distributions derived from Amazon Sale Report (INR amounts)
# Typical range 300–1200 INR, with a long tail for higher-priced items
AMOUNT_PERCENTILES = [300, 400, 500, 650, 750, 900, 1200, 2500]

# Real categories from Amazon Sale Report with approximate proportions
REAL_CATEGORIES = {
    "Kurta": 0.22,
    "Set": 0.18,
    "Western Dress": 0.15,
    "Top": 0.13,
    "Ethnic Dress": 0.10,
    "Blouse": 0.07,
    "Bottom": 0.06,
    "Dupatta": 0.05,
    "Saree": 0.04,
}

# Real sizes from Sale Report
SIZES = ["S", "M", "L", "XL", "XXL", "3XL"]

# Ad campaign types (Amazon SP Ads taxonomy)
CAMPAIGN_TYPES = [
    "SPONSORED_PRODUCTS",
    "SPONSORED_BRANDS",
    "SPONSORED_DISPLAY",
    "SPONSORED_BRANDS_VIDEO",
]

# Competitor brand names (fictional but realistic)
COMPETITOR_NAMES = [
    "BrandX Official",
    "StyleHub",
    "FashionNest",
    "EthnicVogue",
    "TrendSutra",
    "DesiCloth",
    "WearItIndia",
    "FabricMart",
    "UrbanThreads",
    "RoyalEthnics",
]


# ---------------------------------------------------------------------------
# Data loading from real CSVs
# ---------------------------------------------------------------------------
def load_product_catalog() -> list[dict[str, Any]]:
    """Build a synthetic product catalog (250 products) using real CSV distributions."""

    # --- Extract real category proportions from Amazon Sale Report ---
    amzn_path = DATA_DIR / "Amazon Sale Report.csv"
    if amzn_path.exists():
        amzn = pd.read_csv(amzn_path, low_memory=False)
        # Filter valid amounts
        valid = amzn[amzn["Amount"].notna() & (amzn["Amount"] > 0)]
        amounts = valid["Amount"].values
        categories = valid["Category"].value_counts(normalize=True).to_dict()
    else:
        amounts = pd.Series(AMOUNT_PERCENTILES).values  # fallback
        categories = REAL_CATEGORIES.copy()

    # --- Extract real SKU codes from Sale Report ---
    sale_path = DATA_DIR / "Sale Report.csv"
    if sale_path.exists():
        sale = pd.read_csv(sale_path)
        sku_stock = dict(zip(sale["SKU Code"], sale["Stock"]))
        catalog_categories = sale["Category"].unique().tolist()
    else:
        sku_stock = {}
        catalog_categories = list(REAL_CATEGORIES.keys())

    # --- Cross-platform MRP from P L March 2021 ---
    pl_path = DATA_DIR / "P  L March 2021.csv"
    if pl_path.exists():
        pl = pd.read_csv(pl_path)
        platform_mrps = {}
        for col in ["Amazon MRP", "Flipkart MRP", "Myntra MRP", "Ajio MRP", "Limeroad MRP"]:
            vals = pd.to_numeric(pl[col], errors='coerce').dropna()
            if len(vals) > 0:
                platform_mrps[col.replace(" MRP", "")] = vals.median()
    else:
        platform_mrps = {}

    # --- Read Walmart Sales for weekly sales patterns ---
    walmart_path = DATA_DIR / "Walmart_Sales.csv"
    if walmart_path.exists():
        walmart = pd.read_csv(walmart_path)
        holiday_factor = (
            walmart[walmart["Holiday_Flag"] == 1]["Weekly_Sales"].mean()
            / walmart[walmart["Holiday_Flag"] == 0]["Weekly_Sales"].mean()
        )
    else:
        holiday_factor = 1.15

    # --- Build 250 products (50 per retailer × 5 retailers) ---
    products = []
    np_random = random.Random(42)  # seeded for reproducibility

    # Prepare category list with probabilities
    cat_list = list(categories.keys()) if categories else catalog_categories
    cat_weights = [categories.get(c, 1.0) for c in cat_list]
    # Normalize
    total_w = sum(cat_weights)
    cat_weights = [w / total_w for w in cat_weights]

    product_id = 0
    for retailer in RETAILERS:
        for i in range(50):
            product_id += 1
            cat = np_random.choices(cat_list, weights=cat_weights, k=1)[0]
            size = np_random.choice(SIZES)

            # Base price from real amount distribution if available, else percentile-based
            if len(amounts) > 0:
                base_price = round(float(np_random.choice(amounts)), 2)
            else:
                base_price = round(np_random.uniform(300, 2500), 2)

            # Add slight retailer-specific price variation (±10%)
            price_mult = np_random.uniform(0.90, 1.10)
            price = round(base_price * price_mult, 2)

            # Cost is 40-60% of price
            cost = round(price * np_random.uniform(0.40, 0.60), 2)

            # Initial stock — realistic retail inventory (10x previous)
            stock = np_random.randint(500, 5000)

            products.append(
                {
                    "product_id": product_id,
                    "retailer_id": retailer["id"],
                    "retailer_name": retailer["name"],
                    "sku": f"SKU-{retailer['id']:03d}-{product_id:04d}",
                    "category": cat.strip() if isinstance(cat, str) else str(cat),
                    "size": size,
                    "base_price": price,
                    "cost": cost,
                    "stock": stock,
                    "reorder_point": np_random.randint(20, 60),
                }
            )

    print(f"Built product catalog: {len(products)} products across {len(RETAILERS)} retailers")
    print(f"  Categories: {list(categories.keys())[:8]}...")
    print(f"  Amount range: {min(amounts) if len(amounts)>0 else 300:.0f} – {max(amounts) if len(amounts)>0 else 2500:.0f}")
    print(f"  Holiday uplift factor: {holiday_factor:.2f}x")

    return products


# ---------------------------------------------------------------------------
# Message generators
# ---------------------------------------------------------------------------
class DataSimulator:
    """Generates and publishes simulated e-commerce data to Kafka."""

    def __init__(self, broker: str, products: list[dict[str, Any]]):
        self.products = products
        self.broker = broker
        self.running = False
        self.producer: KafkaProducer | None = None
        self.lock = threading.Lock()
        self.orders_today = 0
        self.revenue_today = 0.0

        # Track current stock per product (mutable)
        self.stock: dict[int, int] = {p["product_id"]: p["stock"] for p in products}

        # Track buybox winner price for competitor price logic
        self.buybox_price: dict[int, float] = {}

    def connect(self):
        """Establish Kafka producer connection with retries."""
        max_retries = 15
        for attempt in range(1, max_retries + 1):
            try:
                self.producer = KafkaProducer(
                    bootstrap_servers=self.broker,
                    value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                    key_serializer=lambda k: str(k).encode("utf-8") if k else None,
                    acks=1,
                    compression_type="gzip",
                    max_block_ms=30000,
                )
                print(f"Connected to Kafka broker at {self.broker}")
                return
            except NoBrokersAvailable:
                if attempt < max_retries:
                    wait = min(attempt * 2, 30)
                    print(f"  Waiting for Kafka broker... (attempt {attempt}/{max_retries}, retry in {wait}s)")
                    time.sleep(wait)
                else:
                    raise
        raise RuntimeError(f"Could not connect to Kafka at {self.broker} after {max_retries} attempts")

    def seed_catalog(self):
        """Send product catalog as seed data to the sales topic so downstream
        consumers see available products before any transactions arrive."""
        print("Seeding product catalog...")
        for p in self.products:
            self._send("sales", key=p["product_id"], value={"type": "product_catalog", **p})
        self.producer.flush()
        print(f"  Seeded {len(self.products)} products")

    def start(self, duration: float | None = None):
        """Main simulation loop. Runs until duration expires or indefinitely."""
        self.running = True
        self.connect()
        self.seed_catalog()

        start_time = time.time()
        last_inventory = 0.0
        last_ad = 0.0
        last_buybox = 0.0
        last_competitor = 0.0

        print("\nSimulation running. Press Ctrl+C to stop.\n")
        try:
            while self.running:
                now = time.time()

                # Check duration
                if duration is not None and (now - start_time) >= duration:
                    self.running = False
                    break

                # ---- Sales transactions (5–20 per second) ----
                batch_size = random.randint(5, 20)
                for _ in range(batch_size):
                    msg = self._gen_sale()
                    self._send("sales", key=msg["product_id"], value=msg)

                # ---- Inventory snapshots (every 10s) ----
                if now - last_inventory >= 10.0:
                    last_inventory = now
                    sampled = random.sample(self.products, min(20, len(self.products)))
                    for p in sampled:
                        self._send("inventory", key=p["product_id"], value=self._gen_inventory(p))

                # ---- Ad spend (every 30s) ----
                if now - last_ad >= 30.0:
                    last_ad = now
                    sampled = random.sample(self.products, min(15, len(self.products)))
                    for p in sampled:
                        self._send("ad_spend", key=p["product_id"], value=self._gen_ad_spend(p))

                # ---- Buy Box events (every 15s) ----
                if now - last_buybox >= 15.0:
                    last_buybox = now
                    sampled = random.sample(self.products, min(10, len(self.products)))
                    for p in sampled:
                        msg = self._gen_buybox(p)
                        self._send("buybox", key=p["product_id"], value=msg)
                        self.buybox_price[p["product_id"]] = msg["winner_price"]

                # ---- Competitor prices (every 60s) ----
                if now - last_competitor >= 60.0:
                    last_competitor = now
                    sampled = random.sample(self.products, min(25, len(self.products)))
                    for p in sampled:
                        self._send("competitor", key=p["product_id"], value=self._gen_competitor_price(p))

                # Throttle loop to avoid busy-waiting
                time.sleep(0.05)

        except KeyboardInterrupt:
            print("\nShutting down (Ctrl+C)...")
        finally:
            self._shutdown(start_time)

    def _gen_sale(self) -> dict[str, Any]:
        """Generate a single sales transaction."""
        p = random.choice(self.products)
        now = datetime.now()

        # Deduct stock (simulate real inventory movement)
        qty = random.choices([1, 1, 1, 2, 3], weights=[0.70, 0.10, 0.10, 0.07, 0.03], k=1)[0]
        with self.lock:
            avail = self.stock.get(p["product_id"], 0)
            if avail < qty:
                qty = max(1, avail)  # sell what we can

        unit_price = round(p["base_price"] * random.uniform(0.85, 1.15), 2)
        revenue = round(unit_price * qty, 2)
        retailer = RETAILERS[p["retailer_id"] - 1]

        # Realistic status distribution
        status = random.choices(
            ["Shipped", "Shipped - Delivered to Buyer", "Pending", "Cancelled"],
            weights=[0.55, 0.20, 0.15, 0.10],
            k=1,
        )[0]

        # Fulfillment channel based on retailer weights
        fw = retailer["fulfillment_weights"]
        channel = random.choices(list(fw.keys()), weights=list(fw.values()), k=1)[0]

        order_id = f"ORD-{random.randint(1000000, 9999999)}"

        with self.lock:
            self.orders_today += 1
            self.revenue_today += revenue
            if qty <= self.stock.get(p["product_id"], 0):
                self.stock[p["product_id"]] = max(0, self.stock[p["product_id"]] - qty)
            # Auto-replenish when stock drops below reorder point
            if self.stock.get(p["product_id"], 0) < p.get("reorder_point", 30):
                self.stock[p["product_id"]] += p.get("stock", 2000)

        return {
            "order_id": order_id,
            "product_id": p["product_id"],
            "retailer_id": p["retailer_id"],
            "retailer_name": retailer["name"],
            "date": now.strftime("%Y-%m-%d"),
            "timestamp": now.isoformat(),
            "status": status,
            "fulfillment_channel": channel,
            "qty": qty,
            "unit_price": unit_price,
            "revenue": revenue,
            "currency": "USD",
            "category": p["category"],
            "sku": p["sku"],
        }

    def _gen_inventory(self, p: dict[str, Any]) -> dict[str, Any]:
        """Periodic inventory snapshot."""
        now = datetime.now()
        with self.lock:
            stock = self.stock[p["product_id"]]
        return {
            "product_id": p["product_id"],
            "retailer_id": p["retailer_id"],
            "retailer_name": p["retailer_name"],
            "snapshot_date": now.strftime("%Y-%m-%d"),
            "timestamp": now.isoformat(),
            "stock_on_hand": stock,
            "reorder_point": p["reorder_point"],
            "sku": p["sku"],
        }

    def _gen_ad_spend(self, p: dict[str, Any]) -> dict[str, Any]:
        """Generate ad spend metrics. Spend = 15–25% of estimated daily revenue."""
        now = datetime.now()
        # Estimate daily revenue from base price × ~3 units/day
        est_daily_revenue = p["base_price"] * random.uniform(2.0, 5.0)
        spend_rate = random.uniform(0.15, 0.25)
        daily_spend = round(est_daily_revenue * spend_rate / 24, 2)  # hourly, but we emit every 30s

        campaign = random.choice(CAMPAIGN_TYPES)
        impressions = int(daily_spend * random.uniform(80, 200))  # CPM ~$5-12
        ctr = random.uniform(0.002, 0.05)
        clicks = max(1, int(impressions * ctr))
        cvr = random.uniform(0.02, 0.15)
        attributed_sales = round(clicks * cvr * p["base_price"], 2)

        return {
            "product_id": p["product_id"],
            "retailer_id": p["retailer_id"],
            "retailer_name": p["retailer_name"],
            "date": now.strftime("%Y-%m-%d"),
            "timestamp": now.isoformat(),
            "campaign_type": campaign,
            "spend": daily_spend,
            "impressions": impressions,
            "clicks": clicks,
            "attributed_sales": attributed_sales,
            "acos": round(daily_spend / max(attributed_sales, 0.01), 4),
            "sku": p["sku"],
        }

    def _gen_buybox(self, p: dict[str, Any]) -> dict[str, Any]:
        """Generate Buy Box win/loss event."""
        now = datetime.now()
        our_price = round(p["base_price"] * random.uniform(0.90, 1.10), 2)

        # Competitor price — sometimes lower, sometimes higher
        price_gap = random.gauss(0, 0.08)  # mean 0, std 8%
        competitor_price = round(our_price * (1 + price_gap), 2)

        # Buy box win probability
        if competitor_price < our_price:
            won_prob = 0.20  # we're more expensive → low chance
        else:
            won_prob = 0.65  # we're cheaper or equal → good chance

        won = random.random() < won_prob
        winner_type = "SELF" if won else "COMPETITOR"
        winner_price = our_price if won else competitor_price

        return {
            "product_id": p["product_id"],
            "retailer_id": p["retailer_id"],
            "retailer_name": p["retailer_name"],
            "date": now.strftime("%Y-%m-%d"),
            "timestamp": now.isoformat(),
            "won": won,
            "winner_type": winner_type,
            "our_price": our_price,
            "winner_price": winner_price,
            "competitor_price": competitor_price,
            "price_delta_pct": round((our_price - competitor_price) / competitor_price * 100, 2),
            "sku": p["sku"],
        }

    def _gen_competitor_price(self, p: dict[str, Any]) -> dict[str, Any]:
        """Generate competitor price observation."""
        now = datetime.now()
        competitor = random.choice(COMPETITOR_NAMES)

        # Competitor prices usually within ±15% of our price
        price = round(p["base_price"] * random.uniform(0.85, 1.15), 2)

        return {
            "product_id": p["product_id"],
            "retailer_id": p["retailer_id"],
            "retailer_name": p["retailer_name"],
            "date": now.strftime("%Y-%m-%d"),
            "timestamp": now.isoformat(),
            "competitor_name": competitor,
            "price": price,
            "our_price": p["base_price"],
            "price_delta_pct": round((p["base_price"] - price) / price * 100, 2),
            "sku": p["sku"],
        }

    def _send(self, topic_key: str, key: Any, value: dict[str, Any]):
        """Send a message to a Kafka topic."""
        if self.producer is None:
            return
        topic = TOPICS[topic_key]
        try:
            self.producer.send(topic, key=key, value=value)
        except Exception as e:
            print(f"  [WARN] Failed to send to {topic}: {e}")

    def _shutdown(self, start_time: float):
        """Clean shutdown with summary."""
        elapsed = time.time() - start_time
        self.running = False
        if self.producer:
            self.producer.flush(timeout=10)
            self.producer.close()
        print(f"\nSimulation ended. Ran for {elapsed:.0f}s")
        print(f"  Orders: {self.orders_today}")
        print(f"  Revenue: ${self.revenue_today:,.2f}")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="Irion E-Commerce Kafka Data Simulator",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s
  %(prog)s --duration 3600
  %(prog)s --kafka-broker kafka:9092 --duration 600
        """,
    )
    parser.add_argument(
        "--kafka-broker",
        default=os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
        help="Kafka bootstrap server (default: localhost:9092, env: KAFKA_BOOTSTRAP_SERVERS)",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help="Run duration in seconds (default: run until interrupted)",
    )
    args = parser.parse_args()

    # Load product catalog from real data
    products = load_product_catalog()

    # Write product catalog seed to Parquet for Irion API
    import pyarrow as pa, pyarrow.parquet as pq
    from pathlib import Path
    silver = Path("../irion/data/silver")
    silver.mkdir(parents=True, exist_ok=True)
    seed = pa.Table.from_pylist([{
        "id": p["product_id"],
        "sku_code": p["sku"],
        "product_name": p.get("retailer_name", "") + " " + p.get("category", ""),
        "category": p["category"],
    } for p in products])
    pq.write_table(seed, silver / "product_catalog_seed.parquet")
    print(f"Wrote product_catalog_seed.parquet: {len(products)} products")

    # Create simulator
    sim = DataSimulator(broker=args.kafka_broker, products=products)

    sim.start(duration=args.duration)


if __name__ == "__main__":
    main()
