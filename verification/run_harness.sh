#!/bin/sh
# Money-build verification harness: compiles the pure-logic money
# modules + this harness with kotlinc and runs the PASS tests.
# Requires: JDK 17 (JAVA_HOME or ~/workspace/tools jdk), kotlinc,
# and the org.json jar (Android provides org.json at runtime; the
# harness needs it on the compile classpath only).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${PM_TOOLS:-$HOME/workspace/tools}"
JAVA_HOME="${JAVA_HOME:-$(ls -d $TOOLS/jdk-17* 2>/dev/null | head -1)}"
export JAVA_HOME
KOTLINC="${KOTLINC:-$TOOLS/kotlinc/bin/kotlinc}"
JSON_JAR="${JSON_JAR:-$TOOLS/jars/json-20240303.jar}"
OUT=/tmp/pm-money-harness
rm -rf "$OUT"; mkdir -p "$OUT"
"$KOTLINC" -cp "$JSON_JAR" -include-runtime -d "$OUT/harness.jar" \
  "$ROOT"/app/src/main/java/com/hindsight/app/util/Base58.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/MoneyConfig.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/Ed25519.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/ShellVerifier.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/SecureStore.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/EntitlementLedger.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/RookieLedger.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/TxErrors.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/PurchaseFlow.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/SettlementVerifier.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/TransactionBuilder.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/PurchaseCoordinator.kt \
  "$ROOT"/app/src/main/java/com/hindsight/app/money/SolanaRpc.kt \
  "$ROOT"/verification/MoneyBuildHarness.kt
"$JAVA_HOME/bin/java" -cp "$OUT/harness.jar:$JSON_JAR" MoneyBuildHarnessKt
