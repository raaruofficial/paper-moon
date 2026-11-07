#!/usr/bin/env python3
"""Paper Moon chart scrub — burst VERIFY job.

Compares the frozen scenario candles in the paper-moon repo
(app/src/main/assets/game/index.html, `var SCENARIOS=[...]`) against the
exchange's recorded candles (Binance public klines, no API key) and writes
chart-scrub-report.json into the repo root for the orchestrator to harvest.

Design (owner-ruled 2026-11-07: the scrub runs on the Amazon burst box,
never as Muse-credit work):
  - The charts in the game are NOT fed by an API. They draw the frozen
    bundled candles; the game is self-contained (zero network except the
    purchase settlement check). This script is a VERIFICATION tool: it
    checks the frozen candles against the exchange, it does not feed them.
  - Stdlib only (urllib/json/re/sys/time). No key, no secret, no write to
    the frozen data. The frozen bundle is read-only input.
  - Exit 0 whenever the report is written — even with mismatches. A
    mismatch is a FINDING (it triggers the spec's fresh-freeze + gate, an
    owner decision), never an automatic re-freeze and never a job failure.
    Exit non-zero only if the report itself cannot be produced.
  - A scenario whose coin has no Binance pair (or whose fetch fails after
    retries) is UNVERIFIABLE, not FAILED — the run continues and the report
    says so per scenario.

Usage (run from the cloned paper-moon repo root, as the burst job does):
    python3 verification/chart-scrub.py [--bundle app/src/main/assets/game/index.html]
                                        [--out chart-scrub-report.json]
"""

import json
import re
import sys
import time
import urllib.error
import urllib.request

BINANCE = "https://api.binance.com/api/v3/klines"
TOLERANCE = 0.005  # 0.5% relative per OHLC field
INTERVAL_MS = {"1d": 86_400_000, "1h": 3_600_000}

# Coin -> Binance symbol overrides. Default is {COIN}USDT.
# LUNA: Binance renamed LUNA to LUNC on 2022-05-26; the pre-rename history
# (including the May 2022 death spiral this scenario replays) lives under
# LUNCUSDT. The luna-death window (2022-05-01 -> 2022-05-14) is entirely
# pre-rename, so it must query LUNCUSDT.
SYMBOL_OVERRIDES = {"LUNA": "LUNCUSDT"}


def load_frozen(bundle_path):
    with open(bundle_path, encoding="utf-8") as fh:
        html = fh.read()
    m = re.search(r"var SCENARIOS=(\[.*?\]);", html, re.S)
    if not m:
        raise SystemExit(f"no SCENARIOS array found in {bundle_path}")
    scenarios = json.loads(m.group(1))
    return scenarios


def fetch_klines(symbol, interval, start_ms, end_ms):
    """Fetch klines for the window; paginates if >1000 candles. Returns
    {openTime_ms: [open, high, low, close]} or raises on final failure."""
    out = {}
    cur = start_ms
    step = INTERVAL_MS[interval]
    for _ in range(5):  # pagination guard; longest scenario is 693 candles
        url = (f"{BINANCE}?symbol={symbol}&interval={interval}"
               f"&startTime={cur}&endTime={end_ms}&limit=1000")
        last_err = None
        for attempt, backoff in enumerate((0.2, 1.0, 2.0, 4.0)):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "paper-moon-chart-scrub/1.0"})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    rows = json.load(resp)
                break
            except urllib.error.HTTPError as e:
                last_err = e
                if e.code in (429, 500, 502, 503) and attempt < 3:
                    time.sleep(backoff)
                    continue
                raise
            except (urllib.error.URLError, TimeoutError) as e:
                last_err = e
                if attempt < 3:
                    time.sleep(backoff)
                    continue
                raise
        else:
            raise last_err
        if not rows:
            break
        for r in rows:
            out[int(r[0])] = [float(r[1]), float(r[2]), float(r[3]), float(r[4])]
        last_open = int(rows[-1][0])
        if last_open + step >= end_ms:
            break
        cur = last_open + step
        time.sleep(0.2)
    return out


