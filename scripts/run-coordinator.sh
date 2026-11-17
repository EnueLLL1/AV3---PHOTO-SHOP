#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

if [[ ! -d "out/main" ]]; then
  echo "Erro: diretorio 'out/main' nao encontrado. Execute ./scripts/build.sh antes." >&2
  exit 1
fi

exec java -Xmx3g -cp "out/main:lib/jocl-2.0.5.jar:tools/mpj/lib/*" estudio.net.CoordinatorMain "$@"
