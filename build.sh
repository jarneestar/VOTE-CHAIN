#!/bin/bash
# ============================================================
# VoteChain — Java Build Script
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_DIR="$SCRIPT_DIR/java"
LIB_DIR="$SCRIPT_DIR/lib"
OUT_DIR="$SCRIPT_DIR/out"

# Detect MySQL connector jar
CONNECTOR=""
for f in "$LIB_DIR"/mysql-connector*.jar "$SCRIPT_DIR"/../mysql-connector*.jar \
          /mnt/user-data/uploads/mysql-connector*.jar; do
  if [ -f "$f" ]; then CONNECTOR="$f"; break; fi
done

if [ -z "$CONNECTOR" ]; then
  echo "❌  MySQL connector JAR not found."
  echo "    Download it from https://dev.mysql.com/downloads/connector/j/"
  echo "    Place it in: $LIB_DIR/"
  exit 1
fi

mkdir -p "$OUT_DIR" "$LIB_DIR"

echo "✔  Using connector: $CONNECTOR"
echo "⚙  Compiling Java sources..."

# Auto-detect javac (handles systems where only JRE is on PATH)
JAVAC=$(command -v javac 2>/dev/null || \
        find /usr/lib/jvm -name javac 2>/dev/null | head -1)
if [ -z "$JAVAC" ]; then
  echo "❌  javac not found. Install JDK: sudo apt install openjdk-21-jdk"
  exit 1
fi
echo "✔  Using javac: $JAVAC"

$JAVAC -cp "$CONNECTOR" \
      -sourcepath "$JAVA_DIR" \
      -d "$OUT_DIR" \
      "$JAVA_DIR"/SHA.java \
      "$JAVA_DIR"/DatabaseConnection.java \
      "$JAVA_DIR"/Block.java \
      "$JAVA_DIR"/VotingSystem.java \
      "$JAVA_DIR"/VotingApi.java

echo "✅  Build successful → $OUT_DIR"
echo ""
echo "Run with:"
echo "  java -cp \"$OUT_DIR:$CONNECTOR\" VotingApi"
