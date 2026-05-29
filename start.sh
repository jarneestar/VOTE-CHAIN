#!/bin/bash
# ============================================================
# VoteChain — One-shot startup
# Starts: MySQL (assumed running), Python face API, Java API
# Opens:  frontend/index.html in default browser
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"

# ── Locate MySQL connector ───────────────────────────────────
CONNECTOR=""
for f in "$SCRIPT_DIR"/lib/mysql-connector*.jar \
          /mnt/user-data/uploads/mysql-connector*.jar \
          "$SCRIPT_DIR"/mysql-connector*.jar; do
  if [ -f "$f" ]; then CONNECTOR="$f"; break; fi
done

if [ -z "$CONNECTOR" ]; then
  echo "❌  MySQL connector JAR not found. Put it in $SCRIPT_DIR/lib/"
  exit 1
fi

# ── Build Java if needed ─────────────────────────────────────
if [ ! -d "$SCRIPT_DIR/out" ] || [ -z "$(ls -A "$SCRIPT_DIR/out" 2>/dev/null)" ]; then
  echo "⚙  Building Java..."
  bash "$SCRIPT_DIR/build.sh"
fi

# ── Check Python deps ────────────────────────────────────────
python3 -c "import flask, face_recognition, cv2, numpy" 2>/dev/null || {
  echo "⚙  Installing Python dependencies..."
  pip3 install flask flask-cors face_recognition opencv-python numpy --quiet
}

# ── Start Python face server ─────────────────────────────────
echo "▶  Starting Python Face Server (port 5000)..."
cd "$SCRIPT_DIR/python"
python3 face_server.py >"$LOG_DIR/face_server.log" 2>&1 &
PYTHON_PID=$!
echo "   PID=$PYTHON_PID  log=$LOG_DIR/face_server.log"

sleep 2
curl -s http://localhost:5000/ > /dev/null && echo "✅  Face server is up" || echo "⚠  Face server may still be starting"

# ── Start Java API ───────────────────────────────────────────
echo "▶  Starting Java Voting API (port 4567)..."
cd "$SCRIPT_DIR"
java -cp "out:$CONNECTOR" VotingApi >"$LOG_DIR/voting_api.log" 2>&1 &
JAVA_PID=$!
echo "   PID=$JAVA_PID  log=$LOG_DIR/voting_api.log"

sleep 3
curl -s http://localhost:4567/health > /dev/null && echo "✅  Java API is up" || echo "⚠  Java API may still be starting"

# ── Open frontend ─────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " VoteChain is running!"
echo "  Java API  → http://localhost:4567"
echo "  Face API  → http://localhost:5000"
echo "  Frontend  → open frontend/index.html in your browser"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Press Ctrl+C to stop all services."

# ── Open browser if possible ──────────────────────────────────
FRONTEND="$SCRIPT_DIR/frontend/index.html"
if command -v xdg-open &>/dev/null; then xdg-open "$FRONTEND" &
elif command -v open &>/dev/null;    then open     "$FRONTEND" &
fi

# ── Cleanup on exit ───────────────────────────────────────────
cleanup() {
  echo ""
  echo "Stopping services..."
  kill $PYTHON_PID $JAVA_PID 2>/dev/null || true
  echo "Done."
}
trap cleanup EXIT INT TERM

# Wait for both processes
wait $PYTHON_PID $JAVA_PID
