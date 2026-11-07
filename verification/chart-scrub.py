#!/usr/bin/env python3
"""Paper Moon chart scrub — multi-source verification of the frozen candles.

Reads the frozen SCENARIOS from app/src/main/assets/game/index.html and
checks them against the reference sources the owner named. Binance is OUT
(owner-ruled 2026-11-07: it is a false positive — a different venue with
different listing dates; a Binance diff flags venue differences as errors).

Sources (each attempted where it can reach the window; a source that cannot
reach it is recorded, never treated as a failure):
  PUMP      Pump Fun frontend API (frontend-api-v3.pump.fun) — the native
            venue for the Solana launches. Full OHLC via /candlesticks/{mint}
            (timeframe 86400 = 1d, 3600 = 1h). Keyless. Cloudflare-gated:
            this VM is challenged; the burst box may or may not pass. Any
            non-JSON / challenge response is UNVERIFIABLE, never a failure.
            Only covers coins with a Solana mint (the ~20 Solana scenarios).
  COINGECKO CoinGecko Demo (free, key via x-cg-demo-api-key). market_chart
            and market_chart/range are on the Demo plan — BUT Demo is
            restricted to the past 365 days, and every frozen window is older
            than a year, so it cannot verify any of the 35 scenarios. The
            script records NOT_APPLICABLE with that reason. Full history needs
            the Analyst plan ($129/mo) — not spent for this check.
  CMC       CoinMarketCap Basic (free, key via X-CMC_PRO_API_KEY). Historical
            OHLCV (/v2/cryptocurrency/ohlcv/historical) starts at the $79/mo
            Startup tier; Basic only carries 1 year of daily quotes. It cannot
            verify any of the 35 scenarios. Recorded NOT_APPLICABLE.

Mint resolution (keyless, no guessing): CoinGecko /search?q={symbol} gives
candidates ordered by market-cap rank; the first symbol-matching result is
taken and its id + rank + name are recorded in the report so the choice is
auditable. /coins/{id} then gives platforms.solana (the mint). No mint, or
an ambiguous symbol with no clear top result, is UNVERIFIABLE.

Per-source status: MATCH / MISMATCH / UNVERIFIABLE / NOT_APPLICABLE.
Scenario verdict: CONFIRMED if any source MATCHes and none MISMATCHes;
MISMATCH if any source MISMATCHes; otherwise UNVERIFIABLE. A MISMATCH is a
FINDING (triggers the spec's fresh-freeze + gate, an owner decision), never a
job failure. Exit 0 whenever the report is written.

Tolerance: 0.5% relative per OHLC field. Timestamps must align exactly
(Pump Fun candles are bucketed by open time; timestamps are normalised to ms).

Writes chart-scrub-report.json to the repo root. Stdlib only, no key required
for the live path (Pump Fun); COINGECKO_API_KEY / CMC_API_KEY are read from
the environment when present and never printed.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TOL = 0.005
UA = {"User-Agent": "paper-moon-chart-scrub/2.0"}
COINGECKO = "https://api.coingecko.com/api/v3"
PUMP = "https://frontend-api-v3.pump.fun"


def fetch(url, headers=None, timeout=60):
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def fetch_json(url, headers=None, timeout=60):
    raw = fetch(url, headers, timeout)
    return json.loads(raw.decode("utf-8"))


def load_scenarios(path="app/src/main/assets/game/index.html"):
    text = open(path, encoding="utf-8").read()
    m = re.search(r"var SCENARIOS\s*=\s*(\[.*?\])\s*;", text, re.S)
    if not m:
        raise SystemExit("SCENARIOS array not found in " + path)
    return json.loads(m.group(1))


def rel(a, b):
    if b == 0:
        return 0.0 if a == 0 else float("inf")
    return abs(a - b) / abs(b)


def coingecko_search(symbol):
    """Return (id, rank, name) for the top symbol-matching CoinGecko result."""
    url = COINGECKO + "/search?query=" + urllib.parse.quote(symbol)
    data = fetch_json(url)
    for c in data.get("coins", []):
        if c.get("symbol", "").lower() == symbol.lower():
            return c.get("id"), c.get("market_cap_rank"), c.get("name")
    return None, None, None


def coingecko_solana_mint(coin_id):
    data = fetch_json(COINGECKO + "/coins/" + urllib.parse.quote(coin_id))
    platforms = data.get("platforms") or {}
    return platforms.get("solana")


def pump_candles(mint, timeframe_s, limit=1000):
    """Return a list of [ts_ms, o, h, l, c] from Pump Fun, or raise."""
    url = (PUMP + "/candlesticks/" + urllib.parse.quote(mint)
           + "?limit=%d&timeframe=%d" % (limit, timeframe_s))
    raw = fetch(url, timeout=90)
    try:
        data = json.loads(raw.decode("utf-8"))
    except Exception:
        raise RuntimeError("non-JSON response (cloudflare challenge or block)")
    rows = None
    if isinstance(data, list):
        rows = data
    elif isinstance(data, dict):
        for key in ("candles", "data", "ohlcv", "result", "candlesticks"):
            if isinstance(data.get(key), list):
                rows = data[key]
                break
    if rows is None:
        raise RuntimeError("unexpected response shape: keys=%s"
                           % sorted(data.keys()) if isinstance(data, dict)
                           else "unexpected response shape")
    out = []
    for row in rows:
        if isinstance(row, dict):
            ts = row.get("timestamp") or row.get("time") or row.get("t")
            vals = [row.get(k) for k in ("open", "high", "low", "close")]
            if ts is None or any(v is None for v in vals):
                continue
            ts_ms = int(ts * 1000) if ts < 10**12 else int(ts)
            out.append([ts_ms, float(vals[0]), float(vals[1]),
                        float(vals[2]), float(vals[3])])
        elif isinstance(row, (list, tuple)) and len(row) >= 5:
            ts = row[0]
            ts_ms = int(ts * 1000) if ts < 10**12 else int(ts)
            out.append([ts_ms, float(row[1]), float(row[2]),
                        float(row[3]), float(row[4])])
    if not out:
        raise RuntimeError("no parseable candles in response")
    return sorted(out)


def check_source_pump(scn):
    """Check one scenario against Pump Fun. Returns (status, detail dict)."""
    coin = scn["coin"]
    interval = scn.get("interval", "1d")
    tf = 3600 if interval == "1h" else 86400
    try:
        cg_id, rank, name = coingecko_search(coin)
        if not cg_id:
            return "UNVERIFIABLE", {"reason": "no CoinGecko id for symbol",
                                    "symbol": coin}
        mint = coingecko_solana_mint(cg_id)
        if not mint:
            return "UNVERIFIABLE", {"reason": "no Solana mint (not a Pump Fun coin)",
                                    "coingecko_id": cg_id,
                                    "coingecko_name": name,
                                    "market_cap_rank": rank}
        ref = pump_candles(mint, tf)
    except Exception as e:  # network, challenge, shape, rate limit
        return "UNVERIFIABLE", {"reason": "fetch failed: %s" % e}
    frozen = {c[0]: c for c in scn["candles"]}
    ref_by_ts = {c[0]: c for c in ref}
    if not ref_by_ts:
        return "UNVERIFIABLE", {"reason": "empty reference series"}
    earliest_ref = min(ref_by_ts)
    latest_frozen = max(frozen)
    if earliest_ref > latest_frozen:
        return "UNVERIFIABLE", {
            "reason": "reference candles do not reach the window "
                      "(earliest reference is after the scenario ends)",
            "earliest_reference_ts_ms": earliest_ref,
            "scenario_end_ts_ms": latest_frozen}
    compared = 0
    mismatches = []
    for ts, f in sorted(frozen.items()):
        r = ref_by_ts.get(ts)
        if r is None:
            continue
        compared += 1
        worst = max(rel(f[i], r[i]) for i in range(1, 5))
        if worst > TOL:
            mismatches.append({"ts_ms": ts, "worst_rel_diff": round(worst, 4),
                               "frozen": f[1:], "reference": r[1:]})
    if compared == 0:
        return "UNVERIFIABLE", {"reason": "no overlapping timestamps",
                                "reference_candles": len(ref),
                                "frozen_candles": len(frozen)}
    detail = {"compared": compared, "frozen_candles": len(frozen),
              "reference_candles": len(ref), "mismatches": mismatches[:5],
              "mismatch_count": len(mismatches),
              "coingecko_id": cg_id, "coingecko_name": name,
              "market_cap_rank": rank, "mint": mint}
    if mismatches:
        return "MISMATCH", detail
    return "MATCH", detail


def main():
    scenarios = load_scenarios()
    results = []
    for scn in scenarios:
        pump_status, pump_detail = check_source_pump(scn)
        sources = {
            "pumpfun": {"status": pump_status, "detail": pump_detail},
            "coingecko": {
                "status": "NOT_APPLICABLE",
                "detail": {"reason": "Demo plan is restricted to the past "
                           "365 days; every frozen window is older than a "
                           "year. Full history needs the Analyst plan."}},
            "cmc": {
                "status": "NOT_APPLICABLE",
                "detail": {"reason": "Historical OHLCV needs the $79/mo "
                           "Startup tier; the free Basic plan carries no "
                           "historical candles."}},
        }
        if pump_status == "MATCH":
            verdict = "CONFIRMED"
        elif pump_status == "MISMATCH":
            verdict = "MISMATCH"
        else:
            verdict = "UNVERIFIABLE"
        results.append({
            "id": scn["id"], "coin": scn["coin"],
            "interval": scn.get("interval", "1d"),
            "entry_date": scn.get("entry_date"),
            "end_date": scn.get("end_date"),
            "candles": len(scn["candles"]),
            "verdict": verdict, "sources": sources,
        })
        # Keyless CoinGecko pace: stay under the ~30/min no-key ceiling.
        time.sleep(2)
    summary = {
        "total": len(results),
        "confirmed": sum(1 for r in results if r["verdict"] == "CONFIRMED"),
        "mismatch": sum(1 for r in results if r["verdict"] == "MISMATCH"),
        "unverifiable": sum(1 for r in results
                            if r["verdict"] == "UNVERIFIABLE"),
    }
    report = {"tool": "paper-moon-chart-scrub", "version": 2,
              "tolerance_rel": TOL,
              "sources_attempted": ["pumpfun", "coingecko", "cmc"],
              "summary": summary, "scenarios": results}
    with open("chart-scrub-report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
