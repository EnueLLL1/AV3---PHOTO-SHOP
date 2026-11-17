#!/usr/bin/env bash
set -euo pipefail

# Script central: verifica dependencias, compila, roda toda a suite JUnit 5
# e executa a bancada end-to-end (Coordenador RMI/TCP + BENCHMARK_ALL + concorrencia).
# Uso: ./scripts/run-all.sh [--full] [--skip-benchmark] [--filter SOBEL|BLUR|SHARPEN]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

FULL=0
SKIP_BENCHMARK=0
FILTER="SOBEL"
CONCURRENT_CLIENTS=4
RMI_PORT=1099
TCP_PORT=9090

while [[ $# -gt 0 ]]; do
  case "$1" in
    --full) FULL=1; shift ;;
    --skip-benchmark) SKIP_BENCHMARK=1; shift ;;
    --filter) FILTER="$2"; shift 2 ;;
    --concurrent-clients) CONCURRENT_CLIENTS="$2"; shift 2 ;;
    *) echo "Opcao desconhecida: $1" >&2; exit 1 ;;
  esac
done

if [[ ! -f "lib/jocl-2.0.5.jar" || ! -f "lib/junit-platform-console-standalone-1.10.2.jar" || ! -f "tools/mpj/lib/mpj.jar" ]]; then
  echo "[run-all] Dependencias ausentes. Executando setup-deps.sh..."
  "$SCRIPT_DIR/setup-deps.sh"
fi

echo ""
echo "[1/4] Build (main + test)..."
"$SCRIPT_DIR/build.sh"

echo ""
echo "[2/4] Executando suite JUnit 5..."
java -jar "lib/junit-platform-console-standalone-1.10.2.jar" execute \
  --class-path "out/test:out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/mpj.jar" \
  --scan-class-path

if [[ "$SKIP_BENCHMARK" -eq 1 ]]; then
  echo ""
  echo "[run-all] Concluido (benchmark ignorado via --skip-benchmark)."
  exit 0
fi

if [[ "$FULL" -eq 1 ]]; then
  COUNT=20; WIDTH=4000; HEIGHT=3000
else
  COUNT=4; WIDTH=1200; HEIGHT=900
fi

mkdir -p work
COORD_LOG="work/bench_coordinator.log"
COORD_PID=""

cleanup() {
  if [[ -n "$COORD_PID" ]] && kill -0 "$COORD_PID" 2>/dev/null; then
    echo ""
    echo "[run-all] Encerrando CoordinatorMain (PID $COORD_PID)..."
    kill "$COORD_PID" 2>/dev/null || true
    wait "$COORD_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo ""
echo "[3/4] Subindo CoordinatorMain (RMI:$RMI_PORT, TCP:$TCP_PORT)..."
java -Xmx3g -cp "out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/*" \
  estudio.net.CoordinatorMain --host 127.0.0.1 --rmi-port "$RMI_PORT" --tcp-port "$TCP_PORT" \
  >"$COORD_LOG" 2>&1 &
COORD_PID=$!

READY=0
for _ in $(seq 1 30); do
  if ! kill -0 "$COORD_PID" 2>/dev/null; then
    echo "CoordinatorMain encerrou inesperadamente:" >&2
    cat "$COORD_LOG" >&2 || true
    exit 1
  fi
  if (echo >/dev/tcp/127.0.0.1/"$TCP_PORT") 2>/dev/null; then
    READY=1
    break
  fi
  sleep 0.5
done

if [[ "$READY" -ne 1 ]]; then
  echo "Timeout aguardando CoordinatorMain abrir a porta TCP $TCP_PORT." >&2
  cat "$COORD_LOG" >&2 || true
  exit 1
fi

echo "[3/4] Executando BENCHMARK_ALL ($COUNT imagens ${WIDTH}x${HEIGHT}, filtro $FILTER)..."
"$SCRIPT_DIR/run-client.sh" \
  --host 127.0.0.1 --rmi-port "$RMI_PORT" \
  --mode BENCHMARK_ALL --filter "$FILTER" \
  --generate --count "$COUNT" --width "$WIDTH" --height "$HEIGHT" \
  --input work/bench_input --output work/bench_out

echo ""
echo "[4/4] Executando teste de concorrencia (--concurrent-demo $CONCURRENT_CLIENTS)..."
"$SCRIPT_DIR/run-client.sh" \
  --host 127.0.0.1 --rmi-port "$RMI_PORT" \
  --filter "$FILTER" \
  --input work/bench_input --output work/concurrent_out \
  --concurrent-demo "$CONCURRENT_CLIENTS"

echo ""
echo "[run-all] Toda a bancada passou com sucesso!"
