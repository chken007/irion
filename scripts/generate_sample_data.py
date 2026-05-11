#!/usr/bin/env python3
"""
Generate 1000 rows of sample SKU data for Irion testing.

Output: data/bronze/sample_sku_data.csv
Columns: sku_id, asin, product_name, category, date, inventory_on_hand,
         sales_units, revenue, ad_spend, has_buy_box, my_price, competitor_price

5 SKU scenarios × 200 days each = 1000 rows.

Scenario overview:
  ABC-001  Healthy:       WOS ~6,  has Buy Box, ROAS ~3.5
  DEF-002  OOS risk:      WOS ~1,  no Buy Box,  has ad spend
  GHI-003  Competitor low:WOS ~4,  Buy Box LOST, competitor_price < my_price
  JKL-004  Shared BB:     WOS ~3,  Buy Box SHARED
  MNO-005  Zero ad:       WOS ~8,  has Buy Box, ad_spend = 0
"""

import csv
import math
import os
import random
from datetime import date, timedelta

random.seed(42)

OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "..", "data", "bronze")
OUTPUT_FILE = os.path.join(OUTPUT_DIR, "sample_sku_data.csv")

COLUMNS = [
    "sku_id", "asin", "product_name", "category", "date",
    "inventory_on_hand", "sales_units", "revenue", "ad_spend",
    "has_buy_box", "my_price", "competitor_price",
]

# ── Scenario definitions ──────────────────────────────────────────────
# Each scenario: (sku_id, asin, product_name, category,
#                 base_sales, base_inventory, base_my_price, base_competitor_price,
#                 buy_box_mode, ad_mode, roas_target)
#
# buy_box_mode: "won" | "lost" | "shared"
# ad_mode: "on" | "off"
# roas_target: desired ROAS (only used when ad_mode="on")

SCENARIOS = [
    # ABC-001: Healthy, WOS ~6, has Buy Box, ROAS ~3.5
    ("ABC-001", "B0ABC00100", "Wireless Earbuds Pro", "Electronics",
     70.0, 420.0, 25.00, 25.50, "won", "on", 3.5),

    # DEF-002: OOS risk, WOS ~1, no Buy Box, has ad spend
    ("DEF-002", "B0DEF00200", "Organic Protein Powder", "Grocery",
     140.0, 140.0, 30.00, 28.00, "lost", "on", 2.0),

    # GHI-003: Competitor underpricing, WOS ~4, Buy Box LOST
    ("GHI-003", "B0GHI00300", "Yoga Mat Premium", "Sports",
     105.0, 420.0, 35.00, 30.00, "lost", "on", 4.0),

    # JKL-004: Shared Buy Box, WOS ~3
    ("JKL-004", "B0JKL00400", "Stainless Steel Bottle", "Home",
     56.0, 168.0, 20.00, 20.00, "shared", "on", 4.0),

    # MNO-005: Zero ad spend, WOS ~8, has Buy Box
    ("MNO-005", "B0MNO00500", "LED Desk Lamp", "Office",
     35.0, 280.0, 22.00, 23.00, "won", "off", None),
]

NUM_DAYS = 200
START_DATE = date(2026, 1, 1)


def fluctuate(value: float, pct: float = 0.20) -> float:
    """Add ±pct random noise to a value."""
    factor = 1.0 + random.uniform(-pct, pct)
    return round(value * factor, 4)


def buy_box_flip(base_mode: str) -> bool:
    """
    Return has_buy_box based on the scenario's dominant mode,
    with occasional random flips.
    """
    if base_mode == "won":
        # Mostly true (has Buy Box), ~10% chance of losing it
        return random.random() > 0.10
    elif base_mode == "lost":
        # Mostly false, ~10% chance of winning
        return random.random() < 0.10
    elif base_mode == "shared":
        # Roughly 50/50 flip
        return random.random() > 0.50
    return False


def generate():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    rows = []
    for scenario in SCENARIOS:
        (sku_id, asin, name, cat, base_sales, base_inv,
         my_price, comp_price, bb_mode, ad_mode, roas_target) = scenario

        # To keep WOS roughly constant over time we use a slight downward
        # drift in inventory and sales so that the ratio stays stable.
        # The most recent day (day 199) will have the "target" values;
        # earlier days are slightly higher.
        for day_idx in range(NUM_DAYS):
            current_date = START_DATE + timedelta(days=day_idx)

            # Drift factor: 1.0 on day 0 down to ~0.85 on day 199
            drift = 1.0 - (day_idx / NUM_DAYS) * 0.15

            inv = int(round(base_inv * drift))
            sales = fluctuate(base_sales * drift)
            revenue = round(sales * my_price, 2)

            if ad_mode == "on":
                ad_spend = round(revenue / roas_target, 2)
            else:
                ad_spend = 0.0

            has_bb = buy_box_flip(bb_mode)

            rows.append([
                sku_id, asin, name, cat,
                current_date.isoformat(),
                inv,
                sales,
                revenue,
                ad_spend,
                "true" if has_bb else "false",
                my_price,
                comp_price,
            ])

    # Write CSV
    with open(OUTPUT_FILE, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(COLUMNS)
        writer.writerows(rows)

    print(f"✅ Generated {len(rows)} rows → {OUTPUT_FILE}")

    # ── Quick sanity checks ───────────────────────────────────────────
    from collections import defaultdict

    by_sku = defaultdict(list)
    for row in rows:
        by_sku[row[0]].append(row)

    for sku_id, recs in by_sku.items():
        # Look at the last 28 rows (most recent 28 days) for WOS estimate
        recent = sorted(recs, key=lambda r: r[4])[-28:]
        avg_sales = sum(r[6] for r in recent) / len(recent)
        latest_inv = recent[-1][5]
        est_wos = latest_inv / avg_sales if avg_sales > 0 else 999
        bb_pct = sum(1 for r in recent if r[9] == "true") / len(recent) * 100
        total_ad = sum(r[8] for r in recent)
        total_rev = sum(r[7] for r in recent)
        est_roas = total_rev / total_ad if total_ad > 0 else None
        roas_str = f"ROAS ~{est_roas:.1f}" if est_roas else "ROAS N/A"
        print(f"  {sku_id}: WOS ~{est_wos:.1f}, BB%={bb_pct:.0f}%, {roas_str}")


if __name__ == "__main__":
    generate()