def symbol_exists(symbol):
    """Probe whether a symbol exists (one cheap klines call, limit 1)."""
    url = f"{BINANCE}?symbol={symbol}&interval=1d&limit=1"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "paper-moon-chart-scrub/1.0"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            json.load(resp)
        return True
    except urllib.error.HTTPError as e:
        if e.code == 400:
            return False
        raise
    except Exception:
        raise


def main(argv):
    bundle = "app/src/main/assets/game/index.html"
    out_path = "chart-scrub-report.json"
    i = 0
    while i < len(argv):
        if argv[i] == "--bundle":
            i += 1
            bundle = argv[i]
        elif argv[i] == "--out":
            i += 1
            out_path = argv[i]
        i += 1

    scenarios = load_frozen(bundle)
    results = []
    for sc in scenarios:
        entry = {
            "id": sc["id"], "coin": sc["coin"], "interval": sc["interval"],
            "entry_date": sc["entry_date"], "end_date": sc["end_date"],
            "candles_frozen": len(sc["candles"]),
        }
        step = INTERVAL_MS[sc["interval"]]
        frozen = {int(c[0]): [float(v) for v in c[1:5]] for c in sc["candles"]}
        first = min(frozen)
        last = max(frozen)
        symbol = SYMBOL_OVERRIDES.get(sc["coin"], f"{sc['coin']}USDT")
        entry["symbol"] = symbol
        try:
            exists = symbol_exists(symbol)
        except Exception as e:
            entry.update(status="UNVERIFIABLE", note=f"symbol probe failed: {e}")
            results.append(entry)
            continue
        if not exists:
            entry.update(status="UNVERIFIABLE",
                         note=f"no Binance pair {symbol}")
            results.append(entry)
            continue
        try:
            exchange = fetch_klines(symbol, sc["interval"], first, last + step)
        except Exception as e:
            entry.update(status="UNVERIFIABLE", note=f"klines fetch failed: {e}")
            results.append(entry)
            continue
        compared = 0
        mismatches = []
        worst = 0.0
        for ts in sorted(frozen):
            if ts not in exchange:
                mismatches.append({"ts": ts, "field": "missing",
                                   "frozen": frozen[ts], "exchange": None})
                continue
            compared += 1
            for fi, field in enumerate(("open", "high", "low", "close")):
                f = frozen[ts][fi]
                x = exchange[ts][fi]
                denom = max(abs(f), 1e-12)
                dev = abs(x - f) / denom
                worst = max(worst, dev)
                if dev > TOLERANCE:
                    mismatches.append({"ts": ts, "field": field,
                                       "frozen": f, "exchange": x,
                                       "rel_dev": round(dev, 6)})
        entry.update(candles_compared=compared, matches=compared - len(
            {m["ts"] for m in mismatches if m["field"] != "missing"}),
            mismatches=mismatches[:20], mismatch_count=len(mismatches),
            worst_rel_dev=round(worst, 6),
            status="MISMATCH" if mismatches else "MATCH")
        results.append(entry)
        time.sleep(0.2)

    summary = {
        "total": len(results),
        "matched": sum(1 for r in results if r["status"] == "MATCH"),
        "mismatched": sum(1 for r in results if r["status"] == "MISMATCH"),
        "unverifiable": sum(1 for r in results if r["status"] == "UNVERIFIABLE"),
        "candles_frozen": sum(r["candles_frozen"] for r in results),
    }
    report = {
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": "Binance public klines vs frozen bundle (app/src/main/assets/game/index.html)",
        "tolerance_rel": TOLERANCE,
        "note": ("A MISMATCH triggers the spec's fresh-freeze + gate (owner "
                 "decision) — never an automatic re-freeze. UNVERIFIABLE "
                 "means no Binance pair or a failed fetch, not a bad candle."),
        "scenarios": results,
        "summary": summary,
    }
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=1)
    print(f"chart scrub: {summary['matched']} MATCH / {summary['mismatched']} MISMATCH / "
          f"{summary['unverifiable']} UNVERIFIABLE of {summary['total']} scenarios "
          f"({summary['candles_frozen']} frozen candles) -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
